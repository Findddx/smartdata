package io.smartdata.foundation.runtime.workspace.lineage

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.runtime.workspace.*
import io.smartdata.foundation.service.AuthorizationDeniedException
import io.smartdata.foundation.service.OptimisticStateException
import io.smartdata.foundation.service.ResourceNotFoundException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WorkspaceLineageTest {
    @TempDir lateinit var directory: Path
    private val clock = Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), ZoneOffset.UTC)
    private val admin = Actor("admin", "管理员", "tenant-a", setOf(BusinessRole.ADMIN))
    private val reader = Actor("auditor", "审计员", "tenant-a", setOf(BusinessRole.AUDITOR))
    private lateinit var service: WorkspaceService
    private var sequence = 0
    private fun key() = "lineage-test-${++sequence}"
    @BeforeEach fun setup() { service = WorkspaceService(LocalWorkspaceStore(directory), listOf(JsonSnapshotSource()), clock, WorkspaceCapabilities()) }
    private fun source(scan: Boolean = true): String {
        val view = service.createSource(admin, key(), CreateSourceCommand(service.view(admin).stateVersion, "订单来源", SourceKind.JSON_SNAPSHOT,
            snapshotRows = listOf(mapOf("id" to "O1", "customer_id" to "C1", "paid_cents" to 100)), observedAt = clock.instant().toString()))
        val id = view.sources.last().id
        if (scan) service.scan(admin, id, key(), VersionCommand(view.stateVersion))
        return id
    }
    private fun brief(source: String) {
        service.saveBusinessBrief(admin, key(), SaveBusinessBriefCommand(service.view(admin).stateVersion, service.view(admin).businessBrief?.version ?: 0,
            "合成企业", goals = listOf(BusinessGoal("goal", "核对收入", BusinessGoalType.ANALYTICS_TRUST, "核对来源", BusinessGoalPriority.HIGH, listOf(source))),
            includedSourceIds = listOf(source), metrics = listOf(MetricDefinitionInput("net", "净收入", "支付减退款", "财务", "UTC", "CNY", "支付减退款", listOf(source)))))
    }
    private fun mappings(): SaveLineageMappingsCommand {
        val graph = service.lineage(reader)
        val ref = graph.nodes.first { it.kind == LineageNodeKind.FIELD && it.label == "customer_id" }.ref
        return SaveLineageMappingsCommand(graph.stateVersion, graph.mappingVersion,
            entities = listOf(EntityLineageMapping("customers", "客户实体", "CUSTOMER", listOf(PhysicalFieldRef(ref.sourceId!!, ref.assetId!!, ref.fieldName!!)), "按明确客户编号匹配；仅登记，未提取")),
            metrics = listOf(MetricLineageMapping("net", service.view(admin).businessBrief!!.metrics.single().stateVersion, listOf("customers"), transformationDescription = "按客户汇总支付减退款；仅登记")),
            decisions = listOf(DecisionLineageMapping("goal", listOf("net"), "核对净收入是否可信")))
    }

    @Test fun `empty and configured sources never invent observed entities or fields`() {
        assertTrue(service.lineage(reader).nodes.isEmpty())
        source(false)
        val graph = service.lineage(reader)
        assertEquals(listOf(LineageNodeKind.SOURCE), graph.nodes.map { it.kind })
        assertEquals(LineageEvidenceNature.DECLARED, graph.nodes.single().evidenceNature)
        assertTrue(graph.edges.isEmpty())
        assertTrue(graph.nodes.single().evidenceRefs.isEmpty())
    }

    @Test fun `actual observation only proves source table field containment`() {
        source()
        val graph = service.lineage(reader)
        assertTrue(graph.nodes.any { it.kind == LineageNodeKind.FIELD })
        assertTrue(graph.edges.all { it.kind == LineageEdgeKind.CONTAINS && it.lineStyle == LineageLineStyle.SOLID && it.evidenceNature == LineageEvidenceNature.OBSERVED })
        assertTrue(graph.nodes.all { it.layer == LineageLayer.L0 && it.materialization == LineageMaterialization.METADATA_ONLY && it.sensitivity == LineageSensitivity.UNKNOWN })
        assertTrue(graph.nodes.all { it.authorization == "METADATA_VISIBLE" && it.scopeStatus == "IN_SCOPE" })
        assertTrue(graph.nodes.filter { it.kind == LineageNodeKind.FIELD }.all { it.evidenceRefs.size == 1 && it.ref.fieldName != null })
    }

    @Test fun `declared entities and authoritative metric refs remain unmaterialized with directional evidence`() {
        val id = source(); brief(id)
        val command = mappings()
        service.saveLineageMappings(admin, key(), command)
        val graph = service.lineage(reader)
        assertEquals(1, graph.mappingVersion)
        assertTrue(graph.nodes.any { it.layer == LineageLayer.L1 && it.label == "客户实体" })
        assertTrue(graph.nodes.filter { it.layer != LineageLayer.L0 }.all { it.materialization == LineageMaterialization.NOT_MATERIALIZED })
        assertEquals(service.view(admin).businessBrief!!.metrics.single().stateVersion, graph.nodes.single { it.kind == LineageNodeKind.METRIC }.ref.definitionVersion)
        assertTrue(graph.edges.any { it.lineStyle == LineageLineStyle.DASHED })
        assertTrue(graph.edges.any { it.lineStyle == LineageLineStyle.DOTTED && it.evidenceNature == LineageEvidenceNature.PLANNED })
        val byId = graph.nodes.associateBy { it.id }
        assertTrue(graph.edges.filter { it.kind != LineageEdgeKind.CONTAINS }.all { byId.getValue(it.from).layer.ordinal < byId.getValue(it.to).layer.ordinal })
        val rebuilt = WorkspaceService(LocalWorkspaceStore(directory), listOf(JsonSnapshotSource()), clock, WorkspaceCapabilities())
        assertEquals(graph.nodes, rebuilt.lineage(reader).nodes)
        assertEquals(1, rebuilt.lineageMappings(admin).version)
    }

    @Test fun `admin authority tenant isolation CAS and scope are enforced by server`() {
        val id = source(); brief(id); val command = mappings()
        assertThrows(AuthorizationDeniedException::class.java) { service.saveLineageMappings(reader, key(), command) }
        assertThrows(AuthorizationDeniedException::class.java) { service.lineageMappings(reader) }
        assertTrue(service.lineage(reader.copy(tenantId = "tenant-b")).nodes.isEmpty())
        service.saveLineageMappings(admin, key(), command)
        assertThrows(OptimisticStateException::class.java) { service.saveLineageMappings(admin, key(), command) }
        val current = service.view(admin)
        service.saveBusinessBrief(admin, key(), SaveBusinessBriefCommand(current.stateVersion, current.businessBrief!!.version, "合成企业"))
        assertTrue(service.lineage(reader).nodes.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { service.saveLineageMappings(admin, key(), command.copy(expectedStateVersion = service.view(admin).stateVersion, expectedMappingVersion = 1)) }
    }

    @Test fun `disconnect or changed source configuration removes dependent derived chain`() {
        val id = source(); brief(id); service.saveLineageMappings(admin, key(), mappings())
        val graph = service.lineage(reader)
        assertTrue(graph.nodes.any { it.layer == LineageLayer.L1 })
        val view = service.view(admin)
        service.replaceSnapshot(admin, id, key(), ReplaceSnapshotCommand(view.stateVersion, listOf(mapOf("id" to "O2")), clock.instant().toString()))
        val configured = service.lineage(reader)
        assertFalse(configured.nodes.any { it.kind == LineageNodeKind.FIELD || it.kind == LineageNodeKind.BUSINESS_ENTITY })
        assertFalse(configured.edges.any { it.evidenceNature == LineageEvidenceNature.OBSERVED })
        service.disconnectSource(admin, id, key(), VersionCommand(service.view(admin).stateVersion))
        assertTrue(service.lineage(reader).nodes.isEmpty())
    }

    @Test fun `unknown fields stale metric versions and reverse typed dependencies are rejected`() {
        val id = source(); brief(id); val command = mappings()
        val entity = command.entities.single()
        val bad = entity.copy(inputFields = entity.inputFields.map { it.copy(fieldName = "secret-not-observed") })
        assertThrows(IllegalArgumentException::class.java) { service.saveLineageMappings(admin, key(), command.copy(entities = listOf(bad))) }
        assertThrows(OptimisticStateException::class.java) { service.saveLineageMappings(admin, key(), command.copy(metrics = command.metrics.map { it.copy(definitionVersion = 999) })) }
        assertThrows(IllegalArgumentException::class.java) { service.saveLineageMappings(admin, key(), command.copy(metrics = command.metrics.map { it.copy(inputEntityIds = listOf("net")) })) }
        assertEquals(0, service.lineage(reader).mappingVersion)
    }

    @Test fun `field display budget is explicit bounded and independent from mapping validation`() {
        val source = SourceRecord("source-wide", "合成元数据", SourceKind.JSON_SNAPSHOT, Provenance.USER,
            null, null, null, emptyList(), clock.instant().toString(), null, null, 24, "public", SourceStatus.SCANNED, tenantId = "tenant-wide")
        val assets = (0..3).map { table -> AssetView("asset-$table", source.id, "table-$table", Provenance.USER, 1, 1, 1000, false,
            (0..127).map { field -> ColumnProfile("f%03d".format(field), "STRING", 0, 1) }, clock.instant().toString(), "a".repeat(64)) }
        val state = PersistedWorkspace(tenantId = "tenant-wide", sources = mutableListOf(source), assets = assets.toMutableList(),
            scans = mutableListOf(ScanView("scan-wide", source.id, Provenance.USER, "SUCCEEDED", clock.instant().toString(), clock.instant().toString(), assets.map { it.id }, emptyList())))
        val projection = LineageProjection(clock)
        val initial = projection.graph(state)
        assertTrue(initial.coverage.partial)
        assertEquals(512, initial.coverage.totalFields)
        assertEquals(256, initial.coverage.shownFields)
        assertTrue(initial.nodes.size <= 3000 && initial.edges.size <= 6000)
        val reference = PhysicalFieldRef(source.id, "asset-3", "f127")
        assertFalse(initial.nodes.any { it.ref.assetId == reference.assetId && it.ref.fieldName == reference.fieldName })
        val command = SaveLineageMappingsCommand(0, 0, entities = listOf(EntityLineageMapping("late-fields", "预算外字段实体", "CUSTOM", listOf(reference), "只登记引用，不执行")))
        state.lineageMappings = projection.validate(state, command)
        val bounded = projection.graph(state)
        assertEquals(1, bounded.coverage.omittedDerivedDefinitions)
        assertFalse(bounded.nodes.any { it.kind == LineageNodeKind.BUSINESS_ENTITY })
        val ids = bounded.nodes.map { it.id }.toSet()
        assertTrue(bounded.edges.all { it.from in ids && it.to in ids })
        val first = projection.fields(state, source.id, "asset-3", 0, 64)
        val last = projection.fields(state, source.id, "asset-3", 64, 64)
        assertTrue(first.hasMore); assertFalse(last.hasMore)
        assertEquals(128, first.totalFields)
        assertEquals(128, (first.nodes + last.nodes).map { it.id }.distinct().size)
        assertTrue(last.nodes.any { it.ref.fieldName == "f127" })
        assertThrows(IllegalArgumentException::class.java) { projection.fields(state, source.id, "asset-3", 0, 129) }
        val tooMany = (0..5).map { index -> EntityLineageMapping("entity-$index", "实体", "CUSTOM", (0..99).map { PhysicalFieldRef(source.id, "asset-0", "f%03d".format(it)) }, "仅登记") }
        assertThrows(IllegalArgumentException::class.java) { projection.validate(state, SaveLineageMappingsCommand(0, 1, entities = tooMany)) }
        state.businessBrief = BusinessBrief(1, "合成企业", "", emptyList(), emptyList(), emptyList(), clock.instant().toString(), "admin")
        assertThrows(ResourceNotFoundException::class.java) { projection.fields(state, source.id, "asset-3", 0, 64) }
    }
}
