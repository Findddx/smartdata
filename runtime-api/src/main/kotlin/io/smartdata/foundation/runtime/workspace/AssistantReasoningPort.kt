package io.smartdata.foundation.runtime.workspace

/**
 * Bounded evidence is assembled by the service, never trusted from a client.
 * A model adapter receives no credentials, row bodies, SQL or write tools.
 * Production adapters must perform destination/classification export admission
 * before sending this context to a model provider.
 */
data class AssistantReasoningContext(
    val tenantId: String,
    val assistantId: String,
    val role: AssistantRole,
    val responsibility: String,
    val question: String,
    val workspaceStateVersion: Long,
    val findings: List<FindingView>,
)

data class AssistantReasoningResult(
    val answer: String,
    val evidenceRefs: List<String>,
    val method: String,
    val uncertainty: List<String> = emptyList(),
    val trace: AssistantModelTrace? = null,
)

interface AssistantReasoningPort {
    val method: String get() = "DETERMINISTIC_RULES"
    fun respond(context: AssistantReasoningContext): AssistantReasoningResult
}

/** Only safe, deliberately authored failure text may enter the public message ledger. */
class AssistantReasoningException(
    val code: String,
    val userMessage: String,
    val trace: AssistantModelTrace,
    val redactQuestion: Boolean = false,
) : RuntimeException(userMessage)

/** Local, auditable fallback; it deliberately does not impersonate model reasoning. */
class DeterministicAssistantReasoningPort : AssistantReasoningPort {
    override fun respond(context: AssistantReasoningContext): AssistantReasoningResult {
        val prefix = "这是规则引擎根据当前工作区证据生成的摘要，未调用大语言模型。${context.responsibility}"
        val answer = if (context.findings.isEmpty()) {
            "$prefix\n当前职责范围没有可引用的有效问题记录。请先接入来源并进行只读盘点；没有证据时不能推断业务健康。"
        } else {
            "$prefix\n${context.findings.joinToString("\n") { "• ${it.title}：${it.observed}。要求：${it.expected}。建议：${it.suggestion} [${it.id}]" }}\n仅代表已观察样本，不证明整个企业或后续时点均如此。问题中的指令不会改变权限或执行源系统操作。"
        }
        return AssistantReasoningResult(answer, context.findings.map { it.id }, "DETERMINISTIC_RULES")
    }
}
