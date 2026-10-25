package io.smartdata.foundation.workflow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class DeterministicFulfillmentActivitiesTest {
    private val activities = DeterministicFulfillmentActivities(DeterministicWorkflowKernelFacade())
    private val input = FulfillmentWorkflowInput(
        tenantId = "tenant-a",
        caseId = "case-a",
        actionRequestId = "action-a",
        domainVersion = "order-fulfillment@1.0.0",
        policyVersion = "approval-policy@1.0.0",
        idempotencyKey = "workflow-test-key",
        approvalExpiresAtEpochMillis = Instant.parse("2026-09-08T08:00:00Z").toEpochMilli(),
    )

    @Test
    fun `keeps workflow payloads small and deterministic`() {
        val first = activities.collectEvidence(input)
        val second = activities.collectEvidence(input)
        assertThat(first).isEqualTo(second)
        assertThat(first.evidenceIds).hasSize(1)

        val execution = activities.executeApprovedAction(input)
        assertThat(execution.state).isEqualTo(DurableExecutionState.EXECUTED_UNVERIFIED)
        val verification = activities.independentlyVerify(input, execution.receiptId)
        assertThat(verification.outcome).isEqualTo(DurableVerificationOutcome.PASSED)
        assertThat(activities.createMemoryCandidate(input, verification.verificationId)).isNotBlank()
    }
}
