package io.smartdata.foundation.runtime.workspace

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import tools.jackson.databind.json.JsonMapper

data class ProfileResult(val asset: AssetView, val findings: List<FindingView>)

/** Rules compare explicit expectations with observed samples. They do not infer an enterprise's business truth. */
class DeterministicAssetProfiler(private val clock: Clock) {
    private val mapper = JsonMapper.builder().build()
    fun profile(source: SourceRecord, observation: ObservedAsset, scanId: String): ProfileResult {
        validateRows(observation.rows)
        val names = observation.rows.flatMap { it.keys }.distinct().sorted()
        val canonical = mapper.writeValueAsString(observation.rows.map { it.toSortedMap() })
        // The content fingerprint must remain stable when only the collection
        // timestamp changes. Freshness is separate evidence, not a content edit.
        val hash = digest("${source.id}\u0000${observation.name}\u0000$canonical")
        val assetId = "asset-${digest("${source.id}:${observation.name}").take(20)}"
        val columns = names.map { name ->
            val values = observation.rows.map { it[name] }
            val types = values.filterNot(::missing).map(::cellType).distinct()
            ColumnProfile(name, when (types.size) { 0 -> "EMPTY"; 1 -> types.single(); else -> "MIXED" },
                values.count(::missing), values.filterNot(::missing).map(::canonicalCell).distinct().size)
        }
        val asset = AssetView(assetId, source.id, observation.name, source.provenance, observation.rows.size,
            observation.totalRows, MAX_SAMPLE_ROWS, observation.totalRows == null || observation.totalRows > observation.rows.size || observation.sampling.contains("TEXT_CAPPED") || observation.sampling.contains("VALUES_CAPPED"),
            columns, observation.observedAt, hash, observation.sampling)
        val evidence = FindingEvidence(hash, observation.rows.size, observation.totalRows, MAX_SAMPLE_ROWS, observation.observedAt, observation.sampling)
        val findings = mutableListOf<FindingView>()
        fun finding(rule: String, title: String, severity: FindingSeverity, column: String?, observed: String, expected: String, suggestion: String) {
            findings += FindingView("finding-${digest("$scanId:$assetId:$rule:$column").take(24)}", source.id, assetId, scanId,
                source.provenance, rule, title, severity, column, observed, expected, evidence, suggestion)
        }
        if (observation.rows.isEmpty()) {
            finding("DQ.EMPTY_ASSET.v1", "未取得可分析的数据行", FindingSeverity.MEDIUM, null, "实际观察到 0 行", "至少有 1 行可观察记录", "核对接口筛选条件、账号授权与源系统是否确有业务数据。")
        }
        columns.filter { it.missingCount > 0 }.forEach { column ->
            finding("DQ.MISSING_VALUES.v1", "${column.name} 存在空值", FindingSeverity.MEDIUM, column.name,
                "样本 ${asset.rowCount} 行中 ${column.missingCount} 行为空值或空白文本", "当前基线：已观察列不得缺值；业务负责人可据字段含义调整要求",
                "确认该列是否为必填业务字段；若必填，定位上游采集或同步缺口。")
        }
        columns.filter { it.inferredType == "MIXED" }.forEach { column ->
            finding("DQ.MIXED_TYPES.v1", "${column.name} 混用了不同数据类型", FindingSeverity.MEDIUM, column.name,
                "同一列存在文本、数值或布尔值的混合", "同一列使用约定一致的标量类型", "与数据负责人确认字段类型，并在入口加入类型校验。")
        }
        val duplicateRows = observation.rows.size - observation.rows.map { row -> mapper.writeValueAsString(row.toSortedMap()) }.distinct().size
        if (duplicateRows > 0) finding("DQ.DUPLICATE_ROWS.v1", "存在完全重复的记录", FindingSeverity.MEDIUM, null,
            "样本中额外重复记录 $duplicateRows 行", "完整记录不重复；需由业务确认是否允许重复事件", "使用业务唯一键核对是否重复采集，避免直接删除合法的重复事件。")
        source.primaryKey?.let { key ->
            if (key !in names) finding("DQ.PRIMARY_KEY_MISSING.v1", "配置的业务唯一键不存在", FindingSeverity.HIGH, key,
                "没有观察到列 $key", "数据必须包含已配置的唯一键列", "修正字段映射后重新盘点。")
            else {
                val values = observation.rows.map { it[key] }.filterNot(::missing).map(::canonicalCell)
                val duplicates = values.size - values.distinct().size
                if (duplicates > 0) finding("DQ.DUPLICATE_PRIMARY_KEY.v1", "业务唯一键出现重复", FindingSeverity.HIGH, key,
                    "非空键值额外重复 $duplicates 次（仅当前样本）", "已声明的业务唯一键在观察范围内不重复", "核查源系统记录和合并规则；不要据此自动合并客户或订单。")
            }
        }
        val observedAt = Instant.parse(observation.observedAt)
        val now = clock.instant()
        if (observedAt.isAfter(now.plusSeconds(300))) finding("DQ.FUTURE_OBSERVATION.v1", "数据采集时间晚于当前时钟", FindingSeverity.HIGH, null,
            "采集时间 ${observation.observedAt}", "采集时间不超过服务时钟 5 分钟", "核对源系统时钟与时区，未核对前不要将未来时间作为最新状态。")
        if (Duration.between(observedAt, now).toHours() > source.freshnessMaxHours) {
            finding("DQ.STALE_OBSERVATION.v1", "本次输入的数据快照已过期", FindingSeverity.MEDIUM, null,
                "采集时间距当前 ${Duration.between(observedAt, now).toHours()} 小时", "采集时间距当前不超过 ${source.freshnessMaxHours} 小时", "重新获取来源数据；旧快照不能证明当前业务状态。")
        }
        source.freshnessColumn?.let { column ->
            if (column !in names) finding("DQ.FRESHNESS_COLUMN_MISSING.v1", "配置的更新时间列不存在", FindingSeverity.MEDIUM, column,
                "没有观察到列 $column", "存在已配置的更新时间列", "核对字段映射和 API 投影。")
            else {
                val values = observation.rows.map { it[column] }.filterNot(::missing)
                val dates = values.mapNotNull(::parseInstant)
                val invalid = values.size - dates.size
                if (invalid > 0) finding("DQ.INVALID_TIMESTAMP.v1", "更新时间格式无法解析", FindingSeverity.MEDIUM, column,
                    "$invalid 个非空时间值不是带时区的 ISO-8601 时间", "更新时间包含明确时区，例如 2026-10-25T08:00:00Z", "在来源接口统一时间格式，避免擅自猜测时区。")
                if (dates.any { it.isAfter(now.plusSeconds(300)) }) finding("DQ.FUTURE_TIMESTAMP.v1", "存在未来的业务更新时间", FindingSeverity.MEDIUM, column,
                    "${dates.count { it.isAfter(now.plusSeconds(300)) }} 个时间值超过当前时钟 5 分钟", "更新时间不得明显晚于当前时钟", "核查上游时钟和时间字段含义。")
                val latest = dates.filter { !it.isAfter(now.plusSeconds(300)) }.maxOrNull()
                if (latest != null && Duration.between(latest, now).toHours() > source.freshnessMaxHours) {
                    finding("DQ.STALE_DATA.v1", "业务数据长时间未更新", FindingSeverity.HIGH, column,
                        "样本中最新更新时间 $latest，距当前 ${Duration.between(latest, now).toHours()} 小时", "样本最新更新时间距当前不超过 ${source.freshnessMaxHours} 小时",
                        "核查同步链路和业务活跃情况；静态主数据可能需要不同阈值。")
                }
            }
        }
        return ProfileResult(asset, findings)
    }

    private fun missing(value: Any?) = value == null || value is String && value.isBlank()
    private fun cellType(value: Any?): String = when (value) { is Number -> "NUMBER"; is Boolean -> "BOOLEAN"; else -> "STRING" }
    private fun canonicalCell(value: Any?): String = when (value) {
        null -> "null"
        is Number -> "n:${value.toString()}"
        is Boolean -> "b:$value"
        else -> "s:${value.toString().length}:${value}"
    }
    private fun parseInstant(value: Any?): Instant? = try { Instant.parse(value?.toString()) } catch (_: Exception) {
        try { OffsetDateTime.parse(value?.toString()).toInstant() } catch (_: Exception) { null }
    }
}
