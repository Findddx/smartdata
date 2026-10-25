package io.smartdata.foundation.runtime.workspace

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

enum class SourceKind { JSON_SNAPSHOT, HTTP_JSON, JDBC_POSTGRES_READONLY }
enum class Provenance { USER, DEMO }
enum class SourceStatus { CONFIGURED, SCANNED, FAILED, DISCONNECTED }
enum class FindingSeverity { HIGH, MEDIUM, LOW }
enum class AssistantRole { OPERATIONS, ANALYSIS, DECISION }
enum class TaskStatus { PROPOSED, ACCEPTED, COMPLETED, VERIFIED, REOPENED }

/** These DTOs deliberately contain no row bodies or source credentials. */
data class WorkspaceView(
    val stateVersion: Long,
    val mode: String = "LOCAL_PERSISTENT",
    val sources: List<SourceView>,
    val assets: List<AssetView>,
    val scans: List<ScanView>,
    val findings: List<FindingView>,
    val assistants: List<AssistantView>,
    val tasks: List<TaskView>,
    val comments: List<TaskComment>,
    val messages: List<AssistantMessage>,
    val audit: List<WorkspaceAudit>,
    val capabilities: WorkspaceCapabilities,
    val businessBrief: BusinessBrief? = null,
)

data class WorkspaceCapabilities(
    val sourceKinds: List<SourceKind> = SourceKind.entries,
    val httpAllowedHosts: List<String> = emptyList(),
    val postgresAllowedHosts: List<String> = emptyList(),
    val maxRowsPerAsset: Int = 1000,
    val maxResponseBytes: Int = 1048576,
    val mutationsAffectSourceSystems: Boolean = false,
    val assistantMethod: String = "DETERMINISTIC_RULES",
)

data class SourceView(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val provenance: Provenance,
    val status: SourceStatus,
    val endpointLabel: String?,
    val lastScannedAt: String?,
    val assetCount: Int,
    val credentialConfigured: Boolean,
    val stateVersion: Long,
    val lastError: String? = null,
)

data class ColumnProfile(val name: String, val inferredType: String, val missingCount: Int, val distinctCount: Int)
data class AssetView(
    val id: String,
    val sourceId: String,
    val name: String,
    val provenance: Provenance,
    val rowCount: Int,
    val totalRows: Long?,
    val sampleLimit: Int,
    val sampled: Boolean,
    val columns: List<ColumnProfile>,
    val observedAt: String,
    val evidenceSha256: String,
    val sampling: String = "",
)

data class ScanView(
    val id: String,
    val sourceId: String,
    val provenance: Provenance,
    val status: String,
    val startedAt: String,
    val completedAt: String,
    val assetIds: List<String>,
    val findingIds: List<String>,
    val method: String = "READ_ONLY_RULE_SCAN_V1",
    val error: String? = null,
)

data class FindingEvidence(
    val sha256: String,
    val sampledRows: Int,
    val totalRows: Long?,
    val sampleLimit: Int,
    val observedAt: String,
    val sampling: String,
)

data class FindingView(
    val id: String,
    val sourceId: String,
    val assetId: String,
    val scanId: String,
    val provenance: Provenance,
    val ruleId: String,
    val title: String,
    val severity: FindingSeverity,
    val column: String?,
    val observed: String,
    val expected: String,
    val evidence: FindingEvidence,
    val suggestion: String,
    val active: Boolean = true,
)

data class AssistantView(
    val id: String,
    val role: AssistantRole,
    val name: String,
    val responsibility: String,
    val status: String = "READY",
    val method: String = "DETERMINISTIC_RULES",
    val findingIds: List<String> = emptyList(),
    val taskIds: List<String> = emptyList(),
    val focusGoalIds: List<String> = emptyList(),
    val scopeSourceIds: List<String> = emptyList(),
    val scopeNotice: String = "尚未登记业务目标，暂按检查规则安排工作。",
)

data class TaskView(
    val id: String,
    val assistantId: String,
    val findingId: String,
    val title: String,
    val description: String,
    val status: TaskStatus = TaskStatus.PROPOSED,
    val ownerId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val completionNote: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val stateVersion: Long = 1,
    val verifiedBy: String? = null,
    val goalIds: List<String> = emptyList(),
    val inCurrentScope: Boolean = true,
    val scopeReason: String = "按已观察问题安排工作。",
    val businessBriefVersion: Long? = null,
    val createdUnderBriefVersion: Long? = null,
    val priorityRank: Int = 100,
    val priorityReason: String = "按规则严重程度排序。",
    val currentFindingId: String? = null,
)

data class TaskComment(val id: String, val taskId: String, val actorId: String, val body: String, val evidenceRefs: List<String>, val createdAt: String)
data class AssistantMessage(
    val id: String,
    val assistantId: String,
    val actorId: String,
    val question: String,
    val answer: String,
    val evidenceRefs: List<String>,
    val createdAt: String,
    val method: String = "DETERMINISTIC_RULES",
    val status: String = "SUCCEEDED",
    val uncertainty: List<String> = emptyList(),
    val errorCode: String? = null,
    val trace: AssistantModelTrace? = null,
)

/** Model provenance, not a prompt/body telemetry record. */
data class AssistantModelTrace(
    val providerProfile: String,
    val modelId: String,
    val modelVersion: String,
    val promptVersion: String,
    val contextSha256: String,
    val responseId: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

/** Disk-only conservative admission reservations; never included in WorkspaceView. */
data class ModelBudgetReservation(val id: String, val reservedTokens: Long)
data class WorkspaceAudit(val id: String, val actorId: String, val event: String, val resourceId: String, val occurredAt: String, val stateVersion: Long)
data class WorkspaceReport(val generatedAt: String, val stateVersion: Long, val method: String, val title: String, val sections: List<ReportSection>, val evidenceRefs: List<String>)
data class ReportSection(val title: String, val text: String)

data class VersionCommand(val expectedStateVersion: Long)
data class CreateSourceCommand(
    val expectedStateVersion: Long,
    val name: String,
    val kind: SourceKind,
    val endpoint: String? = null,
    val credentialEnvRef: String? = null,
    val usernameEnvRef: String? = null,
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.SET)
    val snapshotRows: List<Map<String, Any?>> = emptyList(),
    val observedAt: String? = null,
    val primaryKey: String? = null,
    val freshnessColumn: String? = null,
    val freshnessMaxHours: Long = 24,
    val schema: String = "public",
)
data class TaskTransitionCommand(val expectedStateVersion: Long, val status: TaskStatus, val note: String, val evidenceRefs: List<String> = emptyList())
data class TaskCommentCommand(val expectedStateVersion: Long, val body: String, val evidenceRefs: List<String> = emptyList())
data class AssistantMessageCommand(val expectedStateVersion: Long, val question: String, val taskId: String? = null)

/** Disk-only schema. credentialEnvRef stores a reference name, never its resolved secret. */
data class SourceRecord(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val provenance: Provenance,
    val endpoint: String?,
    val credentialEnvRef: String?,
    val usernameEnvRef: String?,
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.SET)
    val snapshotRows: List<Map<String, Any?>>,
    val observedAt: String?,
    val primaryKey: String?,
    val freshnessColumn: String?,
    val freshnessMaxHours: Long,
    val schema: String,
    val status: SourceStatus = SourceStatus.CONFIGURED,
    val lastScannedAt: String? = null,
    val stateVersion: Long = 1,
    val lastError: String? = null,
    val tenantId: String = "",
)

data class PersistedWorkspace(
    val formatVersion: Int = 1,
    val tenantId: String,
    var stateVersion: Long = 0,
    val sources: MutableList<SourceRecord> = mutableListOf(),
    val assets: MutableList<AssetView> = mutableListOf(),
    val scans: MutableList<ScanView> = mutableListOf(),
    val findings: MutableList<FindingView> = mutableListOf(),
    val assistants: MutableList<AssistantView> = mutableListOf(),
    val tasks: MutableList<TaskView> = mutableListOf(),
    val comments: MutableList<TaskComment> = mutableListOf(),
    val messages: MutableList<AssistantMessage> = mutableListOf(),
    val audit: MutableList<WorkspaceAudit> = mutableListOf(),
    val commands: MutableMap<String, String> = mutableMapOf(),
    val modelBudgetReservations: MutableList<ModelBudgetReservation> = mutableListOf(),
    var businessBrief: BusinessBrief? = null,
    val businessBriefHistory: MutableList<BusinessBrief> = mutableListOf(),
)
