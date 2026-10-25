package io.smartdata.foundation.domain

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class ActionStateMachineTest {
    @Test
    fun `cannot bypass approval and execution states`() {
        val now = Instant.parse("2026-09-07T08:00:00Z")
        val request = ActionRequest(
            id = "action", tenantId = "tenant", caseId = "case", recommendationId = "recommendation",
            definitionId = "request_stock_recount", definitionVersion = "1.0.0", input = emptyMap(),
            evidenceIds = emptySet(), requestedBy = "requester", idempotencyKey = "12345678",
            requestFingerprint = "fingerprint", expectedCaseStateVersion = 0, boundCaseStateVersion = 1,
            domainVersion = "order-fulfillment@1.0.0", policyVersion = "policy@1", state = ActionState.PROPOSED,
            stateVersion = 0, createdAt = now, approvalExpiresAt = now.plusSeconds(3600), updatedAt = now,
        )
        assertThatThrownBy {
            ActionStateMachine.transition(request, ActionState.EXECUTING, now)
        }.isInstanceOf(InvalidActionTransition::class.java)
    }
}
