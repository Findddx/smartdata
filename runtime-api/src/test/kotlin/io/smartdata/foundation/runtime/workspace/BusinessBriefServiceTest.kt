package io.smartdata.foundation.runtime.workspace

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.service.AuthorizationDeniedException
import io.smartdata.foundation.service.IdempotencyConflictException
import io.smartdata.foundation.service.OptimisticStateException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BusinessBriefServiceTest {
    @TempDir lateinit var directory: Path
    private lateinit var service: WorkspaceService
    private val clock = Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), ZoneOffset.UTC)
    private val editor = Actor("operator", "业务人员", "tenant-a", setOf(BusinessRole.ORDER_SPECIALIST))
    private val administrator = Actor("administrator", "管理员", "tenant-a", setOf(BusinessRole.ADMIN))
    private val reviewer = Actor("business-owner", "业务负责人", "tenant-a", setOf(BusinessRole.SALES_OWNER))
    private var sequence = 0
    private fun key() = "business-command-${++sequence}"

    @BeforeEach
    fun setup() { service = createService() }
    private fun createService(reasoning: AssistantReasoningPort = DeterministicAssistantReasoningPort()) =
        WorkspaceService(LocalWorkspaceStore(directory), listOf(JsonSnapshotSource()), clock, WorkspaceCapabilities(), reasoning)

    private fun source(name: String, rows: List<Map<String, Any?>> = listOf(mapOf("id" to "A"), mapOf("id" to "A")),
        observedAt: String = clock.instant().toString()): String {
        val before = service.view(editor)
        val created = service.createSource(administrator, key(), CreateSourceCommand(before.stateVersion, name,
            SourceKind.JSON_SNAPSHOT, snapshotRows = rows, observedAt = observedAt, primaryKey = "id"))
        val id = created.sources.last().id
        service.scan(editor, id, key(), VersionCommand(created.stateVersion))
        return id
    }

    private fun command(sources: List<String>, goals: List<BusinessGoal> = emptyList(),
        metrics: List<MetricDefinitionInput> = emptyList()): SaveBusinessBriefCommand {
        val view = service.view(editor)
        return SaveBusinessBriefCommand(view.stateVersion, view.businessBrief?.version ?: 0, "真实业务名称",
            "业务负责人填写的企业背景，不是系统推断", goals, sources, metrics)
    }

    private fun metric(sourceId: String) = MetricDefinitionInput("net-revenue", "净收入",
        "支付完成金额扣除退款，不包含待支付订单", "财务负责人", "Asia/Shanghai", "CNY",
        "支付金额合计减去已确认退款金额；当前只登记说明", listOf(sourceId))

    @Test
    fun `legacy files without business fields remain readable and first registration is explicit`() {
        Files.writeString(directory.resolve("${digest(editor.tenantId)}.json"),
            """{"formatVersion":1,"tenantId":"tenant-a","stateVersion":7}""")
        val legacy = service.view(editor)
        assertEquals(7, legacy.stateVersion)
        assertNull(legacy.businessBrief)
        assertTrue(service.businessBriefHistory(editor).isEmpty())
        val saved = service.saveBusinessBrief(editor, key(), SaveBusinessBriefCommand(7, 0, "只有已知的企业名称"))
        assertEquals(1, saved.businessBrief!!.version)
        assertTrue(saved.businessBrief!!.goals.isEmpty())
        assertTrue(saved.businessBrief!!.includedSourceIds.isEmpty())
        assertFalse(saved.businessBrief!!.modelContextExportEnabled)
    }

    @Test
    fun `business goal priority changes task order and decision attention using source membership`() {
        val lowSource = source("一般订单")
        val highSource = source("重点分析", listOf(mapOf("id" to "B", "email" to null)))
        val goals = listOf(
            BusinessGoal("low", "次要订单核对", BusinessGoalType.ANALYTICS_TRUST, "明确重复原因", BusinessGoalPriority.LOW, listOf(lowSource)),
            BusinessGoal("high", "先保障重点分析", BusinessGoalType.ANALYTICS_TRUST, "确认必填字段覆盖", BusinessGoalPriority.HIGH, listOf(highSource)),
        )
        var view = service.saveBusinessBrief(editor, key(), command(listOf(lowSource, highSource), goals))
        view = service.provisionTeam(editor, key(), VersionCommand(view.stateVersion))
        val current = view.tasks.filter { it.inCurrentScope }
        assertTrue(current.isNotEmpty())
        assertTrue(current.first().goalIds.contains("high"), "high business priority precedes a technically severe low-goal issue")
        assertEquals(1L, current.first().createdUnderBriefVersion)
        assertTrue(current.first().priorityReason.contains("重点分析"))
        val decision = view.assistants.single { it.role == AssistantRole.DECISION }
        assertTrue(decision.focusGoalIds.contains("high"))
        assertTrue(decision.findingIds.any { id -> view.findings.any { it.id == id && it.sourceId == highSource && it.severity == FindingSeverity.MEDIUM } })
        assertTrue(view.assistants.single { it.role == AssistantRole.OPERATIONS }.taskIds.isEmpty())
    }

    @Test
    fun `goal type filters local assistant work but does not remove unselected source evidence`() {
        val id = source("延迟且重复的订单", observedAt = clock.instant().minusSeconds(172800).toString())
        var view = service.provisionTeam(editor, key(), VersionCommand(service.view(editor).stateVersion))
        val oldTasks = view.tasks.associateBy { it.id }
        val goals = listOf(BusinessGoal("reliability", "先检查同步", BusinessGoalType.DATA_RELIABILITY,
            "了解更新时间为何落后", BusinessGoalPriority.HIGH))
        view = service.saveBusinessBrief(editor, key(), command(listOf(id), goals))
        assertTrue(view.findings.any { it.ruleId == "DQ.DUPLICATE_PRIMARY_KEY.v1" && it.active })
        assertTrue(view.assistants.single { it.role == AssistantRole.ANALYSIS }.taskIds.isEmpty())
        assertTrue(view.assistants.single { it.role == AssistantRole.OPERATIONS }.taskIds.isNotEmpty())
        val unrelated = view.tasks.first { task -> view.findings.any { it.id == task.findingId && it.ruleId == "DQ.DUPLICATE_PRIMARY_KEY.v1" } }
        assertFalse(unrelated.inCurrentScope)
        assertEquals(oldTasks[unrelated.id]!!.status, unrelated.status)
        assertEquals(oldTasks.keys, view.tasks.map { it.id }.toSet())
    }

    @Test
    fun `empty and restored scope preserve completed task status original evidence and stable identity`() {
        val sourceId = source("订单")
        var view = service.provisionTeam(editor, key(), VersionCommand(service.view(editor).stateVersion))
        val task = view.tasks.first()
        view = service.transitionTask(editor, task.id, key(), TaskTransitionCommand(view.stateVersion, TaskStatus.ACCEPTED, "领取核实"))
        view = service.transitionTask(editor, task.id, key(), TaskTransitionCommand(view.stateVersion, TaskStatus.COMPLETED, "提交说明，尚待复核", task.evidenceRefs))
        val completed = view.tasks.first { it.id == task.id }
        view = service.saveBusinessBrief(editor, key(), command(emptyList()))
        val outside = view.tasks.first { it.id == task.id }
        assertFalse(outside.inCurrentScope)
        assertEquals(TaskStatus.COMPLETED, outside.status)
        assertEquals(completed.evidenceRefs, outside.evidenceRefs)
        assertTrue(view.assistants.all { it.taskIds.isEmpty() })
        view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId)))
        val restored = view.tasks.first { it.id == task.id }
        assertTrue(restored.inCurrentScope)
        assertEquals(TaskStatus.COMPLETED, restored.status)
        assertEquals(completed.findingId, restored.findingId)
        assertEquals(completed.evidenceRefs, restored.evidenceRefs)
        assertNull(restored.verifiedBy)
    }

    @Test
    fun `metric confirmation requires complete source-bound definition and business authority`() {
        val sourceId = source("订单")
        var view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), metrics = listOf(MetricDefinitionInput("draft", "待讨论指标"))))
        var brief = view.businessBrief!!
        val confirmation = ConfirmMetricDefinitionCommand(view.stateVersion, brief.version, brief.metrics.single().stateVersion)
        assertThrows(AuthorizationDeniedException::class.java) { service.confirmMetricDefinition(editor, "draft", key(), confirmation) }
        assertThrows(IllegalArgumentException::class.java) { service.confirmMetricDefinition(reviewer, "draft", key(), confirmation) }
        view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), metrics = listOf(metric(sourceId).copy(sourceIds = emptyList()))))
        brief = view.businessBrief!!
        assertThrows(IllegalArgumentException::class.java) {
            service.confirmMetricDefinition(reviewer, "net-revenue", key(), ConfirmMetricDefinitionCommand(view.stateVersion, brief.version, brief.metrics.single().stateVersion))
        }
        assertTrue(service.businessBriefHistory(editor).flatMap { it.metrics }.all { it.status == MetricDefinitionStatus.DRAFT })
    }

    @Test
    fun `changing a confirmed formula returns to draft while unchanged definitions keep confirmation`() {
        val sourceId = source("订单")
        var view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), metrics = listOf(metric(sourceId))))
        var brief = view.businessBrief!!
        view = service.confirmMetricDefinition(reviewer, "net-revenue", key(), ConfirmMetricDefinitionCommand(view.stateVersion, brief.version, brief.metrics.single().stateVersion))
        val confirmed = view.businessBrief!!.metrics.single()
        assertEquals(MetricDefinitionStatus.CONFIRMED, confirmed.status)
        assertEquals(reviewer.id, confirmed.confirmedBy)
        view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), metrics = listOf(metric(sourceId))).copy(businessDescription = "补充背景，口径未变"))
        assertEquals(confirmed, view.businessBrief!!.metrics.single())
        val observations = view.scans
        val formula = "DROP TABLE orders; -- 这是待讨论的文字说明，不能执行"
        view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), metrics = listOf(metric(sourceId).copy(formulaDescription = formula))))
        val changed = view.businessBrief!!.metrics.single()
        assertEquals(MetricDefinitionStatus.DRAFT, changed.status)
        assertNull(changed.confirmedBy)
        assertNull(changed.confirmedAt)
        assertEquals(confirmed.stateVersion + 1, changed.stateVersion)
        assertEquals("NOT_COMPUTED", changed.calculationStatus)
        assertEquals(observations, view.scans, "saving a documented formula must not query or mutate a source")
        val history = service.businessBriefHistory(editor)
        assertEquals(4, history.size)
        assertEquals(confirmed, history[1].metrics.single())
        assertEquals(formula, history.last().metrics.single().formulaDescription)
        assertTrue(service.report(editor).sections.any { it.text.contains("公式说明（未执行）") && it.text.contains("尚未计算或验证指标") })
    }

    @Test
    fun `brief and metric CAS reject stale updates while durable replay preserves one history version`() {
        val sourceId = source("订单")
        val original = command(listOf(sourceId), metrics = listOf(metric(sourceId)))
        val originalKey = key()
        var view = service.saveBusinessBrief(editor, originalKey, original)
        service = createService()
        assertEquals(view.businessBrief, service.saveBusinessBrief(editor, originalKey, original).businessBrief)
        assertEquals(1, service.businessBriefHistory(editor).size)
        assertThrows(IdempotencyConflictException::class.java) { service.saveBusinessBrief(editor, originalKey, original.copy(enterpriseName = "另一名称")) }
        assertThrows(OptimisticStateException::class.java) { service.saveBusinessBrief(editor, key(), original.copy(expectedStateVersion = view.stateVersion)) }
        val brief = view.businessBrief!!
        assertThrows(OptimisticStateException::class.java) {
            service.confirmMetricDefinition(reviewer, "net-revenue", key(), ConfirmMetricDefinitionCommand(view.stateVersion, brief.version, 999))
        }
        val confirm = ConfirmMetricDefinitionCommand(view.stateVersion, brief.version, brief.metrics.single().stateVersion)
        val confirmKey = key()
        view = service.confirmMetricDefinition(reviewer, "net-revenue", confirmKey, confirm)
        service = createService()
        assertEquals(view.businessBrief, service.confirmMetricDefinition(reviewer, "net-revenue", confirmKey, confirm).businessBrief)
        assertEquals(2, service.businessBriefHistory(editor).size)
    }

    @Test
    fun `foreign and unscoped source references invalid calendars and duplicate stable IDs are rejected`() {
        val sourceId = source("订单")
        val base = command(listOf(sourceId), metrics = listOf(metric(sourceId)))
        val goal = BusinessGoal("goal", "核对订单", BusinessGoalType.CUSTOM, "明确观察边界", BusinessGoalPriority.MEDIUM)
        listOf(
            base.copy(includedSourceIds = listOf("foreign-source")),
            base.copy(metrics = listOf(metric(sourceId).copy(sourceIds = listOf("foreign-source")))),
            base.copy(goals = listOf(goal, goal)),
            base.copy(metrics = listOf(metric(sourceId).copy(timeZone = "guess-my-time-zone"))),
            base.copy(metrics = listOf(metric(sourceId).copy(currency = "XXX-invalid"))),
            base.copy(goals = listOf(goal.copy(id = "../unsafe"))),
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { service.saveBusinessBrief(editor, key(), invalid) } }
        assertNull(service.view(editor).businessBrief)
        assertTrue(service.businessBriefHistory(editor).isEmpty())
    }

    @Test
    fun `business history is tenant scoped and audit records retain the actual confirmer`() {
        val sourceId = source("订单")
        var view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), metrics = listOf(metric(sourceId))))
        val brief = view.businessBrief!!
        view = service.confirmMetricDefinition(reviewer, "net-revenue", key(), ConfirmMetricDefinitionCommand(view.stateVersion, brief.version, brief.metrics.single().stateVersion))
        assertTrue(service.businessBriefHistory(editor.copy(tenantId = "another-tenant")).isEmpty())
        assertNull(service.view(editor.copy(tenantId = "another-tenant")).businessBrief)
        val audit = view.audit.last()
        assertEquals("metric_definition.confirmed", audit.event)
        assertEquals(reviewer.id, audit.actorId)
        assertEquals("net-revenue", audit.resourceId)
        assertThrows(AuthorizationDeniedException::class.java) {
            service.saveBusinessBrief(editor.copy(roles = setOf(BusinessRole.AUDITOR)), key(), command(listOf(sourceId)))
        }
    }

    @Test
    fun `business raw text is not added to the model port and local goal planning still applies`() {
        val sourceId = source("订单")
        val goal = BusinessGoal("priority", "不能出站的目标原文", BusinessGoalType.CUSTOM, "不能出站的期望结果", BusinessGoalPriority.HIGH)
        var view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), listOf(goal), listOf(metric(sourceId)))
            .copy(enterpriseName = "不能出站的企业名称", businessDescription = "不能出站的业务简介"))
        view = service.provisionTeam(editor, key(), VersionCommand(view.stateVersion))
        var captured: AssistantReasoningContext? = null
        val port = object : AssistantReasoningPort {
            override fun respond(context: AssistantReasoningContext): AssistantReasoningResult {
                captured = context
                return AssistantReasoningResult("仅解释提供的检查摘要", context.findings.map { it.id }, "TEST_MODEL")
            }
        }
        service = createService(port)
        view = service.message(editor, "assistant-analysis", key(), AssistantMessageCommand(view.stateVersion, "解释当前问题"))
        val serialized = JsonMapper.builder().build().writeValueAsString(captured!!)
        listOf("不能出站", "净收入", "支付完成金额", "财务负责人").forEach { assertFalse(serialized.contains(it), it) }
        assertTrue(view.tasks.filter { it.inCurrentScope }.all { "priority" in it.goalIds })
        assertFalse(view.businessBrief!!.modelContextExportEnabled)
    }

    @Test
    fun `demo exit removes current demo references but preserves authored business history`() {
        var view = service.bootstrapDemo(editor, key(), VersionCommand(0))
        val demoSource = view.sources.first().id
        val goal = BusinessGoal("demo-goal", "用户自己填写的目标", BusinessGoalType.CUSTOM, "先演练核对方法", BusinessGoalPriority.HIGH, listOf(demoSource))
        view = service.saveBusinessBrief(editor, key(), command(listOf(demoSource), listOf(goal), listOf(metric(demoSource))))
        val brief = view.businessBrief!!
        view = service.confirmMetricDefinition(reviewer, "net-revenue", key(), ConfirmMetricDefinitionCommand(view.stateVersion, brief.version, brief.metrics.single().stateVersion))
        val confirmedVersion = view.businessBrief!!
        val versionBeforeRejectedReset = view.stateVersion
        assertThrows(IllegalArgumentException::class.java) { service.resetDemo(editor, key(), VersionCommand(versionBeforeRejectedReset)) }
        assertEquals(confirmedVersion, service.view(editor).businessBrief, "reset cannot turn a source-specific goal into a future global goal")
        assertEquals(versionBeforeRejectedReset, service.view(editor).stateVersion)
        // The user explicitly chooses global scope before clearing demonstration sources.
        view = service.saveBusinessBrief(editor, key(), command(listOf(demoSource), listOf(goal.copy(sourceIds = emptyList())), listOf(metric(demoSource))))
        view = service.resetDemo(editor, key(), VersionCommand(view.stateVersion))
        assertTrue(view.sources.isEmpty())
        assertEquals(confirmedVersion.enterpriseName, view.businessBrief!!.enterpriseName)
        assertEquals(confirmedVersion.businessDescription, view.businessBrief!!.businessDescription)
        assertEquals(goal.id, view.businessBrief!!.goals.single().id)
        assertTrue(view.businessBrief!!.includedSourceIds.isEmpty())
        assertTrue(view.businessBrief!!.goals.single().sourceIds.isEmpty())
        assertTrue(view.businessBrief!!.metrics.single().sourceIds.isEmpty())
        assertEquals(MetricDefinitionStatus.DRAFT, view.businessBrief!!.metrics.single().status)
        assertEquals(confirmedVersion, service.businessBriefHistory(editor)[1])
        assertEquals(4, service.businessBriefHistory(editor).size)
    }

    @Test
    fun `disconnection makes task scope unavailable and blocks new metric confirmation`() {
        val sourceId = source("订单")
        var view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), metrics = listOf(metric(sourceId))))
        view = service.provisionTeam(editor, key(), VersionCommand(view.stateVersion))
        val originalTask = view.tasks.first()
        view = service.disconnectSource(administrator, sourceId, key(), VersionCommand(view.stateVersion))
        val unavailable = view.tasks.first { it.id == originalTask.id }
        assertFalse(unavailable.inCurrentScope)
        assertTrue(unavailable.scopeReason.contains("授权已撤销"))
        assertEquals(originalTask.evidenceRefs, unavailable.evidenceRefs)
        val brief = view.businessBrief!!
        assertThrows(IllegalArgumentException::class.java) {
            service.confirmMetricDefinition(reviewer, "net-revenue", key(), ConfirmMetricDefinitionCommand(view.stateVersion, brief.version, brief.metrics.single().stateVersion))
        }
    }

    @Test
    fun `removing metric source membership resets approval and new sources do not expand explicit scope`() {
        val sourceId = source("原始来源")
        var view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), metrics = listOf(metric(sourceId))))
        val draft = view.businessBrief!!
        view = service.confirmMetricDefinition(reviewer, "net-revenue", key(), ConfirmMetricDefinitionCommand(view.stateVersion, draft.version, draft.metrics.single().stateVersion))
        val confirmed = view.businessBrief!!.metrics.single()
        view = service.saveBusinessBrief(editor, key(), command(emptyList(), metrics = listOf(metric(sourceId).copy(sourceIds = emptyList()))))
        assertEquals(MetricDefinitionStatus.DRAFT, view.businessBrief!!.metrics.single().status)
        assertEquals(confirmed.stateVersion + 1, view.businessBrief!!.metrics.single().stateVersion)
        assertEquals(confirmed, service.businessBriefHistory(editor)[1].metrics.single())
        val newSourceId = source("后来新增的来源")
        view = service.provisionTeam(editor, key(), VersionCommand(service.view(editor).stateVersion))
        assertTrue(view.businessBrief!!.includedSourceIds.isEmpty())
        assertTrue(view.assistants.all { it.taskIds.isEmpty() && newSourceId !in it.scopeSourceIds })
        assertTrue(view.tasks.none { it.inCurrentScope })
    }

    @Test
    fun `new failing scan updates current finding while preserving original task verification binding`() {
        val sourceId = source("持续存在重复的订单")
        var view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId)))
        view = service.provisionTeam(editor, key(), VersionCommand(view.stateVersion))
        val original = view.tasks.first()
        assertEquals(original.findingId, original.currentFindingId)
        view = service.scan(editor, sourceId, key(), VersionCommand(view.stateVersion))
        val current = view.tasks.first { it.id == original.id }
        assertEquals(original.findingId, current.findingId)
        assertNotEquals(original.findingId, current.currentFindingId)
        assertTrue(view.findings.any { it.id == original.findingId && !it.active })
        assertTrue(view.findings.any { it.id == current.currentFindingId && it.active })
        assertEquals(original.evidenceRefs, current.evidenceRefs)
        assertEquals(original.status, current.status)
        val decision = view.assistants.single { it.role == AssistantRole.DECISION }
        assertTrue(decision.taskIds.contains(current.id), "decision associations use stable task identity after rescanning")
    }

    @Test
    fun `missing configured freshness column belongs to reliability and operations work`() {
        var view = service.createSource(administrator, key(), CreateSourceCommand(0, "缺少更新时间映射", SourceKind.JSON_SNAPSHOT,
            snapshotRows = listOf(mapOf("id" to "A")), observedAt = clock.instant().toString(), primaryKey = "id", freshnessColumn = "updated_at"))
        val sourceId = view.sources.single().id
        view = service.scan(editor, sourceId, key(), VersionCommand(view.stateVersion))
        val goal = BusinessGoal("reliability", "明确同步观察条件", BusinessGoalType.DATA_RELIABILITY,
            "先补齐更新时间字段映射", BusinessGoalPriority.HIGH)
        view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), listOf(goal)))
        view = service.provisionTeam(editor, key(), VersionCommand(view.stateVersion))
        val missing = view.findings.single { it.ruleId == "DQ.FRESHNESS_COLUMN_MISSING.v1" }
        val operations = view.assistants.single { it.role == AssistantRole.OPERATIONS }
        assertTrue(missing.id in operations.findingIds)
        assertTrue(view.tasks.any { it.assistantId == operations.id && it.currentFindingId == missing.id && it.inCurrentScope })
    }

    @Test
    fun `fulfillment and inventory goals include mixed scalar types without claiming business validation`() {
        val sourceId = source("已明确选入的业务来源", listOf(mapOf("id" to "A", "quantity" to 2), mapOf("id" to "B", "quantity" to "3")))
        listOf(BusinessGoalType.ORDER_FULFILLMENT, BusinessGoalType.INVENTORY_ACCURACY).forEach { type ->
            val goal = BusinessGoal("business-goal", "核对已选业务数据的基础条件", type, "先确认字段含义", BusinessGoalPriority.HIGH, listOf(sourceId))
            var view = service.saveBusinessBrief(editor, key(), command(listOf(sourceId), listOf(goal)))
            view = service.provisionTeam(editor, key(), VersionCommand(view.stateVersion))
            val mixed = view.findings.single { it.ruleId == "DQ.MIXED_TYPES.v1" }
            assertTrue(mixed.id in view.assistants.single { it.role == AssistantRole.ANALYSIS }.findingIds)
            assertTrue(view.tasks.any { it.currentFindingId == mixed.id && it.inCurrentScope && it.priorityReason.contains("不代表目标已达成") })
        }
    }
}
