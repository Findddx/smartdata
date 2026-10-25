package io.smartdata.foundation.workflow

import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import java.time.Duration

class OrderFulfillmentWorkflowImpl : OrderFulfillmentWorkflow {
    private val normalActivities = Workflow.newActivityStub(
        FulfillmentActivities::class.java,
        io.temporal.activity.ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build(),
    )
    private val executeOnceActivities = Workflow.newActivityStub(
        FulfillmentActivities::class.java,
        io.temporal.activity.ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
            .build(),
    )
    private var state = DurableWorkflowState.STARTED
    private var implementationVersion = Workflow.DEFAULT_VERSION
    private var legacyDecision: WorkflowDecision? = null
    private var legacyApprovalId: String = ""
    private var legacyRejectionReasonZh: String = ""
    private val decisionCandidates = ArrayDeque<DecisionCandidate>()

    override fun run(input: FulfillmentWorkflowInput): FulfillmentWorkflowResult {
        require(input.workflowContractVersion == 1) { "Unsupported workflow contract version" }
        require(input.tenantId.matches(SAFE_ID)) { "A bounded tenant id is required" }
        require(input.caseId.matches(SAFE_ID)) { "A bounded case id is required" }
        require(input.actionRequestId.matches(SAFE_ID)) { "A bounded action request id is required" }
        require(input.actionDefinitionId in V1_ACTIONS) { "Unsupported action definition" }
        require(input.domainVersion.matches(SAFE_VERSION)) { "Domain version is required" }
        require(input.policyVersion.matches(SAFE_VERSION)) { "Policy version is required" }
        require(input.expectedCaseStateVersion >= 0) { "Expected state version is required" }
        require(input.idempotencyKey.matches(SAFE_ID) && input.idempotencyKey.length >= 8) {
            "A bounded idempotency key is required"
        }
        require(
            (input.actionDefinitionId == REQUEST_STOCK_RECOUNT && input.compensationActionId.isBlank()) ||
                (input.actionDefinitionId == RESERVE_SUBSTITUTE_INVENTORY &&
                    input.compensationActionId == RELEASE_RESERVATION),
        ) { "Compensation action does not match the immutable v1 action definition" }
        val now = Workflow.currentTimeMillis()
        require(input.approvalExpiresAtEpochMillis > now) { "Approval expiry must be in the future" }
        require(input.approvalExpiresAtEpochMillis - now <= MAX_APPROVAL_WAIT.toMillis()) {
            "Approval expiry exceeds the bounded workflow wait"
        }
        implementationVersion = Workflow.getVersion(
            VERSION_CHANGE_ID,
            Workflow.DEFAULT_VERSION,
            CURRENT_IMPLEMENTATION_VERSION,
        )
        state = DurableWorkflowState.COLLECTING_EVIDENCE
        normalActivities.collectEvidence(input)
        state = DurableWorkflowState.AWAITING_APPROVAL
        if (implementationVersion <= LEGACY_IMPLEMENTATION_VERSION) {
            val remainingMillis = input.approvalExpiresAtEpochMillis - Workflow.currentTimeMillis()
            val decided = remainingMillis > 0 && Workflow.await(Duration.ofMillis(remainingMillis)) {
                legacyDecision != null
            }
            if (!decided) {
                return expire(input)
            }
            if (legacyDecision == WorkflowDecision.REJECTED) {
                normalActivities.recordRejection(input, legacyApprovalId, legacyRejectionReasonZh)
                state = DurableWorkflowState.REJECTED
                return FulfillmentWorkflowResult(
                    caseId = input.caseId,
                    actionRequestId = input.actionRequestId,
                    finalState = state,
                    workflowContractVersion = implementationVersion,
                )
            }
            normalActivities.recordApproval(input, legacyApprovalId)
        } else {
            var acceptedDecision: DecisionCandidate? = null
            while (acceptedDecision == null) {
                val remainingMillis = input.approvalExpiresAtEpochMillis - Workflow.currentTimeMillis()
                if (remainingMillis <= 0 || !Workflow.await(Duration.ofMillis(remainingMillis)) {
                        decisionCandidates.isNotEmpty()
                    }
                ) {
                    return expire(input)
                }

                val candidate = decisionCandidates.removeFirst()
                val valid = try {
                    when (candidate.decision) {
                        WorkflowDecision.APPROVED -> normalActivities.validateAndRecordApproval(
                            input,
                            candidate.approvalId,
                        )
                        WorkflowDecision.REJECTED -> normalActivities.validateAndRecordRejection(
                            input,
                            candidate.approvalId,
                            candidate.reasonZh,
                        )
                    }
                } catch (_: ActivityFailure) {
                    state = DurableWorkflowState.MANUAL_INTERVENTION
                    return FulfillmentWorkflowResult(
                        caseId = input.caseId,
                        actionRequestId = input.actionRequestId,
                        finalState = state,
                        workflowContractVersion = implementationVersion,
                    )
                }
                if (valid) acceptedDecision = candidate
            }

            if (acceptedDecision.decision == WorkflowDecision.REJECTED) {
                state = DurableWorkflowState.REJECTED
                return FulfillmentWorkflowResult(
                    caseId = input.caseId,
                    actionRequestId = input.actionRequestId,
                    finalState = state,
                    workflowContractVersion = implementationVersion,
                )
            }
        }
        if (implementationVersion >= 2 && Workflow.currentTimeMillis() >= input.approvalExpiresAtEpochMillis) {
            return expire(input)
        }

        state = DurableWorkflowState.EXECUTING
        val execution = try {
            executeOnceActivities.executeApprovedAction(input)
        } catch (_: ActivityFailure) {
            // The Activity may have failed after the source accepted the write.
            // Never replay it here: use the stable action id as a reconciliation
            // token and let the independent read path establish the outcome.
            DurableExecutionResult(
                receiptId = "UNCONFIRMED-${input.actionRequestId}",
                state = DurableExecutionState.UNKNOWN_REQUIRES_RECONCILIATION,
            )
        }
        if (execution.state == DurableExecutionState.COMPENSATED) {
            state = DurableWorkflowState.COMPENSATED
            return FulfillmentWorkflowResult(
                caseId = input.caseId,
                actionRequestId = input.actionRequestId,
                finalState = state,
                receiptId = execution.receiptId,
                workflowContractVersion = implementationVersion,
            )
        }

        state = if (execution.state == DurableExecutionState.UNKNOWN_REQUIRES_RECONCILIATION) {
            DurableWorkflowState.UNKNOWN_REQUIRES_RECONCILIATION
        } else {
            DurableWorkflowState.VERIFYING
        }
        // UNKNOWN and FAILED are never retried as writes. They are reconciled by
        // the independent read path using the stable external request/receipt id.
        val verification = try {
            normalActivities.independentlyVerify(input, execution.receiptId)
        } catch (_: ActivityFailure) {
            state = DurableWorkflowState.MANUAL_INTERVENTION
            return FulfillmentWorkflowResult(
                caseId = input.caseId,
                actionRequestId = input.actionRequestId,
                finalState = state,
                receiptId = execution.receiptId,
                workflowContractVersion = implementationVersion,
            )
        }
        if (verification.outcome != DurableVerificationOutcome.PASSED) {
            if (verification.outcome == DurableVerificationOutcome.FAILED && input.compensationActionId.isNotBlank()) {
                state = DurableWorkflowState.COMPENSATING
                val compensation = try {
                    executeOnceActivities.compensate(input, execution.receiptId)
                } catch (_: ActivityFailure) {
                    DurableExecutionResult(
                        receiptId = "UNCONFIRMED-COMPENSATION-${input.actionRequestId}",
                        state = DurableExecutionState.UNKNOWN_REQUIRES_RECONCILIATION,
                    )
                }
                if (compensation.state == DurableExecutionState.COMPENSATED) {
                    val compensationVerification = try {
                        normalActivities.independentlyVerifyCompensation(input, compensation.receiptId)
                    } catch (_: ActivityFailure) {
                        DurableVerificationResult(outcome = DurableVerificationOutcome.INCONCLUSIVE)
                    }
                    if (compensationVerification.outcome == DurableVerificationOutcome.PASSED) {
                        state = DurableWorkflowState.COMPENSATED
                        return FulfillmentWorkflowResult(
                            caseId = input.caseId,
                            actionRequestId = input.actionRequestId,
                            finalState = state,
                            receiptId = compensation.receiptId,
                            verificationId = verification.verificationId,
                            compensationVerificationId = compensationVerification.verificationId,
                            workflowContractVersion = implementationVersion,
                        )
                    }
                }
            }
            state = DurableWorkflowState.MANUAL_INTERVENTION
            return FulfillmentWorkflowResult(
                caseId = input.caseId,
                actionRequestId = input.actionRequestId,
                finalState = state,
                receiptId = execution.receiptId,
                verificationId = verification.verificationId,
                workflowContractVersion = implementationVersion,
            )
        }
        if (input.actionDefinitionId == REQUEST_STOCK_RECOUNT) {
            state = DurableWorkflowState.ACTION_VERIFIED_AWAITING_OBSERVATION
            return FulfillmentWorkflowResult(
                caseId = input.caseId,
                actionRequestId = input.actionRequestId,
                finalState = state,
                receiptId = execution.receiptId,
                verificationId = verification.verificationId,
                workflowContractVersion = implementationVersion,
            )
        }
        val memoryCandidateId = normalActivities.createMemoryCandidate(input, verification.verificationId)
        state = DurableWorkflowState.VERIFIED_CLOSED
        return FulfillmentWorkflowResult(
            caseId = input.caseId,
            actionRequestId = input.actionRequestId,
            finalState = state,
            receiptId = execution.receiptId,
            verificationId = verification.verificationId,
            memoryCandidateId = memoryCandidateId,
            workflowContractVersion = implementationVersion,
        )
    }

    override fun approve(approvalId: String) {
        // A signal carries only an identifier. The Activity must re-read and
        // validate the immutable approval record. A safe identifier may be
        // buffered while evidence collection is in progress, avoiding a lost
        // signal without treating the signal itself as authorization.
        if (!approvalId.matches(SAFE_ID)) return
        if (implementationVersion <= LEGACY_IMPLEMENTATION_VERSION) {
            if (state == DurableWorkflowState.AWAITING_APPROVAL && legacyDecision == null) {
                legacyApprovalId = approvalId
                legacyDecision = WorkflowDecision.APPROVED
            }
        } else if (state in DECISION_ACCEPTING_STATES) {
            enqueue(DecisionCandidate(approvalId, WorkflowDecision.APPROVED))
        }
    }

    override fun reject(approvalId: String, reasonZh: String) {
        if (!approvalId.matches(SAFE_ID) || reasonZh.isBlank() || reasonZh.length > 2_000) return
        if (implementationVersion <= LEGACY_IMPLEMENTATION_VERSION) {
            if (state == DurableWorkflowState.AWAITING_APPROVAL && legacyDecision == null) {
                legacyApprovalId = approvalId
                legacyRejectionReasonZh = reasonZh
                legacyDecision = WorkflowDecision.REJECTED
            }
        } else if (state in DECISION_ACCEPTING_STATES) {
            enqueue(DecisionCandidate(approvalId, WorkflowDecision.REJECTED, reasonZh))
        }
    }

    override fun currentState(): DurableWorkflowState = state

    private fun enqueue(candidate: DecisionCandidate) {
        if (decisionCandidates.any { it.approvalId == candidate.approvalId && it.decision == candidate.decision }) return
        if (decisionCandidates.size == MAX_DECISION_CANDIDATES) decisionCandidates.removeFirst()
        decisionCandidates.addLast(candidate)
    }

    private fun expire(input: FulfillmentWorkflowInput): FulfillmentWorkflowResult {
        normalActivities.recordApprovalExpiry(input)
        state = DurableWorkflowState.EXPIRED
        return FulfillmentWorkflowResult(
            caseId = input.caseId,
            actionRequestId = input.actionRequestId,
            finalState = state,
            workflowContractVersion = implementationVersion,
        )
    }

    private companion object {
        const val VERSION_CHANGE_ID = "fulfillment-approval-contract-v1"
        const val LEGACY_IMPLEMENTATION_VERSION = 1
        const val CURRENT_IMPLEMENTATION_VERSION = 2
        const val REQUEST_STOCK_RECOUNT = "request_stock_recount"
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val SAFE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._@:+-]{0,127}")
        val MAX_APPROVAL_WAIT: Duration = Duration.ofDays(7)
        const val RESERVE_SUBSTITUTE_INVENTORY = "reserve_substitute_inventory"
        const val RELEASE_RESERVATION = "release_reservation"
        val V1_ACTIONS = setOf(REQUEST_STOCK_RECOUNT, RESERVE_SUBSTITUTE_INVENTORY)
        val DECISION_ACCEPTING_STATES = setOf(
            DurableWorkflowState.STARTED,
            DurableWorkflowState.COLLECTING_EVIDENCE,
            DurableWorkflowState.AWAITING_APPROVAL,
        )
        const val MAX_DECISION_CANDIDATES = 16
    }

    private data class DecisionCandidate(
        val approvalId: String,
        val decision: WorkflowDecision,
        val reasonZh: String = "",
    )
}
