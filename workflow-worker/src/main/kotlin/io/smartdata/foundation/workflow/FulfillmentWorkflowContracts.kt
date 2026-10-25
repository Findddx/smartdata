package io.smartdata.foundation.workflow

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

data class FulfillmentWorkflowInput @JvmOverloads constructor(
    val tenantId: String = "",
    val caseId: String = "",
    val actionRequestId: String = "",
    val actionDefinitionId: String = "",
    val domainVersion: String = "",
    val policyVersion: String = "",
    val expectedCaseStateVersion: Long = 0,
    val idempotencyKey: String = "",
    val approvalExpiresAtEpochMillis: Long = 0,
    val compensationActionId: String = "",
    val workflowContractVersion: Int = 1,
)

enum class WorkflowDecision { APPROVED, REJECTED }

enum class DurableWorkflowState {
    STARTED,
    COLLECTING_EVIDENCE,
    AWAITING_APPROVAL,
    EXPIRED,
    EXECUTING,
    VERIFYING,
    COMPENSATING,
    COMPENSATED,
    ACTION_VERIFIED_AWAITING_OBSERVATION,
    VERIFIED_CLOSED,
    REJECTED,
    UNKNOWN_REQUIRES_RECONCILIATION,
    MANUAL_INTERVENTION,
}

data class SmallEvidenceSummary @JvmOverloads constructor(
    val evidenceIds: List<String> = emptyList(),
    val contentHash: String = "",
)

data class DurableExecutionResult @JvmOverloads constructor(
    val receiptId: String = "",
    val state: DurableExecutionState = DurableExecutionState.UNKNOWN_REQUIRES_RECONCILIATION,
)

enum class DurableExecutionState {
    EXECUTED_UNVERIFIED,
    FAILED,
    UNKNOWN_REQUIRES_RECONCILIATION,
    COMPENSATED,
}

data class DurableVerificationResult @JvmOverloads constructor(
    val verificationId: String = "",
    val outcome: DurableVerificationOutcome = DurableVerificationOutcome.INCONCLUSIVE,
)

enum class DurableVerificationOutcome {
    PASSED,
    FAILED,
    INCONCLUSIVE,
}

data class FulfillmentWorkflowResult @JvmOverloads constructor(
    val caseId: String = "",
    val actionRequestId: String = "",
    val finalState: DurableWorkflowState = DurableWorkflowState.MANUAL_INTERVENTION,
    val receiptId: String? = null,
    val verificationId: String? = null,
    val compensationVerificationId: String? = null,
    val memoryCandidateId: String? = null,
    val workflowContractVersion: Int = 1,
)

@WorkflowInterface
interface OrderFulfillmentWorkflow {
    @WorkflowMethod
    fun run(input: FulfillmentWorkflowInput): FulfillmentWorkflowResult

    @SignalMethod
    fun approve(approvalId: String)

    @SignalMethod
    fun reject(approvalId: String, reasonZh: String)

    @QueryMethod
    fun currentState(): DurableWorkflowState
}

@ActivityInterface
interface FulfillmentActivities {
    @ActivityMethod
    fun collectEvidence(input: FulfillmentWorkflowInput): SmallEvidenceSummary

    @ActivityMethod
    /** Legacy v1 activity kept solely for deterministic replay of pre-v2 histories. */
    fun recordApproval(input: FulfillmentWorkflowInput, approvalId: String)

    /** Returns true only after re-reading and validating the immutable approval ledger entry. */
    fun validateAndRecordApproval(input: FulfillmentWorkflowInput, approvalId: String): Boolean

    /** This Activity is configured for one attempt: an ambiguous timeout must be reconciled, never blindly retried. */
    @ActivityMethod
    fun executeApprovedAction(input: FulfillmentWorkflowInput): DurableExecutionResult

    @ActivityMethod
    fun independentlyVerify(
        input: FulfillmentWorkflowInput,
        receiptId: String,
    ): DurableVerificationResult

    @ActivityMethod
    fun independentlyVerifyCompensation(
        input: FulfillmentWorkflowInput,
        compensationReceiptId: String,
    ): DurableVerificationResult

    @ActivityMethod
    fun createMemoryCandidate(
        input: FulfillmentWorkflowInput,
        verificationId: String,
    ): String

    @ActivityMethod
    fun compensate(input: FulfillmentWorkflowInput, receiptId: String): DurableExecutionResult

    @ActivityMethod
    /** Legacy v1 activity kept solely for deterministic replay of pre-v2 histories. */
    fun recordRejection(input: FulfillmentWorkflowInput, approvalId: String, reasonZh: String)

    /** Returns true only after the immutable rejection entry is bound to this request and versions. */
    fun validateAndRecordRejection(input: FulfillmentWorkflowInput, approvalId: String, reasonZh: String): Boolean

    @ActivityMethod
    fun recordApprovalExpiry(input: FulfillmentWorkflowInput)
}
