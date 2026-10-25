package io.smartdata.foundation.runtime.workspace

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/** Business priorities choose work to investigate; they never prove a business outcome. */
enum class BusinessGoalType { DATA_RELIABILITY, ANALYTICS_TRUST, ORDER_FULFILLMENT, INVENTORY_ACCURACY, COST_CONTROL, CUSTOM }
enum class BusinessGoalPriority { HIGH, MEDIUM, LOW }
enum class MetricDefinitionStatus { DRAFT, CONFIRMED }

data class BusinessGoal(
    val id: String,
    val title: String,
    val type: BusinessGoalType,
    val expectedOutcome: String,
    val priority: BusinessGoalPriority,
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val sourceIds: List<String> = emptyList(),
)

/** Formula text is documentation, never SQL or an executable expression. */
data class MetricDefinitionInput(
    val id: String,
    val name: String,
    val businessDefinition: String = "",
    val owner: String = "",
    val timeZone: String = "",
    val currency: String? = null,
    val formulaDescription: String = "",
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val sourceIds: List<String> = emptyList(),
)

data class MetricDefinition(
    val id: String,
    val name: String,
    val businessDefinition: String,
    val owner: String,
    val timeZone: String,
    val currency: String?,
    val formulaDescription: String,
    val sourceIds: List<String>,
    val status: MetricDefinitionStatus = MetricDefinitionStatus.DRAFT,
    val stateVersion: Long = 1,
    val confirmedBy: String? = null,
    val confirmedAt: String? = null,
    val calculationStatus: String = "NOT_COMPUTED",
) {
    fun input() = MetricDefinitionInput(id, name, businessDefinition, owner, timeZone, currency, formulaDescription, sourceIds)
}

data class BusinessBrief(
    val version: Long,
    val enterpriseName: String,
    val businessDescription: String,
    val goals: List<BusinessGoal>,
    val includedSourceIds: List<String>,
    val metrics: List<MetricDefinition>,
    val updatedAt: String,
    val updatedBy: String,
    val modelContextExportEnabled: Boolean = false,
)

data class SaveBusinessBriefCommand(
    val expectedStateVersion: Long,
    val expectedBriefVersion: Long,
    val enterpriseName: String,
    val businessDescription: String = "",
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val goals: List<BusinessGoal> = emptyList(),
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val includedSourceIds: List<String> = emptyList(),
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val metrics: List<MetricDefinitionInput> = emptyList(),
)

data class ConfirmMetricDefinitionCommand(
    val expectedStateVersion: Long,
    val expectedBriefVersion: Long,
    val expectedMetricVersion: Long,
)

/** A local, explicit mapping from goals to technical checks, not business causal inference. */
internal class BusinessBriefPlanner(private val state: PersistedWorkspace) {
    data class PlannedFinding(val finding: FindingView, val goals: List<BusinessGoal>, val rank: Int, val reason: String)

    private val brief = state.businessBrief
    private val included = brief?.includedSourceIds?.toSet() ?: state.sources.map { it.id }.toSet()
    private val latestScans = state.sources.filter { it.status == SourceStatus.SCANNED }.associate { source ->
        source.id to state.scans.lastOrNull { it.sourceId == source.id && it.status == "SUCCEEDED" }?.id
    }
    private val currentByTask = state.findings.filter { it.active }.associateBy(::taskId)

    val findings: List<PlannedFinding> = state.findings.filter { finding ->
        finding.active && finding.sourceId in included && latestScans[finding.sourceId] == finding.scanId
    }.mapNotNull { finding ->
        val goals = matchingGoals(finding)
        if (brief != null && brief.goals.isNotEmpty() && goals.isEmpty()) null
        else {
            val rank = (goals.minOfOrNull { it.priority.ordinal } ?: 0) * 100 + finding.severity.ordinal * 10
            val reason = if (goals.isEmpty()) "本轮未设匹配的业务目标，暂按规则严重程度排序；不代表业务目标已达成。"
                else "关联“${goals.first().title}”${priorityLabel(goals.first().priority)}优先级目标；这是检查适用性关联，不代表目标已达成。"
            PlannedFinding(finding, goals, rank, reason)
        }
    }.sortedWith(compareBy<PlannedFinding> { it.rank }.thenBy { it.finding.id })
    private val plannedByTask = findings.associateBy { taskId(it.finding) }

    fun task(task: TaskView): TaskView {
        val current = currentByTask[task.id]
        val planned = plannedByTask[task.id]
        val source = (current ?: state.findings.find { it.id == task.findingId })?.sourceId
        val sourceStatus = state.sources.find { it.id == source }?.status
        val scopeReason = when {
            source !in included -> "来源未纳入本轮范围；历史任务和证据保留，不能据此认定已修复。"
            sourceStatus == SourceStatus.DISCONNECTED -> "来源授权已撤销，暂停本轮安排；历史证据不能继续用于确认修复。"
            sourceStatus == SourceStatus.FAILED -> "来源最近一次盘点失败，暂停本轮安排；旧证据不代表最新状态。"
            source !in latestScans -> "来源未有当前成功盘点，暂停本轮安排；不能据旧证据认定修复。"
            current == null -> "最新盘点已无同类有效发现；任务结果仍以独立复核记录为准。"
            planned == null -> "此类检查暂未关联本轮业务目标；历史任务保留，不能据此认定已修复。"
            else -> "属于本轮来源与目标范围；仍需业务人员核对适用性。"
        }
        return task.copy(goalIds = planned?.goals?.map { it.id } ?: emptyList(), inCurrentScope = planned != null,
            scopeReason = scopeReason, businessBriefVersion = brief?.version, priorityRank = planned?.rank ?: 9999,
            priorityReason = planned?.reason ?: scopeReason,
            currentFindingId = current?.takeIf { latestScans[it.sourceId] == it.scanId }?.id)
    }

    fun tasks(): List<TaskView> = state.tasks.map(::task).sortedWith(compareBy<TaskView> { !it.inCurrentScope }
        .thenBy { it.priorityRank }.thenBy { it.createdAt }.thenBy { it.id })

    fun forRole(role: AssistantRole): List<PlannedFinding> = findings.filter {
        when (role) {
            AssistantRole.OPERATIONS -> operationsRule(it.finding)
            AssistantRole.ANALYSIS -> !operationsRule(it.finding)
            AssistantRole.DECISION -> it.finding.severity == FindingSeverity.HIGH || it.goals.any { goal -> goal.priority == BusinessGoalPriority.HIGH }
        }
    }

    fun assistant(assistant: AssistantView): AssistantView {
        val assigned = forRole(assistant.role)
        val knownTasks = state.tasks.map { it.id }.toSet()
        return assistant.copy(findingIds = assigned.map { it.finding.id },
            taskIds = assigned.map { taskId(it.finding) }.filter { it in knownTasks }.distinct(),
            focusGoalIds = assigned.flatMap { it.goals }.map { it.id }.distinct(), scopeSourceIds = included.sorted(),
            scopeNotice = when {
                brief == null -> "尚未登记业务目标，暂按检查规则安排工作；没有推断企业目标。"
                included.isEmpty() -> "本轮未纳入任何来源，暂停当前任务安排；历史任务与证据仍保留。"
                brief.goals.isEmpty() -> "已按本轮来源限制工作；尚未设业务目标，暂按规则严重程度排序。"
                assigned.isEmpty() -> "当前目标与来源下，本职责没有匹配的有效发现；不代表企业健康或目标已完成。"
                else -> "已按本轮目标优先级和来源范围安排；目标、公司与口径原文未发送给模型。"
            })
    }

    private fun matchingGoals(finding: FindingView): List<BusinessGoal> = brief?.goals.orEmpty().filter { goal ->
        (goal.sourceIds.isEmpty() || finding.sourceId in goal.sourceIds) && when (goal.type) {
            BusinessGoalType.DATA_RELIABILITY -> operationsRule(finding)
            BusinessGoalType.ANALYTICS_TRUST, BusinessGoalType.COST_CONTROL -> !operationsRule(finding)
            BusinessGoalType.ORDER_FULFILLMENT, BusinessGoalType.INVENTORY_ACCURACY -> finding.ruleId.startsWith("DQ.")
            BusinessGoalType.CUSTOM -> true
        }
    }.sortedWith(compareBy<BusinessGoal> { it.priority.ordinal }.thenBy { it.id })

    companion object {
        fun taskId(finding: FindingView) = "task-${digest("${finding.sourceId}:${finding.assetId}:${finding.ruleId}:${finding.column}").take(24)}"
        fun operationsRule(finding: FindingView) = listOf("STALE", "TIMESTAMP", "OBSERVATION", "EMPTY_ASSET", "FRESHNESS").any { finding.ruleId.contains(it) }
        private fun priorityLabel(priority: BusinessGoalPriority) = when (priority) {
            BusinessGoalPriority.HIGH -> "高"; BusinessGoalPriority.MEDIUM -> "中"; BusinessGoalPriority.LOW -> "低"
        }
    }
}
