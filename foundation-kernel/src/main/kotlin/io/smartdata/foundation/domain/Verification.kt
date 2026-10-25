package io.smartdata.foundation.domain

import java.time.Instant

enum class VerificationOutcome {
    PASSED,
    FAILED,
    INCONCLUSIVE,
}

enum class VerificationPurpose {
    ACTION,
    COMPENSATION,
}

data class VerificationAssertion(
    val name: String,
    val expected: String,
    val observed: String,
    val passed: Boolean,
)

data class VerificationResult(
    val id: String,
    val tenantId: String,
    val actionRequestId: String,
    val verifierId: String,
    val purpose: VerificationPurpose = VerificationPurpose.ACTION,
    val independentSource: String,
    val outcome: VerificationOutcome,
    val assertions: List<VerificationAssertion>,
    val evidenceRefs: List<EvidenceRef>,
    val resolvedKnowledgeGapIds: Set<String> = emptySet(),
    val observedFrom: Instant,
    val observedUntil: Instant,
    val antiGamingMetrics: Map<String, Double> = emptyMap(),
    val createdAt: Instant,
) {
    init {
        require(independentSource.isNotBlank()) { "Verification must name an independent source" }
        require(evidenceRefs.isNotEmpty()) { "Verification requires evidence" }
        require(evidenceRefs.all { it.tenantId == tenantId }) { "Verification evidence cannot cross tenant boundaries" }
        require(!observedUntil.isBefore(observedFrom)) { "Verification observation window is invalid" }
    }
}
