package io.smartdata.foundation.runtime.postgres

import io.smartdata.foundation.service.BusinessValidationException
import org.jooq.Record
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

internal object PostgresValues {
    val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val objectMap = object : TypeReference<Map<String, Any>>() {}
    private val stringMap = object : TypeReference<Map<String, String>>() {}
    fun identifier(value: String): String = value.also { require(it.isNotBlank() && it.length <= 256 && '\u0000' !in it) { "业务标识无效" } }
    fun storageId(tenantId: String, type: String, businessId: String): UUID {
        identifier(businessId)
        return runCatching { UUID.fromString(businessId) }.getOrNull()?.takeIf { it.toString() == businessId }
            ?: UUID.nameUUIDFromBytes("smartdata:$tenantId:$type:$businessId".toByteArray(StandardCharsets.UTF_8))
    }
    // Every plain-SQL placeholder receiving this value has an explicit
    // ::timestamptz cast. jOOQ's PostgreSQL OffsetDateTime binding can otherwise
    // send VARCHAR in plain SQL, even though the Java value carries an offset.
    fun timestamp(value: Instant): OffsetDateTime = value.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)
    fun json(value: Any): String = mapper.writeValueAsString(value).also {
        require(it.toByteArray(StandardCharsets.UTF_8).size <= 65536) { "单条结构化字段不得超过64KiB" }
    }
    fun objectMap(value: String): Map<String, Any> = mapper.readValue(value, objectMap)
    fun stringMap(value: String): Map<String, String> = mapper.readValue(value, stringMap)
    fun sameInput(left:Map<String,Any>,right:Map<String,Any>):Boolean = json(requireNotNull(canonical(left)))==json(requireNotNull(canonical(right)))
    private fun canonical(value:Any?):Any? = when(value) {
        is Map<*,*> -> value.entries.associate { it.key.toString() to canonical(it.value) }.toSortedMap()
        is Collection<*> -> value.map(::canonical)
        is Number -> value.toString().toBigDecimal().stripTrailingZeros()
        else -> value
    }
    fun uuidArray(values: Collection<UUID>): String = values.joinToString(",", "{", "}")
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun bounded(size: Int, maximum: Int = 1000) { require(size <= maximum) { "单个聚合的关联记录超过${maximum}条上限" } }
    fun overflow(size: Int) { if (size > 1000) throw BusinessValidationException("结果超过1000项；请使用分页仓储查询，禁止隐式截断") }
}

internal fun Record.text(name: String): String = requireNotNull(get(name, String::class.java))
internal fun Record.optionalText(name: String): String? = get(name, String::class.java)
internal fun Record.number(name: String): Long = requireNotNull(get(name, Long::class.javaObjectType))
internal fun Record.optionalNumber(name: String): Long? = get(name, Long::class.javaObjectType)
internal fun Record.instant(name: String): Instant = requireNotNull(get(name, OffsetDateTime::class.java)).toInstant()
internal fun Record.optionalInstant(name: String): Instant? = get(name, OffsetDateTime::class.java)?.toInstant()
internal fun Record.uuid(name: String): UUID = requireNotNull(get(name, UUID::class.java))
