package io.smartdata.foundation.domain

import java.time.Instant

enum class CaseStatus {
    OPEN,
    AWAITING_APPROVAL,
    IN_PROGRESS,
    VERIFIED_CLOSED,
    MANUAL_INTERVENTION,
}

enum class CasePriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

data class ExceptionCase(
    val id: String,
    val tenantId: String,
    val orderId: String,
    val titleZh: String,
    val priority: CasePriority,
    val status: CaseStatus,
    val scenarioKinds: Set<String>,
    val claims: List<Claim>,
    val knowledgeGaps: List<KnowledgeGap>,
    val stateVersion: Long,
    val domainVersion: String,
    val policyVersion: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(stateVersion >= 0) { "stateVersion cannot be negative" }
        require(claims.all { it.tenantId == tenantId }) { "Claims cannot cross tenant boundaries" }
        require(knowledgeGaps.all { it.tenantId == tenantId }) { "Knowledge gaps cannot cross tenant boundaries" }
    }
}

data class Recommendation(
    val id: String,
    val tenantId: String,
    val caseId: String,
    val caseStateVersion: Long,
    val actionDefinitionId: String,
    val actionInput: Map<String, Any>,
    val rationaleZh: String,
    val evidenceIds: Set<String>,
    val createdAt: Instant,
    val domainVersion: String,
    val policyVersion: String,
    val modelVersion: String,
)
