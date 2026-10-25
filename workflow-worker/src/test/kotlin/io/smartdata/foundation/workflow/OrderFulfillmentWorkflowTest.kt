package io.smartdata.foundation.workflow

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderFulfillmentWorkflowTest {
    @Test
    fun `persists approval and independently verifies before closing`() {
        TestWorkflowEnvironment.newInstance().use { environment ->
            val activities = RecordingActivities()
            val workflow = startWorkflow(environment, activities, "happy")
            val input = input(environment, "happy")
            val resultFuture = WorkflowClient.execute(workflow::run, input)
            environment.registerDelayedCallback(Duration.ofSeconds(1)) {
                workflow.approve("approval-record-happy")
            }

            val result = resultFuture.get(10, TimeUnit.SECONDS)
            assertThat(result.finalState).isEqualTo(DurableWorkflowState.VERIFIED_CLOSED)
            assertThat(result.receiptId).isNotBlank()
            assertThat(result.verificationId).isNotBlank()
            assertThat(result.memoryCandidateId).isNotBlank()
            assertThat(result.workflowContractVersion).isEqualTo(2)
            assertThat(activities.approvals).containsExactly("approval-record-happy")
            assertThat(activities.calls).containsSubsequence("execute", "verify", "memory")
        }
    }

    @Test
    fun `expires an undecided approval without executing`() {
        TestWorkflowEnvironment.newInstance().use { environment ->
            val activities = RecordingActivities()
            val workflow = startWorkflow(environment, activities, "expiry")
            val result = workflow.run(input(environment, "expiry", approvalTtl = Duration.ofMinutes(5)))

            assertThat(result.finalState).isEqualTo(DurableWorkflowState.EXPIRED)
            assertThat(activities.calls).contains("expired")
            assertThat(activities.calls).doesNotContain("execute", "verify", "memory")
        }
    }

    @Test
    fun `ambiguous execute failure is not retried and proceeds to independent reconciliation`() {
        TestWorkflowEnvironment.newInstance().use { environment ->
            val activities = RecordingActivities(executeFailure = true, inconclusiveVerification = true)
            val workflow = startWorkflow(environment, activities, "ambiguous")
            val resultFuture = WorkflowClient.execute(workflow::run, input(environment, "ambiguous"))
            environment.registerDelayedCallback(Duration.ofSeconds(1)) {
                workflow.approve("approval-record-ambiguous")
            }

            val result = resultFuture.get(10, TimeUnit.SECONDS)
            assertThat(result.finalState).isEqualTo(DurableWorkflowState.MANUAL_INTERVENTION)
            assertThat(activities.calls.count { it == "execute" }).isEqualTo(1)
            assertThat(activities.calls).containsSubsequence("execute", "verify")
            assertThat(activities.lastVerifiedReceiptId).startsWith("UNCONFIRMED-")
        }
    }

    @Test
    fun `an unverified early approval candidate cannot suppress a later valid approval`() {
        TestWorkflowEnvironment.newInstance().use { environment ->
            val activities = RecordingActivities(invalidApprovalIds = setOf("approval-untrusted"))
            val workflow = startWorkflow(environment, activities, "approval-candidates")
            val resultFuture = WorkflowClient.execute(workflow::run, input(environment, "approval-candidates"))
            environment.registerDelayedCallback(Duration.ofMillis(250)) {
                workflow.approve("approval-untrusted")
            }
            environment.registerDelayedCallback(Duration.ofMillis(500)) {
                workflow.approve("approval-record-valid")
            }

            val result = resultFuture.get(10, TimeUnit.SECONDS)
            assertThat(result.finalState).isEqualTo(DurableWorkflowState.VERIFIED_CLOSED)
            assertThat(activities.approvals).containsSubsequence("approval-untrusted", "approval-record-valid")
            assertThat(activities.calls).contains("execute")
        }
    }

    @Test
    fun `verified recount action waits for the business observation instead of closing the case`() {
        TestWorkflowEnvironment.newInstance().use { environment ->
            val activities = RecordingActivities()
            val workflow = startWorkflow(environment, activities, "recount")
            val workflowInput = input(environment, "recount").copy(
                actionDefinitionId = "request_stock_recount",
                compensationActionId = "",
            )
            val resultFuture = WorkflowClient.execute(workflow::run, workflowInput)
            environment.registerDelayedCallback(Duration.ofSeconds(1)) {
                workflow.approve("approval-record-recount")
            }

            val result = resultFuture.get(10, TimeUnit.SECONDS)
            assertThat(result.finalState).isEqualTo(DurableWorkflowState.ACTION_VERIFIED_AWAITING_OBSERVATION)
            assertThat(result.memoryCandidateId).isNull()
            assertThat(activities.calls).doesNotContain("memory")
        }
    }

    private fun startWorkflow(
        environment: TestWorkflowEnvironment,
        activities: RecordingActivities,
        suffix: String,
    ): OrderFulfillmentWorkflow {
        val queue = "fulfillment-$suffix"
        val worker = environment.newWorker(queue)
        worker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        environment.start()
        return environment.workflowClient.newWorkflowStub(
            OrderFulfillmentWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(queue)
                .setWorkflowId("workflow-$suffix")
                .build(),
        )
    }

    private fun input(
        environment: TestWorkflowEnvironment,
        suffix: String,
        approvalTtl: Duration = Duration.ofHours(1),
    ) = FulfillmentWorkflowInput(
        tenantId = "tenant-a",
        caseId = "case-$suffix",
        actionRequestId = "action-$suffix",
        actionDefinitionId = "reserve_substitute_inventory",
        domainVersion = "order-fulfillment@1.0.0",
        policyVersion = "approval-policy@1.0.0",
        expectedCaseStateVersion = 7,
        idempotencyKey = "workflow-$suffix-key",
        approvalExpiresAtEpochMillis = environment.currentTimeMillis() + approvalTtl.toMillis(),
        compensationActionId = "release_reservation",
    )

    private class RecordingActivities(
        private val executeFailure: Boolean = false,
        private val inconclusiveVerification: Boolean = false,
        private val invalidApprovalIds: Set<String> = emptySet(),
    ) : FulfillmentActivities {
        val calls = CopyOnWriteArrayList<String>()
        val approvals = CopyOnWriteArrayList<String>()
        var lastVerifiedReceiptId: String = ""

        override fun collectEvidence(input: FulfillmentWorkflowInput): SmallEvidenceSummary {
            calls += "evidence"
            return SmallEvidenceSummary(listOf("evidence-${input.caseId}"), "sha256-placeholder")
        }

        override fun recordApproval(input: FulfillmentWorkflowInput, approvalId: String) {
            calls += "approval"
            approvals += approvalId
        }

        override fun validateAndRecordApproval(input: FulfillmentWorkflowInput, approvalId: String): Boolean {
            calls += "approval"
            approvals += approvalId
            return approvalId !in invalidApprovalIds
        }

        override fun executeApprovedAction(input: FulfillmentWorkflowInput): DurableExecutionResult {
            calls += "execute"
            if (executeFailure) error("simulated ambiguous connector failure")
            return DurableExecutionResult("receipt-${input.actionRequestId}", DurableExecutionState.EXECUTED_UNVERIFIED)
        }

        override fun independentlyVerify(
            input: FulfillmentWorkflowInput,
            receiptId: String,
        ): DurableVerificationResult {
            calls += "verify"
            lastVerifiedReceiptId = receiptId
            return DurableVerificationResult(
                "verification-${input.actionRequestId}",
                if (inconclusiveVerification) DurableVerificationOutcome.INCONCLUSIVE else DurableVerificationOutcome.PASSED,
            )
        }

        override fun independentlyVerifyCompensation(
            input: FulfillmentWorkflowInput,
            compensationReceiptId: String,
        ): DurableVerificationResult {
            calls += "verify-compensation"
            return DurableVerificationResult(
                "compensation-verification-${input.actionRequestId}",
                DurableVerificationOutcome.PASSED,
            )
        }

        override fun createMemoryCandidate(input: FulfillmentWorkflowInput, verificationId: String): String {
            calls += "memory"
            return "memory-${input.caseId}"
        }

        override fun compensate(input: FulfillmentWorkflowInput, receiptId: String): DurableExecutionResult {
            calls += "compensate"
            return DurableExecutionResult("compensation-${input.actionRequestId}", DurableExecutionState.COMPENSATED)
        }

        override fun recordRejection(input: FulfillmentWorkflowInput, approvalId: String, reasonZh: String) {
            calls += "rejected"
        }

        override fun validateAndRecordRejection(
            input: FulfillmentWorkflowInput,
            approvalId: String,
            reasonZh: String,
        ): Boolean {
            calls += "rejected"
            return true
        }

        override fun recordApprovalExpiry(input: FulfillmentWorkflowInput) {
            calls += "expired"
        }
    }
}
