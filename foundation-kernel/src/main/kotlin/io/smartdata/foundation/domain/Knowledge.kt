package io.smartdata.foundation.domain

import java.time.Instant

enum class ClaimStatus {
    ASSERTED,
    INFERRED,
    CONFLICTED,
    REVOKED,
}

data class EvidenceRef(
    val id: String,
    val tenantId: String,
    val sourceSystem: String,
    val sourceRecordId: String,
    val contentHash: String,
    val observedAt: Instant,
    val recordedAt: Instant = observedAt,
    val uri: String? = null,
    val classification: DataClassification = DataClassification.INTERNAL,
    val aclRef: String = "tenant",
) {
    init {
        require(tenantId.isNotBlank()) { "tenantId is required" }
        require(contentHash.matches(Regex("^[a-f0-9]{64}$"))) { "contentHash must be a lowercase SHA-256 digest" }
        require(!recordedAt.isBefore(observedAt)) { "recordedAt cannot precede observedAt" }
        require(aclRef.isNotBlank()) { "aclRef is required" }
    }
}

data class Claim(
    val id: String,
    val tenantId: String,
    val subjectType: String,
    val subjectId: String,
    val predicate: String,
    val value: String,
    val evidenceRefs: List<EvidenceRef>,
    val validFrom: Instant,
    val validTo: Instant? = null,
    val recordedAt: Instant,
    val expiresAt: Instant? = null,
    val classification: DataClassification = DataClassification.INTERNAL,
    val aclRef: String = "tenant",
    val derivationVersion: String? = null,
    val modelVersion: String? = null,
    val confidence: Double = 1.0,
    val status: ClaimStatus = ClaimStatus.ASSERTED,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be between 0 and 1" }
        require(evidenceRefs.isNotEmpty()) { "A claim must retain at least one evidence reference" }
        require(evidenceRefs.all { it.tenantId == tenantId }) { "Evidence cannot cross tenant boundaries" }
        require(validTo == null || !validTo.isBefore(validFrom)) { "validTo cannot precede validFrom" }
    }
}

enum class KnowledgeGapKind {
    UNKNOWN,
    CONFLICTING,
    STALE,
}

data class KnowledgeGap(
    val id: String,
    val tenantId: String,
    val kind: KnowledgeGapKind,
    val code: String,
    val subjectId: String,
    val descriptionZh: String,
    val affectedDecision: String,
    val requiredObservation: String,
    val dueAt: Instant? = null,
    val classification: DataClassification = DataClassification.INTERNAL,
    val aclRef: String = "tenant",
) {
    init {
        require(tenantId.isNotBlank()) { "tenantId is required" }
        require(code.matches(Regex("^[A-Z][A-Z0-9_]{1,79}$"))) { "knowledge gap code is invalid" }
        require(aclRef.isNotBlank()) { "aclRef is required" }
    }
}
