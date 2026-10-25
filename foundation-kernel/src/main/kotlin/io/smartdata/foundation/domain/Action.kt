package io.smartdata.foundation.domain

import java.time.Duration
import java.time.Instant

enum class ActionRisk {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

data class ActionDefinition(
    val id: String,
    val version: String,
    val titleZh: String,
    val inputFields: Set<String>,
    val risk: ActionRisk,
    val requestRoles: Set<BusinessRole>,
    val approvalRoles: Set<BusinessRole>,
    val approvalRequired: Boolean = true,
    val timeout: Duration,
    val approvalTtl: Duration = Duration.ofHours(24),
    val compensationActionId: String? = null,
    val verifierId: String,
    val compensationVerifierId: String? = null,
    val resolvableGapCodes: Set<String> = emptySet(),
) {
    init {
        require(timeout > Duration.ZERO) { "action timeout must be positive" }
        require(approvalTtl > Duration.ZERO) { "approval TTL must be positive" }
        require(approvalRequired) { "v1 write actions must require human approval" }
        require(compensationActionId == null || !compensationVerifierId.isNullOrBlank()) {
            "every compensation action must have an independent verifier"
        }
    }
}

enum class ActionState {
    PROPOSED,
    POLICY_PASSED,
    AWAITING_APPROVAL,
    APPROVED,
    EXECUTING,
    EXECUTED_UNVERIFIED,
    VERIFIED_SUCCEEDED,
    VERIFIED_FAILED,
    REJECTED,
    EXPIRED,
    CANCELLED,
    UNKNOWN_REQUIRES_RECONCILIATION,
    COMPENSATING,
    COMPENSATED,
    MANUAL_INTERVENTION,
}

data class ActionRequest(
    val id: String,
    val tenantId: String,
    val caseId: String,
    val recommendationId: String,
    val definitionId: String,
    val definitionVersion: String,
    val input: Map<String, Any>,
    val evidenceIds: Set<String>,
    val requestedBy: String,
    val approvedBy: String? = null,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val expectedCaseStateVersion: Long,
    val boundCaseStateVersion: Long,
    val verificationBoundCaseStateVersion: Long? = null,
    val domainVersion: String,
    val policyVersion: String,
    val state: ActionState,
    val stateVersion: Long,
    val createdAt: Instant,
    val approvalExpiresAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "idempotencyKey is required" }
        require(stateVersion >= 0) { "stateVersion cannot be negative" }
        require(approvalExpiresAt.isAfter(createdAt)) { "approval expiry must follow creation" }
    }
}

enum class ReceiptOutcome {
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    COMPENSATED,
}

data class ActionAttempt(
    val number: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val outcome: ReceiptOutcome,
    val messageZh: String,
)

data class ActionReceipt(
    val id: String,
    val tenantId: String,
    val actionRequestId: String,
    val externalRequestId: String,
    val externalReceiptId: String?,
    val outcome: ReceiptOutcome,
    val attempts: List<ActionAttempt>,
    val compensationReceiptId: String? = null,
    val compensationVerificationId: String? = null,
    val verificationId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class InvalidActionTransition(from: ActionState, to: ActionState) :
    IllegalStateException("Invalid action transition: $from -> $to")

object ActionStateMachine {
    private val allowed = mapOf(
        ActionState.PROPOSED to setOf(ActionState.POLICY_PASSED, ActionState.REJECTED),
        ActionState.POLICY_PASSED to setOf(ActionState.AWAITING_APPROVAL, ActionState.APPROVED, ActionState.REJECTED),
        ActionState.AWAITING_APPROVAL to setOf(ActionState.APPROVED, ActionState.REJECTED, ActionState.EXPIRED, ActionState.CANCELLED),
        ActionState.APPROVED to setOf(ActionState.EXECUTING, ActionState.CANCELLED, ActionState.EXPIRED),
        ActionState.EXECUTING to setOf(
            ActionState.EXECUTED_UNVERIFIED,
            ActionState.UNKNOWN_REQUIRES_RECONCILIATION,
            ActionState.MANUAL_INTERVENTION,
        ),
        ActionState.EXECUTED_UNVERIFIED to setOf(
            ActionState.VERIFIED_SUCCEEDED,
            ActionState.VERIFIED_FAILED,
            ActionState.UNKNOWN_REQUIRES_RECONCILIATION,
        ),
        ActionState.UNKNOWN_REQUIRES_RECONCILIATION to setOf(
            ActionState.VERIFIED_SUCCEEDED,
            ActionState.VERIFIED_FAILED,
            ActionState.MANUAL_INTERVENTION,
        ),
        ActionState.VERIFIED_FAILED to setOf(ActionState.COMPENSATING, ActionState.MANUAL_INTERVENTION),
        ActionState.COMPENSATING to setOf(ActionState.COMPENSATED, ActionState.MANUAL_INTERVENTION),
    )

    fun transition(request: ActionRequest, to: ActionState, now: Instant): ActionRequest {
        if (to !in allowed[request.state].orEmpty()) throw InvalidActionTransition(request.state, to)
        return request.copy(state = to, stateVersion = request.stateVersion + 1, updatedAt = now)
    }
}
