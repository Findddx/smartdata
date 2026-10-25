package io.smartdata.foundation.workflow

import io.smartdata.foundation.domain.StableIds
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

interface WorkflowKernelFacade {
    fun collectEvidence(input: FulfillmentWorkflowInput): SmallEvidenceSummary
    fun recordApproval(input: FulfillmentWorkflowInput, approvalId: String)
    fun validateAndRecordApproval(input: FulfillmentWorkflowInput, approvalId: String): Boolean
    fun execute(input: FulfillmentWorkflowInput): DurableExecutionResult
    fun verify(input: FulfillmentWorkflowInput, receiptId: String): DurableVerificationResult
    fun verifyCompensation(input: FulfillmentWorkflowInput, compensationReceiptId: String): DurableVerificationResult
    fun createMemoryCandidate(input: FulfillmentWorkflowInput, verificationId: String): String
    fun compensate(input: FulfillmentWorkflowInput, receiptId: String): DurableExecutionResult
    fun recordRejection(input: FulfillmentWorkflowInput, approvalId: String, reasonZh: String)
    fun validateAndRecordRejection(input: FulfillmentWorkflowInput, approvalId: String, reasonZh: String): Boolean
    fun recordApprovalExpiry(input: FulfillmentWorkflowInput)
}

/**
 * Safe development facade. Production replaces it with a mTLS runtime client;
 * it never interprets model output or talks directly to a source-system credential.
 */
@Component
@Profile("local")
class DeterministicWorkflowKernelFacade : WorkflowKernelFacade {
    override fun collectEvidence(input: FulfillmentWorkflowInput) = SmallEvidenceSummary(
        evidenceIds = listOf(StableIds.from("workflow-evidence", input.tenantId, input.caseId)),
        contentHash = StableIds.from("workflow-evidence-hash", input.caseId, input.domainVersion),
    )

    override fun recordApproval(input: FulfillmentWorkflowInput, approvalId: String) {
        require(validateAndRecordApproval(input, approvalId)) { "Invalid local approval record" }
    }

    override fun validateAndRecordApproval(input: FulfillmentWorkflowInput, approvalId: String): Boolean =
        approvalId.matches(SAFE_LEDGER_ID)

    override fun execute(input: FulfillmentWorkflowInput) = DurableExecutionResult(
        receiptId = StableIds.from("workflow-receipt", input.tenantId, input.actionRequestId),
        state = DurableExecutionState.EXECUTED_UNVERIFIED,
    )

    override fun verify(input: FulfillmentWorkflowInput, receiptId: String) = DurableVerificationResult(
        verificationId = StableIds.from("workflow-verification", input.tenantId, input.actionRequestId, receiptId),
        outcome = DurableVerificationOutcome.PASSED,
    )

    override fun verifyCompensation(input: FulfillmentWorkflowInput, compensationReceiptId: String) =
        DurableVerificationResult(
            verificationId = StableIds.from(
                "workflow-compensation-verification",
                input.tenantId,
                input.actionRequestId,
                compensationReceiptId,
            ),
            outcome = DurableVerificationOutcome.PASSED,
        )

    override fun createMemoryCandidate(input: FulfillmentWorkflowInput, verificationId: String): String =
        StableIds.from("workflow-memory", input.tenantId, input.caseId, verificationId)

    override fun compensate(input: FulfillmentWorkflowInput, receiptId: String) = DurableExecutionResult(
        receiptId = StableIds.from("workflow-compensation", input.tenantId, input.actionRequestId, receiptId),
        state = DurableExecutionState.COMPENSATED,
    )

    override fun recordRejection(input: FulfillmentWorkflowInput, approvalId: String, reasonZh: String) {
        require(validateAndRecordRejection(input, approvalId, reasonZh)) { "Invalid local rejection record" }
    }

    override fun validateAndRecordRejection(
        input: FulfillmentWorkflowInput,
        approvalId: String,
        reasonZh: String,
    ): Boolean =
        approvalId.matches(SAFE_LEDGER_ID) && reasonZh.isNotBlank() && reasonZh.length <= 2_000
    override fun recordApprovalExpiry(input: FulfillmentWorkflowInput) = Unit

    private companion object {
        val SAFE_LEDGER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

@Component
class DeterministicFulfillmentActivities(
    private val facade: WorkflowKernelFacade,
) : FulfillmentActivities {
    override fun collectEvidence(input: FulfillmentWorkflowInput): SmallEvidenceSummary = facade.collectEvidence(input)
    override fun recordApproval(input: FulfillmentWorkflowInput, approvalId: String) =
        facade.recordApproval(input, approvalId)
    override fun validateAndRecordApproval(input: FulfillmentWorkflowInput, approvalId: String): Boolean =
        facade.validateAndRecordApproval(input, approvalId)
    override fun executeApprovedAction(input: FulfillmentWorkflowInput): DurableExecutionResult = facade.execute(input)
    override fun independentlyVerify(input: FulfillmentWorkflowInput, receiptId: String): DurableVerificationResult =
        facade.verify(input, receiptId)
    override fun independentlyVerifyCompensation(
        input: FulfillmentWorkflowInput,
        compensationReceiptId: String,
    ): DurableVerificationResult = facade.verifyCompensation(input, compensationReceiptId)
    override fun createMemoryCandidate(input: FulfillmentWorkflowInput, verificationId: String): String =
        facade.createMemoryCandidate(input, verificationId)
    override fun compensate(input: FulfillmentWorkflowInput, receiptId: String): DurableExecutionResult =
        facade.compensate(input, receiptId)
    override fun recordRejection(input: FulfillmentWorkflowInput, approvalId: String, reasonZh: String) =
        facade.recordRejection(input, approvalId, reasonZh)
    override fun validateAndRecordRejection(
        input: FulfillmentWorkflowInput,
        approvalId: String,
        reasonZh: String,
    ): Boolean = facade.validateAndRecordRejection(input, approvalId, reasonZh)
    override fun recordApprovalExpiry(input: FulfillmentWorkflowInput) = facade.recordApprovalExpiry(input)
}
