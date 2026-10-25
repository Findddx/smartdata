package io.smartdata.foundation.runtime.workspace.lineage

import io.smartdata.foundation.runtime.workspace.*
import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.OptimisticStateException
import io.smartdata.foundation.service.ResourceNotFoundException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

/** A metadata projection, never an entity extractor or an expression executor. */
class LineageProjection(private val clock: Clock) {
    fun validate(state: PersistedWorkspace, command: SaveLineageMappingsCommand): LineageMappings {
        if (state.lineageMappings.version != command.expectedMappingVersion)
            throw OptimisticStateException("lineage-mappings", state.tenantId, command.expectedMappingVersion, state.lineageMappings.version)
        require(command.entities.size <= 50 && command.metrics.size <= 40 && command.decisions.size <= 12) { "来源映射超过本地工作区上限" }
        require(command.entities.sumOf { it.inputFields.size } + command.metrics.sumOf { it.inputFields.size } <= 512 &&
            command.metrics.sumOf { it.inputEntityIds.size } <= 256 && command.decisions.sumOf { it.inputMetricIds.size } <= 128) { "整包映射最多512项字段引用、256项实体输入和128项决策指标输入" }
        fun identifiers(ids: List<String>) { require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) }) { "定义编号必须唯一且为1–64位字母数字或._-" } }
        fun text(value: String, maximum: Int) = value.trim().also { require(it.isNotBlank() && it.length <= maximum && '\u0000' !in it) { "映射说明不能为空或超过长度上限" } }
        identifiers(command.entities.map { it.id }); identifiers(command.metrics.map { it.metricId }); identifiers(command.decisions.map { it.goalId })
        val scoped = scopedSources(state)
        val scopeIds = scoped.map { it.id }.toSet()
        // Validation uses actual metadata, independently of the graph's display budget.
        val physicalAssets = scoped.flatMap { source -> currentAssets(state, source).map { (source.id to it.id) to it } }.toMap()
        fun fields(refs: List<PhysicalFieldRef>): List<PhysicalFieldRef> {
            require(refs.size <= 100 && refs.distinct().size == refs.size) { "字段引用须唯一且不超过100项" }
            require(refs.all { ref -> physicalAssets[ref.sourceId to ref.assetId]?.columns?.any { it.name == ref.fieldName } == true }) { "字段必须来自本轮可见、成功盘点且纳入范围的来源" }
            return refs
        }
        fun unmixed(sources: Set<String>) {
            require(state.sources.filter { it.id in sources }.map { it.provenance }.distinct().size <= 1) { "演示来源与用户来源不能混入同一个派生定义" }
        }
        val entities = command.entities.map { entity ->
            require(entity.inputFields.isNotEmpty()) { "业务实体必须明确至少一个已观察的输入字段；不会猜测客户实体" }
            val refs = fields(entity.inputFields); unmixed(refs.map { it.sourceId }.toSet())
            entity.copy(name = text(entity.name, 100), entityType = text(entity.entityType, 80), inputFields = refs,
                transformationDescription = text(entity.transformationDescription, 2000))
        }
        val entityById = entities.associateBy { it.id }
        val metrics = command.metrics.map { mapping ->
            val metric = state.businessBrief?.metrics?.find { it.id == mapping.metricId }
                ?: throw BusinessValidationException("指标必须引用业务约定中已有的口径")
            if (mapping.definitionVersion != metric.stateVersion)
                throw OptimisticStateException("metric-definition", metric.id, mapping.definitionVersion, metric.stateVersion)
            require(mapping.inputEntityIds.distinct().size == mapping.inputEntityIds.size && mapping.inputEntityIds.size <= 50 && mapping.inputEntityIds.all { it in entityById }) { "指标只能引用本版本明确声明的L1实体" }
            val refs = fields(mapping.inputFields)
            require(refs.isNotEmpty() || mapping.inputEntityIds.isNotEmpty()) { "指标映射必须明确输入" }
            val sources = refs.map { it.sourceId }.toSet() + mapping.inputEntityIds.flatMap { entityById.getValue(it).inputFields.map { ref -> ref.sourceId } }
            require(metric.sourceIds.isNotEmpty() && sources.all { it in metric.sourceIds } && metric.sourceIds.all { it in scopeIds }) { "指标输入必须符合已有口径和本轮可见范围" }
            unmixed(sources)
            mapping.copy(inputFields = refs, transformationDescription = text(mapping.transformationDescription, 2000))
        }
        val metricById = state.businessBrief?.metrics.orEmpty().associateBy { it.id }
        val decisions = command.decisions.map { mapping ->
            val goal = state.businessBrief?.goals?.find { it.id == mapping.goalId } ?: throw BusinessValidationException("决策计划必须引用已有业务目标")
            require(mapping.inputMetricIds.isNotEmpty() && mapping.inputMetricIds.size <= 40 && mapping.inputMetricIds.distinct().size == mapping.inputMetricIds.size) { "决策计划须明确唯一的指标输入" }
            require(mapping.inputMetricIds.all { it in metricById }) { "决策不能引用未知指标" }
            val sources = mapping.inputMetricIds.flatMap { metricById.getValue(it).sourceIds }.toSet()
            require(sources.isNotEmpty() && sources.all { it in scopeIds } && (goal.sourceIds.isEmpty() || sources.all { it in goal.sourceIds })) { "决策指标必须符合目标与本轮来源范围" }
            unmixed(sources)
            mapping.copy(decisionQuestion = text(mapping.decisionQuestion, 1000))
        }
        // The typed dependencies are strictly L0 -> L1/L2 -> L3; no arbitrary
        // node references exist, so same-layer derived cycles/reverse edges cannot be expressed.
        return LineageMappings(state.lineageMappings.version + 1, entities, metrics, decisions)
    }

    fun graph(state: PersistedWorkspace): LineageGraph {
        val authorized = state.sources.filter { it.status != SourceStatus.DISCONNECTED && (it.tenantId.isEmpty() || it.tenantId == state.tenantId) }
        val included = state.businessBrief?.includedSourceIds?.toSet() ?: authorized.map { it.id }.toSet()
        val sources = authorized.filter { it.id in included }
        val sourceById = sources.associateBy { it.id }
        val nodes = mutableListOf<LineageNode>(); val edges = mutableListOf<LineageEdge>()
        var totalTables = 0; var totalFields = 0; var shownTables = 0; var shownFields = 0; var omittedDerived = 0
        val notices = mutableListOf("这是当前身份可见且纳入本轮范围的工作区元数据与来源声明；未读取或返回原始数据行。", "METADATA_VISIBLE表示当前用户可看工作区元数据，不代表来源读写或执行权限，也不证明凭据已经验证。", "敏感级别尚未登记，统一显示未知；不会按字段名称猜测。", "当前未采集源系统SQL/作业变换，字段级计算血缘未知。", "实体、指标与决策计划均未物化；确认口径不代表已经计算。")
        fun edge(from: LineageNode, to: LineageNode, kind: LineageEdgeKind, nature: LineageEvidenceNature,
            transform: LineageTransformation, description: String) {
            edges += LineageEdge(id("edge", from.id, to.id, kind.name), from.id, to.id, kind, nature,
                when (nature) { LineageEvidenceNature.OBSERVED -> LineageLineStyle.SOLID; LineageEvidenceNature.DECLARED -> LineageLineStyle.DASHED; LineageEvidenceNature.PLANNED -> LineageLineStyle.DOTTED },
                transform, description, from.evidenceRefs)
        }
        for (source in sources.sortedBy { it.id }) {
            val scan = state.scans.lastOrNull { it.sourceId == source.id && it.status == "SUCCEEDED" }.takeIf { source.status == SourceStatus.SCANNED }
            val assets = if (scan == null) emptyList() else state.assets.filter { it.sourceId == source.id && it.id in scan.assetIds }.sortedBy { it.id }
            totalTables += assets.size; totalFields += assets.sumOf { it.columns.size }
            if (nodes.size >= NODE_LIMIT || edges.size >= EDGE_LIMIT) continue
            val evidence = assets.map { LineageEvidenceRef(source.id, it.id, scan!!.id, it.evidenceSha256, it.observedAt) }
            val root = LineageNode(id("source", source.id), source.name, if (scan == null) "来源已登记，尚无当前可用的成功盘点。" else "已完成只读盘点；不代表已提取业务实体。",
                LineageLayer.L0, LineageNodeKind.SOURCE, LineageShape.DATABASE_CYLINDER, LineageRef("SOURCE", source.id, source.id),
                if (scan == null) LineageEvidenceNature.DECLARED else LineageEvidenceNature.OBSERVED, LineageMaterialization.METADATA_ONLY, source.provenance,
                listOf(source.id), evidence, scan?.completedAt, freshness = freshness(assets.map { it.observedAt }, source.freshnessMaxHours),
                notices = listOf("来源状态：${source.status.name}", "端点与凭据不进入血缘图。"))
            nodes += root
            for (asset in assets) {
                if (nodes.size >= NODE_LIMIT || edges.size >= EDGE_LIMIT) continue
                val refs = evidence.filter { it.assetId == asset.id }
                val visibleColumns = asset.columns.sortedBy { it.name }.take(minOf((FIELD_LIMIT - shownFields).coerceAtLeast(0),
                    (NODE_LIMIT - nodes.size - 1).coerceAtLeast(0), (EDGE_LIMIT - edges.size - 1).coerceAtLeast(0)))
                val table = root.copy(id = id("table", source.id, asset.id), label = asset.name,
                    summary = "只读盘点观察到表/数据集及${asset.columns.size}个字段；${if (asset.sampled) "仅抽样观察" else "本次快照完整性按扫描记录说明"}。",
                    kind = LineageNodeKind.TABLE, shape = LineageShape.TABLE_GRID, ref = LineageRef("ASSET", asset.id, source.id, asset.id),
                    evidenceRefs = refs, observedAt = asset.observedAt, freshness = freshness(listOf(asset.observedAt), source.freshnessMaxHours),
                    notices = listOf("字段之间的变换关系未知。", "本表显示${visibleColumns.size}/${asset.columns.size}个字段，未显示字段可按表分页查询。", asset.sampling))
                nodes += table; edge(root, table, LineageEdgeKind.CONTAINS, LineageEvidenceNature.OBSERVED, LineageTransformation.NOT_APPLICABLE, "盘点观察到来源包含该表/数据集。")
                shownTables += 1
                for (column in visibleColumns) {
                    val field = table.copy(id = id("field", source.id, asset.id, column.name), label = column.name,
                        summary = "观察类型：${column.inferredType}；类型来自本次盘点，业务含义未确认。", kind = LineageNodeKind.FIELD, shape = LineageShape.FIELD_PILL,
                        ref = LineageRef("FIELD", column.name, source.id, asset.id, column.name), notices = listOf("没有采集到该字段的上游SQL或变换作业。"))
                    nodes += field; edge(table, field, LineageEdgeKind.CONTAINS, LineageEvidenceNature.OBSERVED, LineageTransformation.NOT_APPLICABLE, "盘点观察到字段属于该表；此线不是字段转换。")
                    shownFields += 1
                }
            }
        }
        val fieldByRef = nodes.filter { it.kind == LineageNodeKind.FIELD }.associateBy { PhysicalFieldRef(it.ref.sourceId!!, it.ref.assetId!!, it.ref.fieldName!!) }
        val visibleSourceRoots = nodes.filter { it.kind == LineageNodeKind.SOURCE }.map { it.ref.id }.toSet()
        val entities = mutableMapOf<String, LineageNode>()
        fun provenance(ids: List<String>): Provenance? = ids.mapNotNull { sourceById[it]?.provenance }.distinct().singleOrNull()
        for (mapping in state.lineageMappings.entities) {
            val inputs = mapping.inputFields.mapNotNull(fieldByRef::get)
            if (inputs.size != mapping.inputFields.size || inputs.isEmpty()) { omittedDerived += 1; continue }
            val sourceIds = inputs.flatMap { it.sourceIds }.distinct().sorted()
            val provenance = provenance(sourceIds) ?: continue
            val node = LineageNode(id("entity", mapping.id), mapping.name, "${mapping.entityType}：人工登记输入与处理说明；尚未提取实体记录。", LineageLayer.L1,
                LineageNodeKind.BUSINESS_ENTITY, LineageShape.ENTITY_HEXAGON, LineageRef("ENTITY_DEFINITION", mapping.id, definitionVersion = state.lineageMappings.version),
                LineageEvidenceNature.DECLARED, LineageMaterialization.NOT_MATERIALIZED, provenance, sourceIds,
                inputs.flatMap { it.evidenceRefs }.distinct(), notices = listOf(mapping.transformationDescription, "文字说明未执行；没有客户画像或业务实体产物。"))
            if (nodes.size >= NODE_LIMIT || edges.size + inputs.size > EDGE_LIMIT) { omittedDerived += 1; continue }
            nodes += node; entities[mapping.id] = node
            inputs.forEach { edge(it, node, LineageEdgeKind.MAPPED_TO, LineageEvidenceNature.DECLARED, LineageTransformation.DECLARED_ONLY, mapping.transformationDescription) }
        }
        val metrics = mutableMapOf<String, LineageNode>()
        for (metric in state.businessBrief?.metrics.orEmpty()) {
            if (metric.sourceIds.any { it !in sourceById }) continue
            if (metric.sourceIds.any { it !in visibleSourceRoots }) { omittedDerived += 1; continue }
            val sourceIds = metric.sourceIds.distinct().sorted()
            val provenance = if (sourceIds.isEmpty()) Provenance.USER else provenance(sourceIds) ?: continue
            val mapping = state.lineageMappings.metrics.find { it.metricId == metric.id && it.definitionVersion == metric.stateVersion }
            val entityInputs = mapping?.inputEntityIds.orEmpty().mapNotNull(entities::get)
            val fieldInputs = mapping?.inputFields.orEmpty().mapNotNull(fieldByRef::get)
            if (mapping != null && (entityInputs.size != mapping.inputEntityIds.size || fieldInputs.size != mapping.inputFields.size)) { omittedDerived += 1; continue }
            val node = LineageNode(id("metric", metric.id), metric.name, "口径版本${metric.stateVersion}，${metric.status.name}；未执行公式或计算数值。", LineageLayer.L2,
                LineageNodeKind.METRIC, LineageShape.METRIC_CIRCLE, LineageRef("METRIC_DEFINITION", metric.id, definitionVersion = metric.stateVersion),
                LineageEvidenceNature.DECLARED, LineageMaterialization.NOT_MATERIALIZED, provenance, sourceIds,
                notices = listOf("权威口径来自业务约定；不另建指标定义。", if (mapping == null) "字段与变换尚未明确；只有口径登记的来源声明。" else mapping.transformationDescription))
            val inputs = entityInputs + fieldInputs
            if (nodes.size >= NODE_LIMIT || edges.size + inputs.size + sourceIds.size > EDGE_LIMIT) { omittedDerived += 1; continue }
            nodes += node; metrics[metric.id] = node
            inputs.forEach { edge(it, node, LineageEdgeKind.FEEDS_INTO, LineageEvidenceNature.DECLARED, LineageTransformation.DECLARED_ONLY, mapping!!.transformationDescription) }
            val covered = inputs.flatMap { it.sourceIds }.toSet()
            sourceIds.filter { it !in covered }.forEach { sourceId ->
                val sourceNode = nodes.first { it.kind == LineageNodeKind.SOURCE && it.ref.id == sourceId }
                edge(sourceNode, node, LineageEdgeKind.FEEDS_INTO, LineageEvidenceNature.DECLARED, LineageTransformation.UNKNOWN, "已有口径声明该来源；尚不清楚具体字段和变换，未执行计算。")
            }
        }
        for (goal in state.businessBrief?.goals.orEmpty()) {
            if (goal.sourceIds.any { it !in sourceById }) continue
            if (goal.sourceIds.any { it !in visibleSourceRoots }) { omittedDerived += 1; continue }
            val mapping = state.lineageMappings.decisions.find { it.goalId == goal.id }
            val inputs = mapping?.inputMetricIds.orEmpty().mapNotNull(metrics::get)
            if (mapping != null && inputs.size != mapping.inputMetricIds.size) { omittedDerived += 1; continue }
            val sourceIds = (goal.sourceIds + inputs.flatMap { it.sourceIds }).distinct().sorted()
            val provenance = if (sourceIds.isEmpty()) Provenance.USER else provenance(sourceIds) ?: continue
            val node = LineageNode(id("decision", goal.id), goal.title, "决策计划：${mapping?.decisionQuestion ?: goal.expectedOutcome}", LineageLayer.L3,
                LineageNodeKind.DECISION_PLAN, LineageShape.DECISION_DIAMOND, LineageRef("BUSINESS_GOAL", goal.id, definitionVersion = state.businessBrief?.version),
                LineageEvidenceNature.PLANNED, LineageMaterialization.NOT_MATERIALIZED, provenance, sourceIds,
                notices = listOf("目标与计划不代表已形成决策、采取行动或取得业务结果。"))
            if (nodes.size >= NODE_LIMIT || edges.size + inputs.size > EDGE_LIMIT) { omittedDerived += 1; continue }
            nodes += node; inputs.forEach { edge(it, node, LineageEdgeKind.INFORMS, LineageEvidenceNature.PLANNED, LineageTransformation.DECLARED_ONLY, "计划使用该指标支持决策；当前没有已计算结果。") }
        }
        val coverage = LineageCoverage(nodes.count { it.kind == LineageNodeKind.SOURCE } < sources.size || shownTables < totalTables || shownFields < totalFields || omittedDerived > 0,
            sources.size, nodes.count { it.kind == LineageNodeKind.SOURCE }, totalTables, shownTables, totalFields, shownFields, omittedDerived)
        if (coverage.partial) notices += "当前视图覆盖不全：显示${shownTables}/${totalTables}张表、${shownFields}/${totalFields}个字段；${omittedDerived}项派生依赖未完整显示。缺少连线不代表没有依赖，请按表分页查看。"
        notices += "覆盖数字只统计本轮可见的已登记元数据，不代表企业全量数据或业务记录覆盖。"
        if (state.lineageMappings.entities.size > entities.size) notices += "部分实体声明的输入已过期、失去授权或移出范围，当前图未展示其依赖链。"
        return LineageGraph(state.stateVersion, state.lineageMappings.version, clock.instant().toString(),
            LineageScope(state.businessBrief?.version, sources.map { it.id }.sorted(), authorized.count { it.id !in included }), nodes, edges, notices, coverage)
    }

    fun fields(state: PersistedWorkspace, sourceId: String, assetId: String, offset: Int, limit: Int): LineageFieldPage {
        require(offset in 0..100000 && limit in 1..128) { "字段分页offset须在0–100000，limit须在1–128" }
        val source = scopedSources(state).find { it.id == sourceId } ?: throw ResourceNotFoundException("visible lineage source", sourceId)
        val asset = currentAssets(state, source).find { it.id == assetId } ?: throw ResourceNotFoundException("current lineage asset", assetId)
        val scan = state.scans.last { it.sourceId == sourceId && it.status == "SUCCEEDED" }
        val evidence = listOf(LineageEvidenceRef(sourceId, assetId, scan.id, asset.evidenceSha256, asset.observedAt))
        val nodes = asset.columns.sortedBy { it.name }.drop(offset).take(limit).map { column ->
            LineageNode(id("field", sourceId, assetId, column.name), column.name, "观察类型：${column.inferredType}；业务含义未确认。", LineageLayer.L0,
                LineageNodeKind.FIELD, LineageShape.FIELD_PILL, LineageRef("FIELD", column.name, sourceId, assetId, column.name),
                LineageEvidenceNature.OBSERVED, LineageMaterialization.METADATA_ONLY, source.provenance, listOf(sourceId), evidence,
                asset.observedAt, freshness = freshness(listOf(asset.observedAt), source.freshnessMaxHours), notices = listOf("字段转换未知；此页只展示物理元数据。"))
        }
        val tableId = id("table", sourceId, assetId)
        val edges = nodes.map { LineageEdge(id("edge", tableId, it.id, LineageEdgeKind.CONTAINS.name), tableId, it.id,
            LineageEdgeKind.CONTAINS, LineageEvidenceNature.OBSERVED, LineageLineStyle.SOLID, LineageTransformation.NOT_APPLICABLE, "盘点观察到该字段属于选中表。", evidence) }
        return LineageFieldPage(state.stateVersion, sourceId, assetId, asset.columns.size, offset, limit, nodes, edges, offset + nodes.size < asset.columns.size)
    }

    private fun scopedSources(state: PersistedWorkspace): List<SourceRecord> = state.sources.filter { source ->
        source.status != SourceStatus.DISCONNECTED && (source.tenantId.isEmpty() || source.tenantId == state.tenantId) &&
            (state.businessBrief == null || source.id in state.businessBrief!!.includedSourceIds)
    }
    private fun currentAssets(state: PersistedWorkspace, source: SourceRecord): List<AssetView> {
        if (source.status != SourceStatus.SCANNED) return emptyList()
        val scan = state.scans.lastOrNull { it.sourceId == source.id && it.status == "SUCCEEDED" } ?: return emptyList()
        return state.assets.filter { it.sourceId == source.id && it.id in scan.assetIds }
    }

    private fun freshness(times: List<String>, maxHours: Long): LineageFreshness {
        if (times.isEmpty()) return LineageFreshness.UNKNOWN
        val parsed = times.map { runCatching { Instant.parse(it) }.getOrNull() }
        if (parsed.any { it == null || it.isAfter(clock.instant().plusSeconds(300)) }) return LineageFreshness.UNKNOWN
        return if (parsed.any { it!!.isBefore(clock.instant().minusSeconds(maxHours * 3600)) }) LineageFreshness.STALE else LineageFreshness.CURRENT
    }
    private fun id(type: String, vararg values: String): String {
        val input = (listOf(type) + values).joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        return "lineage-$type-" + MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
    companion object { const val NODE_LIMIT = 3000; const val EDGE_LIMIT = 6000; const val FIELD_LIMIT = 256 }
}
