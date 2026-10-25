package io.smartdata.foundation.runtime.workspace.lineage

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.smartdata.foundation.runtime.workspace.Provenance

enum class LineageLayer { L0, L1, L2, L3 }
enum class LineageNodeKind { SOURCE, TABLE, FIELD, BUSINESS_ENTITY, METRIC, DECISION_PLAN }
enum class LineageShape { DATABASE_CYLINDER, TABLE_GRID, FIELD_PILL, ENTITY_HEXAGON, METRIC_CIRCLE, DECISION_DIAMOND }
enum class LineageEvidenceNature { OBSERVED, DECLARED, PLANNED }
enum class LineageMaterialization { METADATA_ONLY, NOT_MATERIALIZED }
enum class LineageSensitivity { UNKNOWN, PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }
enum class LineageFreshness { CURRENT, STALE, UNKNOWN }
enum class LineageEdgeKind { CONTAINS, MAPPED_TO, FEEDS_INTO, INFORMS }
enum class LineageLineStyle { SOLID, DASHED, DOTTED }
enum class LineageTransformation { NOT_APPLICABLE, UNKNOWN, DECLARED_ONLY }

data class LineageRef(val type: String, val id: String, val sourceId: String? = null, val assetId: String? = null,
    val fieldName: String? = null, val definitionVersion: Long? = null)
data class LineageEvidenceRef(val sourceId: String, val assetId: String, val scanId: String, val sha256: String, val observedAt: String)
data class LineageNode(
    val id: String, val label: String, val summary: String, val layer: LineageLayer, val kind: LineageNodeKind,
    val shape: LineageShape, val ref: LineageRef, val evidenceNature: LineageEvidenceNature,
    val materialization: LineageMaterialization, val provenance: Provenance,
    val sourceIds: List<String>, val evidenceRefs: List<LineageEvidenceRef> = emptyList(),
    val observedAt: String? = null, val sensitivity: LineageSensitivity = LineageSensitivity.UNKNOWN,
    val freshness: LineageFreshness = LineageFreshness.UNKNOWN,
    val authorization: String = "METADATA_VISIBLE", val scopeStatus: String = "IN_SCOPE",
    val notices: List<String> = emptyList(),
)
data class LineageEdge(val id: String, val from: String, val to: String, val kind: LineageEdgeKind,
    val evidenceNature: LineageEvidenceNature, val lineStyle: LineageLineStyle,
    val transformationStatus: LineageTransformation, val description: String,
    val evidenceRefs: List<LineageEvidenceRef> = emptyList())
data class LineageScope(val briefVersion: Long?, val sourceIds: List<String>, val excludedAuthorizedSourceCount: Int)
data class LineageCoverage(val partial: Boolean, val totalSources: Int, val shownSources: Int, val totalTables: Int,
    val shownTables: Int, val totalFields: Int, val shownFields: Int, val omittedDerivedDefinitions: Int,
    val nodeLimit: Int = 3000, val edgeLimit: Int = 6000, val fieldLimit: Int = 256)
data class LineageGraph(val stateVersion: Long, val mappingVersion: Long, val generatedAt: String,
    val scope: LineageScope, val nodes: List<LineageNode>, val edges: List<LineageEdge>, val notices: List<String>, val coverage: LineageCoverage)
data class LineageFieldPage(val stateVersion: Long, val sourceId: String, val assetId: String,
    val totalFields: Int, val offset: Int, val limit: Int, val nodes: List<LineageNode>, val edges: List<LineageEdge>, val hasMore: Boolean)

/** References only; source rows, executable SQL and model prompts never enter this contract. */
data class PhysicalFieldRef(val sourceId: String, val assetId: String, val fieldName: String)
data class EntityLineageMapping(val id: String, val name: String, val entityType: String,
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL) val inputFields: List<PhysicalFieldRef>,
    val transformationDescription: String)
data class MetricLineageMapping(val metricId: String, val definitionVersion: Long,
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL) val inputEntityIds: List<String> = emptyList(),
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL) val inputFields: List<PhysicalFieldRef> = emptyList(),
    val transformationDescription: String)
data class DecisionLineageMapping(val goalId: String,
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL) val inputMetricIds: List<String>,
    val decisionQuestion: String)
data class LineageMappings(val version: Long = 0,
    val entities: List<EntityLineageMapping> = emptyList(), val metrics: List<MetricLineageMapping> = emptyList(),
    val decisions: List<DecisionLineageMapping> = emptyList(), val updatedBy: String? = null, val updatedAt: String? = null)
data class SaveLineageMappingsCommand(val expectedStateVersion: Long, val expectedMappingVersion: Long,
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL) val entities: List<EntityLineageMapping> = emptyList(),
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL) val metrics: List<MetricLineageMapping> = emptyList(),
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL) val decisions: List<DecisionLineageMapping> = emptyList())
