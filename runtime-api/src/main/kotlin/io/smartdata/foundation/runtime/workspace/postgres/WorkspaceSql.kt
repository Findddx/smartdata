package io.smartdata.foundation.runtime.workspace.postgres

import io.smartdata.foundation.service.BusinessValidationException
import org.jooq.DSLContext
import org.jooq.Record
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** SQL names are hard-coded by repository methods; all data values remain bound parameters. */
internal object WorkspaceSql {
    val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()
    data class Strings(val values: List<String>)
    data class Json(val value: Any)
    fun array(values: List<String>) = Strings(values)
    fun json(value: Any?) = value?.let(::Json)
    fun encode(value: Any): String = mapper.writeValueAsString(value)
    fun <T> decode(text: String, type: Class<T>): T = mapper.readValue(text, type)

    fun save(db: DSLContext, table: String, fields: Map<String, Any?>, keys: List<String>, mutable: Set<String> = emptySet()) {
        require(table.startsWith("workspace_") && fields.keys.all { it.matches(Regex("[a-z_]+")) })
        val where = keys.joinToString(" and ") { "$it=?" }
        val keyValues = keys.map { fields[it] }.toTypedArray()
        val old = db.fetchOne("select * from $table where $where", *keyValues)
        if (old == null) {
            val inserted = db.execute("insert into $table(${fields.keys.joinToString()}) values(${fields.values.joinToString { expression(it) }}) on conflict do nothing",
                *fields.values.map(::binding).toTypedArray())
            if (inserted == 1) return
            // A conflicting concurrent insert must match actual values, not just a caller-provided digest.
            val stored = requireNotNull(db.fetchOne("select * from $table where $where", *keyValues))
            compare(stored, fields, emptySet())
            return
        }
        compare(old, fields, mutable)
        val changes = fields.filterKeys { it in mutable && it !in keys }
        if (changes.isNotEmpty()) db.execute("update $table set ${changes.entries.joinToString { "${it.key}=${expression(it.value)}" }} where $where",
            *(changes.values.map(::binding) + keyValues.toList()).toTypedArray())
    }

    private fun compare(row: Record, fields: Map<String, Any?>, ignored: Set<String>) {
        fields.filterKeys { it !in ignored }.forEach { (name, expected) ->
            val actual = if (expected is Strings) row.strings(name) else if (expected is Json)
                row.get(name)?.toString()?.let { mapper.readValue(it, Any::class.java) } else row.get(name)
            val value = when (expected) { is Strings -> expected.values; is Json -> mapper.readValue(encode(expected.value), Any::class.java); else -> expected }
            if (canonical(actual) != canonical(value)) throw BusinessValidationException("工作区历史标识对应的内容已改变，拒绝覆盖: $name")
        }
    }

    private fun canonical(value: Any?): Any? = when (value) {
        is Number -> value.toString().toBigDecimal().stripTrailingZeros()
        is Map<*, *> -> value.entries.associate { it.key.toString() to canonical(it.value) }.toSortedMap()
        is Collection<*> -> value.map(::canonical)
        is Array<*> -> value.map(::canonical)
        else -> value
    }
    private fun expression(value: Any?): String = when(value) { is Strings -> "ARRAY(SELECT jsonb_array_elements_text(?::jsonb))"; is Json -> "?::jsonb"; else -> "?" }
    private fun binding(value: Any?): Any? = when(value) { is Strings -> encode(value.values); is Json -> encode(value.value); else -> value }
}

internal fun Record.text(name: String) = requireNotNull(get(name, String::class.java))
internal fun Record.optional(name: String): String? = get(name, String::class.java)
internal fun Record.long(name: String) = requireNotNull(get(name, Long::class.javaObjectType))
internal fun Record.optionalLong(name: String): Long? = get(name, Long::class.javaObjectType)
internal fun Record.int(name: String) = requireNotNull(get(name, Int::class.javaObjectType))
internal fun Record.bool(name: String) = requireNotNull(get(name, Boolean::class.javaObjectType))
internal fun Record.strings(name: String): List<String> {
    val value = get(name) ?: return emptyList()
    return when(value) {
        is java.sql.Array -> (value.array as Array<*>).map { requireNotNull(it).toString() }
        is Array<*> -> value.map { requireNotNull(it).toString() }
        else -> throw BusinessValidationException("工作区关系数组类型无效")
    }
}
