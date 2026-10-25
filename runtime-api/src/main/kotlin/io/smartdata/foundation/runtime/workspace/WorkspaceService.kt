package io.smartdata.foundation.runtime.workspace

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.runtime.api.commandFingerprint
import io.smartdata.foundation.service.AuthorizationDeniedException
import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.IdempotencyConflictException
import io.smartdata.foundation.service.OptimisticStateException
import io.smartdata.foundation.service.ResourceNotFoundException
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import java.util.UUID

class WorkspaceService(
    private val store: WorkspaceStorePort,
    connectors: List<ReadOnlySourcePort>,
    private val clock: Clock,
    private val capabilities: WorkspaceCapabilities,
    private val reasoning: AssistantReasoningPort = DeterministicAssistantReasoningPort(),
) {
    private val connectors = connectors.associateBy { it.kind }
    private val profiler = DeterministicAssetProfiler(clock)

    fun view(actor: Actor): WorkspaceView = toView(store.read(actor.tenantId))

    fun businessBriefHistory(actor: Actor): List<BusinessBrief> {
        val state = store.read(actor.tenantId)
        return (state.businessBriefHistory.ifEmpty { listOfNotNull(state.businessBrief) })
            .map(::safeBriefView)
    }

    fun saveBusinessBrief(actor: Actor, key: String, body: SaveBusinessBriefCommand): WorkspaceView {
        requireEditor(actor)
        val enterprise = businessText(body.enterpriseName, 120, true, "企业名称")
        val description = businessText(body.businessDescription, 2000, false, "业务简介")
        require(body.goals.size <= 12 && body.metrics.size <= 40) { "最多登记 12 项业务目标和 40 项指标口径" }
        validateBusinessIds(body.goals.map { it.id }, "目标")
        validateBusinessIds(body.metrics.map { it.id }, "指标")
        val sources = businessSourceIds(body.includedSourceIds)
        val goals = body.goals.map { goal -> goal.copy(title = businessText(goal.title, 100, true, "目标名称"),
            expectedOutcome = businessText(goal.expectedOutcome, 1000, true, "期望结果"), sourceIds = businessSourceIds(goal.sourceIds)) }
        val metrics = body.metrics.map(::normalizeMetric)
        return mutate(actor, key, body.expectedStateVersion, "business_brief.saved", "business-brief", body) { state ->
            requireBriefVersion(state, body.expectedBriefVersion)
            val known = state.sources.map { it.id }.toSet()
            require(sources.all { it in known }) { "本轮范围只能引用同一工作区已登记的来源" }
            require((goals.flatMap { it.sourceIds } + metrics.flatMap { it.sourceIds }).all { it in sources }) {
                "目标和口径只能关联本轮纳入的来源；调整范围时请一并调整关联，原版本仍保留"
            }
            val previous = state.businessBrief
            val savedMetrics = metrics.map { input ->
                val old = previous?.metrics?.find { it.id == input.id }
                if (old != null && old.input() == input) old.copy(calculationStatus = "NOT_COMPUTED")
                else MetricDefinition(input.id, input.name, input.businessDefinition, input.owner, input.timeZone, input.currency,
                    input.formulaDescription, input.sourceIds, stateVersion = (old?.stateVersion ?: 0) + 1)
            }
            retainBusinessBrief(state, BusinessBrief((previous?.version ?: 0) + 1, enterprise, description, goals, sources,
                savedMetrics, now(), actor.id))
            if (state.assistants.isNotEmpty()) synchronizeTasks(state)
        }
    }

    fun confirmMetricDefinition(actor: Actor, metricId: String, key: String, body: ConfirmMetricDefinitionCommand): WorkspaceView {
        requireReviewer(actor)
        require(body.expectedMetricVersion >= 1) { "口径版本必须为正数，请刷新后重试" }
        return mutate(actor, key, body.expectedStateVersion, "metric_definition.confirmed", metricId, body) { state ->
            requireBriefVersion(state, body.expectedBriefVersion)
            val brief = state.businessBrief ?: throw BusinessValidationException("请先登记业务背景与指标草稿")
            val metric = brief.metrics.find { it.id == metricId } ?: throw ResourceNotFoundException("metric definition", metricId)
            if (metric.stateVersion != body.expectedMetricVersion)
                throw OptimisticStateException("metric-definition", metricId, body.expectedMetricVersion, metric.stateVersion)
            require(metric.status == MetricDefinitionStatus.DRAFT) { "该口径已确认；内容变更后需重新确认" }
            require(metric.businessDefinition.isNotBlank() && metric.owner.isNotBlank() && metric.timeZone.isNotBlank() && metric.formulaDescription.isNotBlank()) {
                "确认前必须明确业务定义、责任人、时区和公式说明；文字说明不会执行或计算指标"
            }
            require(metric.sourceIds.isNotEmpty() && metric.sourceIds.all { sourceId ->
                sourceId in brief.includedSourceIds && state.sources.any { it.id == sourceId && it.status != SourceStatus.DISCONNECTED }
            }) { "确认前须关联至少一个本轮纳入且未断开的来源" }
            val confirmed = metric.copy(status = MetricDefinitionStatus.CONFIRMED, stateVersion = metric.stateVersion + 1,
                confirmedBy = actor.id, confirmedAt = now(), calculationStatus = "NOT_COMPUTED")
            retainBusinessBrief(state, brief.copy(version = brief.version + 1,
                metrics = brief.metrics.map { if (it.id == metricId) confirmed else it }, updatedAt = now(), updatedBy = actor.id,
                modelContextExportEnabled = false))
            if (state.assistants.isNotEmpty()) synchronizeTasks(state)
        }
    }

    fun createSource(actor: Actor, key: String, body: CreateSourceCommand): WorkspaceView {
        requireAdministrator(actor)
        require(body.name.isNotBlank() && body.name.length <= 100) { "来源名称需为 1–100 个字符" }
        require(body.freshnessMaxHours in 1..8760) { "新鲜度阈值需为 1–8760 小时" }
        require(body.primaryKey == null || body.primaryKey.length in 1..128)
        require(body.freshnessColumn == null || body.freshnessColumn.length in 1..128)
        body.observedAt?.let { require(!Instant.parse(it).isAfter(clock.instant().plusSeconds(300))) { "采集时间不能晚于当前时钟 5 分钟" } }
        val source = SourceRecord(id("source"), body.name.trim(), body.kind, Provenance.USER, body.endpoint,
            body.credentialEnvRef, body.usernameEnvRef, body.snapshotRows, body.observedAt, body.primaryKey,
            body.freshnessColumn, body.freshnessMaxHours, body.schema, tenantId = actor.tenantId)
        connector(source).validate(source)
        return mutate(actor, key, body.expectedStateVersion, "source.created", source.id, body) { state ->
            require(state.sources.none { it.provenance == Provenance.DEMO }) { "请先退出演示工作区，再接入用户来源" }
            require(state.sources.size < 50) { "本地工作区最多 50 个来源" }
            state.sources += source
        }
    }

    fun replaceSnapshot(actor: Actor, sourceId: String, key: String, body: ReplaceSnapshotCommand): WorkspaceView {
        requireAdministrator(actor)
        validateRows(body.snapshotRows)
        val timestamp = Instant.parse(body.observedAt)
        require(!timestamp.isAfter(clock.instant().plusSeconds(300))) { "采集时间不能晚于当前时钟 5 分钟" }
        return mutate(actor, key, body.expectedStateVersion, "source.snapshot_replaced", sourceId, body) { state ->
            val source = findSource(state, sourceId)
            require(source.status != SourceStatus.DISCONNECTED) { "来源已断开；请由管理员重新建立授权来源" }
            require(source.kind == SourceKind.JSON_SNAPSHOT && source.provenance == Provenance.USER) { "只能更新用户上传的 JSON 快照" }
            state.sources.replace(source) { it.copy(snapshotRows = body.snapshotRows, observedAt = body.observedAt,
                status = SourceStatus.CONFIGURED, stateVersion = it.stateVersion + 1, lastError = null) }
        }
    }

    fun disconnectSource(actor: Actor, sourceId: String, key: String, body: VersionCommand): WorkspaceView {
        requireAdministrator(actor)
        return mutate(actor, key, body.expectedStateVersion, "source.disconnected", sourceId, body) { state ->
            val source = findSource(state, sourceId)
            require(source.status != SourceStatus.DISCONNECTED) { "来源已经断开" }
            state.sources.replace(source) { it.copy(status = SourceStatus.DISCONNECTED, credentialEnvRef = null,
                usernameEnvRef = null, stateVersion = it.stateVersion + 1, lastError = null) }
        }
    }

    fun scan(actor: Actor, sourceId: String, key: String, body: VersionCommand): WorkspaceView {
        requireEditor(actor)
        val before = store.read(actor.tenantId)
        val durableFingerprint = fingerprint(actor, "source.scanned", sourceId, body)
        // A restart replay must not cause another remote GET or scan.
        before.commands[digest("${actor.tenantId}\u0000$key")]?.let { previous ->
            if (previous != durableFingerprint) throw IdempotencyConflictException()
            return toView(before)
        }
        if (before.stateVersion != body.expectedStateVersion) throw OptimisticStateException("workspace", actor.tenantId, body.expectedStateVersion, before.stateVersion)
        val source = findSource(before, sourceId)
        require(source.status != SourceStatus.DISCONNECTED) { "来源授权已撤销，不能继续扫描" }
        val startedAt = now()
        val scanId = id("scan")
        var failure: String? = null
        val observations = try {
            connector(source).observe(source).also { require(it.size <= 20) { "单次扫描最多 20 个资产" } }
        } catch (error: Exception) {
            failure = when (error) {
                is BusinessValidationException, is IllegalArgumentException -> error.message?.take(180)
                else -> "无法完成只读观察；请检查连接、授权和 JSON 格式，未使用演示数据替代"
            }
            emptyList()
        }
        val profiles = if (failure == null) observations.map { profiler.profile(source, it, scanId) } else emptyList()
        return mutate(actor, key, body.expectedStateVersion, "source.scanned", sourceId, body) { state ->
            require(state.scans.size < 1000) { "本地工作区扫描记录达到 1000 条上限" }
            if (failure == null) {
                val historical = state.findings.map { if (it.sourceId == sourceId) it.copy(active = false) else it }
                state.findings.clear()
                state.findings.addAll(historical)
                state.assets.removeIf { it.sourceId == sourceId }
                state.assets.addAll(profiles.map { it.asset })
                state.findings.addAll(profiles.flatMap { it.findings })
            }
            val completedAt = now()
            state.scans += ScanView(scanId, sourceId, source.provenance, if (failure == null) "SUCCEEDED" else "FAILED",
                startedAt, completedAt, profiles.map { it.asset.id }, profiles.flatMap { it.findings }.map { it.id }, error = failure)
            state.sources.replace(findSource(state, sourceId)) { it.copy(status = if (failure == null) SourceStatus.SCANNED else SourceStatus.FAILED,
                lastScannedAt = completedAt, lastError = failure, stateVersion = it.stateVersion + 1) }
            if (state.assistants.isNotEmpty() && failure == null) synchronizeTasks(state)
        }
    }

    fun bootstrapDemo(actor: Actor, key: String, body: VersionCommand): WorkspaceView {
        requireEditor(actor)
        return mutate(actor, key, body.expectedStateVersion, "demo.loaded", "demo", body) { state ->
            require(state.sources.isEmpty()) { "演示只能加载到空工作区；不会与用户来源混合" }
            val timestamp = now()
            val sources = listOf(
                SourceRecord("demo-orders", "演示 · 电商订单", SourceKind.JSON_SNAPSHOT, Provenance.DEMO, null, null, null,
                    listOf(
                        mapOf("order_id" to "ORD-1001", "customer_id" to "C-21", "amount" to 299, "status" to "paid", "updated_at" to timestamp),
                        mapOf("order_id" to "ORD-1001", "customer_id" to "C-21", "amount" to 299, "status" to "paid", "updated_at" to timestamp),
                        mapOf("order_id" to "ORD-1002", "customer_id" to null, "amount" to "199", "status" to "pending", "updated_at" to timestamp),
                    ), timestamp, "order_id", "updated_at", 24, "public"),
                SourceRecord("demo-inventory", "演示 · 库存台账", SourceKind.JSON_SNAPSHOT, Provenance.DEMO, null, null, null,
                    listOf(mapOf("sku" to "SKU-01", "quantity" to 12, "warehouse" to "华东", "updated_at" to clock.instant().minusSeconds(259200).toString()),
                        mapOf("sku" to "SKU-02", "quantity" to null, "warehouse" to "华东", "updated_at" to clock.instant().minusSeconds(259200).toString())),
                    timestamp, "sku", "updated_at", 24, "public"),
                SourceRecord("demo-shipments", "演示 · 发货记录", SourceKind.JSON_SNAPSHOT, Provenance.DEMO, null, null, null,
                    listOf(mapOf("shipment_id" to "S-1001", "order_id" to "ORD-1001", "carrier" to "示例物流", "updated_at" to timestamp)),
                    timestamp, "shipment_id", "updated_at", 24, "public"),
            )
            state.sources.addAll(sources)
            sources.forEach { source ->
                val scanId = id("scan")
                val profiles = connector(source).observe(source).map { profiler.profile(source, it, scanId) }
                state.assets.addAll(profiles.map { it.asset })
                state.findings.addAll(profiles.flatMap { it.findings })
                state.scans += ScanView(scanId, source.id, Provenance.DEMO, "SUCCEEDED", timestamp, timestamp,
                    profiles.map { it.asset.id }, profiles.flatMap { it.findings }.map { it.id })
                state.sources.replace(source) { it.copy(status = SourceStatus.SCANNED, lastScannedAt = timestamp, stateVersion = 2) }
            }
            provision(state)
        }
    }

    fun resetDemo(actor: Actor, key: String, body: VersionCommand): WorkspaceView {
        requireEditor(actor)
        return mutate(actor, key, body.expectedStateVersion, "demo.cleared", "demo", body) { state ->
            require(state.sources.isNotEmpty() && state.sources.all { it.provenance == Provenance.DEMO }) { "仅可清空纯演示工作区" }
            val demoIds = state.sources.map { it.id }.toSet()
            state.businessBrief?.let { brief ->
                require(brief.goals.none { goal -> goal.sourceIds.any { it in demoIds } }) {
                    "仍有目标只关注演示来源；请先明确改为本轮全部范围、重选来源或移除该目标，再退出演示，避免目标悄悄扩大到未来来源"
                }
                if ((brief.includedSourceIds + brief.goals.flatMap { it.sourceIds } + brief.metrics.flatMap { it.sourceIds }).any { it in demoIds }) {
                    retainBusinessBrief(state, brief.copy(version = brief.version + 1,
                        includedSourceIds = brief.includedSourceIds.filterNot { it in demoIds },
                        goals = brief.goals,
                        metrics = brief.metrics.map { metric -> if (metric.sourceIds.none { it in demoIds }) metric else metric.copy(
                            sourceIds = metric.sourceIds.filterNot { it in demoIds }, status = MetricDefinitionStatus.DRAFT,
                            stateVersion = metric.stateVersion + 1, confirmedBy = null, confirmedAt = null, calculationStatus = "NOT_COMPUTED") },
                        updatedAt = now(), updatedBy = actor.id, modelContextExportEnabled = false))
                }
            }
            state.sources.clear(); state.assets.clear(); state.scans.clear(); state.findings.clear()
            state.assistants.clear(); state.tasks.clear(); state.comments.clear(); state.messages.clear()
        }
    }

    fun provisionTeam(actor: Actor, key: String, body: VersionCommand): WorkspaceView {
        requireEditor(actor)
        return mutate(actor, key, body.expectedStateVersion, "team.provisioned", "team", body) { state -> provision(state) }
    }

    fun transitionTask(actor: Actor, taskId: String, key: String, body: TaskTransitionCommand): WorkspaceView {
        requireEditor(actor)
        require(body.note.isNotBlank() && body.note.length <= 2000) { "请填写 1–2000 字的处置或复核说明" }
        return mutate(actor, key, body.expectedStateVersion, "task.${body.status.name.lowercase()}", taskId, body) { state ->
            val task = state.tasks.find { it.id == taskId } ?: throw ResourceNotFoundException("task", taskId)
            validateRefs(state, body.evidenceRefs)
            val updated = when (body.status) {
                TaskStatus.ACCEPTED -> {
                    require(task.status in setOf(TaskStatus.PROPOSED, TaskStatus.REOPENED)) { "只有待领取或重新打开的任务可领取" }
                    task.copy(status = TaskStatus.ACCEPTED, ownerId = actor.id)
                }
                TaskStatus.COMPLETED -> {
                    require(task.status == TaskStatus.ACCEPTED && task.ownerId == actor.id) { "只有任务领取人可以提交完成" }
                    require(body.evidenceRefs.isNotEmpty()) { "提交完成需要引用盘点或问题证据" }
                    task.copy(status = TaskStatus.COMPLETED, completionNote = body.note, evidenceRefs = body.evidenceRefs)
                }
                TaskStatus.VERIFIED -> {
                    requireReviewer(actor)
                    require(task.status == TaskStatus.COMPLETED && task.ownerId != actor.id) { "必须使用不同于任务完成身份的本地复核身份；演示身份不代表真实双人审批" }
                    verifyTask(state, task, body.evidenceRefs)
                    task.copy(status = TaskStatus.VERIFIED, verifiedBy = actor.id, evidenceRefs = (task.evidenceRefs + body.evidenceRefs).distinct())
                }
                TaskStatus.REOPENED -> {
                    requireReviewer(actor)
                    require(task.status in setOf(TaskStatus.COMPLETED, TaskStatus.VERIFIED)) { "当前任务不可重新打开" }
                    task.copy(status = TaskStatus.REOPENED, ownerId = null, verifiedBy = null)
                }
                else -> throw BusinessValidationException("不支持此任务状态变更")
            }
            state.tasks.replace(task) { updated.copy(updatedAt = now(), stateVersion = task.stateVersion + 1) }
            state.comments += TaskComment(id("comment"), taskId, actor.id, body.note, body.evidenceRefs, now())
        }
    }

    fun addComment(actor: Actor, taskId: String, key: String, body: TaskCommentCommand): WorkspaceView {
        requireEditor(actor)
        require(body.body.isNotBlank() && body.body.length <= 2000) { "评论需为 1–2000 个字符" }
        return mutate(actor, key, body.expectedStateVersion, "task.comment_added", taskId, body) { state ->
            require(state.tasks.any { it.id == taskId }) { "评论的任务不存在" }
            validateRefs(state, body.evidenceRefs)
            state.comments += TaskComment(id("comment"), taskId, actor.id, body.body, body.evidenceRefs, now())
        }
    }

    fun message(actor: Actor, assistantId: String, key: String, body: AssistantMessageCommand): WorkspaceView {
        requireEditor(actor)
        require(body.question.isNotBlank() && body.question.length <= 2000) { "问题需为 1–2000 个字符" }
        val before = store.read(actor.tenantId)
        before.commands[digest("${actor.tenantId}\u0000$key")]?.let { previous ->
            if (previous != fingerprint(actor, "assistant.message_answered", assistantId, body)) throw IdempotencyConflictException()
            return toView(before)
        }
        if (before.stateVersion != body.expectedStateVersion) throw OptimisticStateException("workspace", actor.tenantId, body.expectedStateVersion, before.stateVersion)
        val planner = BusinessBriefPlanner(before)
        val assistant = planner.assistant(before.assistants.find { it.id == assistantId } ?: throw ResourceNotFoundException("assistant", assistantId))
        val task = body.taskId?.let { taskId -> before.tasks.find { it.id == taskId && it.id in assistant.taskIds }
            ?: throw ResourceNotFoundException("assistant task", taskId) }
        val selected = planner.forRole(assistant.role).map { it.finding }
            .filter { task == null || BusinessBriefPlanner.taskId(it) == task.id }.take(5)
        // A future network/model call runs outside the store lock. Commit CAS
        // rejects the result if the workspace evidence changed meanwhile.
        val result = try {
            reasoning.respond(AssistantReasoningContext(actor.tenantId, assistantId, assistant.role,
                assistant.responsibility, body.question, before.stateVersion, selected))
        } catch (failure: AssistantReasoningException) {
            return mutate(actor, key, body.expectedStateVersion, "assistant.message_answered", assistantId, body) { state ->
                state.messages += AssistantMessage(id("message"), assistantId, actor.id,
                    if (failure.redactQuestion) "提问命中服务端敏感信息保护，正文未保存。" else body.question,
                    failure.userMessage, emptyList(), now(), reasoning.method, "FAILED",
                    listOf("本次模型未提供通过校验的结论；现有问题和任务状态保持待核对。"), failure.code, failure.trace)
            }
        }
        require(result.answer.isNotBlank() && result.answer.length <= 16000 &&
            result.method.matches(Regex("[A-Z][A-Z0-9_.:-]{0,79}"))) { "助手答复超出受控输出范围" }
        require(result.evidenceRefs.size <= 20 && result.evidenceRefs.all { reference -> selected.any { it.id == reference } }) {
            "助手引用了不属于本次授权上下文的证据，拒绝保存"
        }
        require(selected.isEmpty() || result.evidenceRefs.isNotEmpty()) { "有证据的助手答复必须保留来源引用" }
        require(result.uncertainty.size <= 8 && result.uncertainty.all { it.isNotBlank() && it.length <= 1000 }) { "助手不确定性说明超出范围" }
        if (result.method == "SPRING_AI_EVIDENCE_V1") require(result.trace != null && result.evidenceRefs.isNotEmpty()) { "模型答复必须保留模型版本和证据" }
        return mutate(actor, key, body.expectedStateVersion, "assistant.message_answered", assistantId, body) { state ->
            state.messages += AssistantMessage(id("message"), assistantId, actor.id, body.question, result.answer,
                result.evidenceRefs.distinct(), now(), result.method, uncertainty = result.uncertainty, trace = result.trace)
        }
    }

    fun report(actor: Actor): WorkspaceReport {
        val state = store.read(actor.tenantId)
        val findings = state.findings.filter { it.active }
        val failures = state.sources.count { it.status == SourceStatus.FAILED }
        val brief = state.businessBrief
        val businessSections = if (brief == null) listOf(ReportSection("业务约定", "尚未登记企业目标与指标口径，当前工作仍按通用检查规则安排。"))
            else listOf(
                ReportSection("本轮业务目标", "${brief.enterpriseName}，业务约定版本 ${brief.version}。" + if (brief.goals.isEmpty()) "尚未登记业务目标，不推断企业目标。"
                    else brief.goals.joinToString("\n", prefix = "\n") {
                        val priority = when (it.priority) { BusinessGoalPriority.HIGH -> "高"; BusinessGoalPriority.MEDIUM -> "中"; BusinessGoalPriority.LOW -> "低" }
                        "${it.title}（${priority}优先级）：${it.expectedOutcome}。这是期望结果，尚未测量是否达成。"
                    }),
                ReportSection("本轮纳入范围", if (brief.includedSourceIds.isEmpty()) "本轮未纳入任何来源；历史任务和证据仍保留，移出范围不等于修复。"
                    else brief.includedSourceIds.joinToString("、") { sourceId -> state.sources.find { it.id == sourceId }?.name ?: sourceId }),
                ReportSection("指标口径登记", if (brief.metrics.isEmpty()) "尚未登记指标口径；没有计算业务指标。" else brief.metrics.joinToString("\n") {
                    "${it.name}：${if (it.status == MetricDefinitionStatus.CONFIRMED) "口径已确认" else "草稿待确认"}；负责人${it.owner.ifBlank { "未填" }}；时区${it.timeZone.ifBlank { "未填" }}；币种${it.currency ?: "未登记或不适用"}；关联${it.sourceIds.size}个来源。定义：${it.businessDefinition.ifBlank { "未填" }}。公式说明（未执行）：${it.formulaDescription.ifBlank { "未填" }}。" +
                        (it.confirmedBy?.let { reviewer -> "确认身份$reviewer，时间${it.confirmedAt}。" } ?: "") + "此处仅登记口径，尚未计算或验证指标。"
                }),
            )
        return WorkspaceReport(now(), state.stateVersion, "DETERMINISTIC_EVIDENCE_REPORT_V1", "智能数据基座 · 可核对的工作区报告", businessSections + listOf(
            ReportSection("观察范围", "已登记 ${state.sources.size} 个来源，观察 ${state.assets.size} 个资产、${state.assets.sumOf { it.rowCount }} 行。${state.sources.count { it.provenance == Provenance.DEMO }} 个为演示来源；$failures 个来源最近扫描失败。所有判断只覆盖采样范围。"),
            ReportSection("发现的问题", if (findings.isEmpty()) "没有有效问题记录不等于系统健康；需结合接入覆盖面和样本边界判断。" else findings.joinToString("\n") { "${it.title}：${it.observed}。依据 ${it.ruleId}，证据 ${it.evidence.sha256.take(16)}。" }),
            ReportSection("协作进度", "已建立 ${state.assistants.size} 位职责助手，${state.tasks.size} 项任务，其中 ${state.tasks.count { it.status == TaskStatus.VERIFIED }} 项经不同本地演示身份结合新扫描证据复核。演示身份可由同一操作者切换，不构成真实双人审批证明。"),
            ReportSection("能力边界", "当前为本地文件持久化；助手方法为 ${reasoning.method}。规则模式未调用模型；模型模式只解释获准的问题摘要，失败会单独记录，模型自述不能验证任务成功。本端仅发出只读请求，没有发出来源写入命令；这不能单独证明外部系统未发生变化。未测量真实收益、全库质量、业务因果或企业部署容量。"),
        ), findings.map { it.id })
    }

    private fun verifyTask(state: PersistedWorkspace, task: TaskView, references: List<String>) {
        val original = state.findings.find { it.id == task.findingId } ?: throw BusinessValidationException("原问题证据缺失，无法复核")
        val source = findSource(state, original.sourceId)
        require(source.status == SourceStatus.SCANNED) { "来源未有成功的最新盘点，请先重新盘点" }
        val latest = state.scans.lastOrNull { it.sourceId == original.sourceId }
            ?: throw BusinessValidationException("缺少新的盘点记录")
        require(latest.status == "SUCCEEDED" && latest.id != original.scanId && latest.id in references) { "复核必须引用该来源新的成功盘点；原问题证据不能自证修复" }
        require(Instant.parse(latest.completedAt).isAfter(Instant.parse(original.evidence.observedAt))) { "新观察时间必须晚于原问题证据" }
        val asset = state.assets.find { it.id == original.assetId }
            ?: throw BusinessValidationException("目标资产已不在最新观察范围，不能认定修复")
        require(!asset.sampled && asset.totalRows != null && asset.totalRows == asset.rowCount.toLong()) {
            "本次只取得不完整或不稳定的采样，无法判断原问题是否已修复；需要完整快照或规则专用的完整验证"
        }
        require(asset.sampling == original.evidence.sampling && original.evidence.totalRows != null &&
            original.evidence.totalRows == original.evidence.sampledRows.toLong()) {
            "原问题与新观察的采样范围不能证明一致覆盖，复核结果只能是无法判断"
        }
        require(asset.rowCount > 0 && asset.evidenceSha256 != original.evidence.sha256 && Instant.parse(asset.observedAt).isAfter(Instant.parse(original.evidence.observedAt))) { "需要采集时间更新、内容哈希不同且非空的新来源证据" }
        require(asset.columns.isNotEmpty() && (original.column == null || asset.columns.any { it.name == original.column && it.inferredType != "EMPTY" })) {
            "原问题对应字段已消失或全部为空，观察不足以确认修复"
        }
        require(state.findings.none { it.active && it.sourceId == original.sourceId && it.assetId == original.assetId && it.ruleId == original.ruleId && it.column == original.column }) {
            "最新独立盘点仍触发原规则，不能确认问题已解决"
        }
    }

    private fun provision(state: PersistedWorkspace) {
        require(state.scans.any { it.status == "SUCCEEDED" }) { "请先完成至少一次来源盘点，再组建有证据的服务团队" }
        if (state.assistants.isEmpty()) state.assistants.addAll(listOf(
            AssistantView("assistant-operations", AssistantRole.OPERATIONS, "数据运维助手", "检查接入、数据更新和同步状态，并组织故障排查。"),
            AssistantView("assistant-analysis", AssistantRole.ANALYSIS, "数据分析助手", "检查样本中的缺值、重复和类型问题，协助统一指标口径。"),
            AssistantView("assistant-decision", AssistantRole.DECISION, "项目决策助手", "汇总有证据的高优先级问题和处理进度；不据样本编造商业收益。"),
        ))
        synchronizeTasks(state)
    }

    private fun synchronizeTasks(state: PersistedWorkspace) {
        val planner = BusinessBriefPlanner(state)
        planner.findings.forEach { planned ->
            val finding = planned.finding
            val role = if (BusinessBriefPlanner.operationsRule(finding)) AssistantRole.OPERATIONS else AssistantRole.ANALYSIS
            val assistant = state.assistants.find { it.role == role } ?: return@forEach
            val taskId = BusinessBriefPlanner.taskId(finding)
            val previous = state.tasks.find { it.id == taskId }
            if (previous == null) state.tasks += TaskView(taskId, assistant.id, finding.id, finding.title, finding.suggestion,
                createdAt = now(), updatedAt = now(), evidenceRefs = listOf(finding.id), createdUnderBriefVersion = state.businessBrief?.version)
        }
        // A new independent failing observation can reopen a previous result, even outside current business scope.
        state.findings.filter { it.active && state.sources.any { source -> source.id == it.sourceId && source.status == SourceStatus.SCANNED } }.forEach { finding ->
            val previous = state.tasks.find { it.id == BusinessBriefPlanner.taskId(finding) }
            if (previous != null && previous.status == TaskStatus.VERIFIED && previous.findingId != finding.id) {
                state.tasks.replace(previous) { it.copy(status = TaskStatus.REOPENED, findingId = finding.id, ownerId = null, verifiedBy = null,
                    updatedAt = now(), stateVersion = it.stateVersion + 1, evidenceRefs = (it.evidenceRefs + finding.id).distinct()) }
            }
        }
        val revisedTasks = state.tasks.map(planner::task)
        state.tasks.clear(); state.tasks.addAll(revisedTasks)
        val revised = state.assistants.map(planner::assistant)
        state.assistants.clear(); state.assistants.addAll(revised)
    }

    private fun businessText(value: String, maximum: Int, required: Boolean, label: String): String = value.trim().also {
        require(it.length <= maximum && (!required || it.isNotBlank()) && it.none { char -> char == '\u0000' }) {
            if (required) "${label}需填写1–${maximum}个字符，不能包含空字符" else "${label}不能超过${maximum}个字符或包含空字符"
        }
    }

    private fun validateBusinessIds(ids: List<String>, label: String) {
        require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) }) { "${label}ID必须唯一，使用1–64个字母数字或._-，首字符须为字母数字" }
    }

    private fun businessSourceIds(ids: List<String>): List<String> {
        require(ids.size <= 50 && ids.distinct().size == ids.size && ids.all { it.isNotBlank() && it.length <= 128 }) { "最多关联50个唯一来源ID" }
        return ids.sorted()
    }

    private fun normalizeMetric(input: MetricDefinitionInput): MetricDefinitionInput {
        val timeZone = businessText(input.timeZone, 80, false, "时区")
        if (timeZone.isNotBlank()) require(runCatching { ZoneId.of(timeZone) }.isSuccess) { "时区须为明确有效的时区，例如Asia/Shanghai或UTC" }
        val currency = input.currency?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
        require(currency == null || currency.matches(Regex("[A-Z]{3}")) && runCatching { Currency.getInstance(currency) }.isSuccess) { "币种请使用有效三字母代码，例如CNY（人民币）；不涉及币种时可留空" }
        return input.copy(name = businessText(input.name, 100, true, "指标名称"), businessDefinition = businessText(input.businessDefinition, 2000, false, "业务定义"),
            owner = businessText(input.owner, 120, false, "责任人"), timeZone = timeZone, currency = currency,
            formulaDescription = businessText(input.formulaDescription, 2000, false, "公式说明"), sourceIds = businessSourceIds(input.sourceIds))
    }

    private fun requireBriefVersion(state: PersistedWorkspace, expected: Long) {
        require(expected >= 0) { "expectedBriefVersion不能为负数" }
        val actual = state.businessBrief?.version ?: 0
        if (actual != expected) throw OptimisticStateException("business-brief", state.tenantId, expected, actual)
    }

    private fun retainBusinessBrief(state: PersistedWorkspace, brief: BusinessBrief) {
        require(state.businessBriefHistory.size < 100) { "业务约定已达100个历史版本，请先迁移可归档存储；不会覆盖旧版本" }
        if (state.businessBriefHistory.isEmpty()) state.businessBrief?.let { state.businessBriefHistory += it }
        state.businessBrief = brief
        state.businessBriefHistory += brief
    }

    private fun safeBriefView(brief: BusinessBrief) = brief.copy(modelContextExportEnabled = false,
        metrics = brief.metrics.map { it.copy(calculationStatus = "NOT_COMPUTED") })

    private fun validateRefs(state: PersistedWorkspace, references: List<String>) {
        require(references.size <= 20) { "最多引用 20 条证据" }
        val known = state.findings.map { it.id }.toSet() + state.scans.map { it.id } + state.assets.map { it.id }
        require(references.all { it in known }) { "引用必须来自同一工作区中已记录的问题、资产或盘点" }
    }

    private fun mutate(actor: Actor, key: String, expected: Long, event: String, resourceId: String, body: Any, operation: (PersistedWorkspace) -> Unit): WorkspaceView =
        toView(store.mutate(actor.tenantId, expected, key, fingerprint(actor, event, resourceId, body)) { state ->
            require(state.comments.size < 10000 && state.messages.size < 10000) { "本地协作记录已达上限，请迁移持久化适配器" }
            operation(state)
            state.audit += WorkspaceAudit(id("audit"), actor.id, event, resourceId, now(), state.stateVersion + 1)
        })

    private fun fingerprint(actor: Actor, event: String, resourceId: String, body: Any) = commandFingerprint(
        actor.id, actor.roles.map { it.name }.sorted(), event, if (event == "source.created") "new-source" else resourceId, body,
    )

    private fun toView(state: PersistedWorkspace): WorkspaceView {
        val planner = BusinessBriefPlanner(state)
        return WorkspaceView(state.stateVersion, sources = state.sources.map { source ->
        SourceView(source.id, source.name, source.kind, source.provenance, source.status,
            source.endpoint?.let { endpoint -> try { val uri = URI.create(endpoint.removePrefix("jdbc:")); "${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}${uri.path}" } catch (_: Exception) { "已配置端点" } },
            source.lastScannedAt, state.assets.count { it.sourceId == source.id }, source.credentialEnvRef != null, source.stateVersion, source.lastError)
    }, assets = state.assets.toList(), scans = state.scans.toList(), findings = state.findings.toList(), assistants = state.assistants.map { planner.assistant(it).copy(method = reasoning.method) },
        tasks = planner.tasks(), comments = state.comments.toList(), messages = state.messages.toList(), audit = state.audit.toList(), capabilities = capabilities.copy(assistantMethod = reasoning.method),
        businessBrief = state.businessBrief?.let(::safeBriefView))
    }

    private fun connector(source: SourceRecord) = connectors[source.kind] ?: throw BusinessValidationException("此部署未启用该只读连接器")
    private fun findSource(state: PersistedWorkspace, sourceId: String) = state.sources.find { it.id == sourceId } ?: throw ResourceNotFoundException("workspace source", sourceId)
    private fun now() = clock.instant().toString()
    private fun id(prefix: String) = "$prefix-${UUID.randomUUID()}"
    private fun requireEditor(actor: Actor) {
        if (actor.roles.none { it in setOf(BusinessRole.ADMIN, BusinessRole.ORDER_SPECIALIST, BusinessRole.WAREHOUSE_APPROVER, BusinessRole.SALES_OWNER) }) throw AuthorizationDeniedException("审计身份为只读，不能修改工作区")
    }
    private fun requireAdministrator(actor: Actor) {
        if (BusinessRole.ADMIN !in actor.roles) throw AuthorizationDeniedException("数据来源配置、原始快照替换和授权撤销仅限平台管理员")
    }
    private fun requireReviewer(actor: Actor) {
        if (actor.roles.none { it == BusinessRole.ADMIN || it == BusinessRole.SALES_OWNER }) throw AuthorizationDeniedException("工作区复核需要平台管理员或业务负责人角色")
    }
}

data class ReplaceSnapshotCommand(val expectedStateVersion: Long,
    @param:com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.FAIL, contentNulls = com.fasterxml.jackson.annotation.Nulls.SET)
    val snapshotRows: List<Map<String, Any?>>, val observedAt: String)

private fun <T> MutableList<T>.replace(previous: T, transform: (T) -> T) { this[indexOf(previous)] = transform(previous) }
