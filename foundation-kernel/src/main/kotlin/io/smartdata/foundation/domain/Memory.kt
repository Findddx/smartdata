package io.smartdata.foundation.domain

import java.time.Instant

enum class MemoryLevel {
    L0_TRACE,
    L1_ATOMIC_CANDIDATE,
    L2_SCENARIO_PATTERN,
    L3_APPROVED_PLAYBOOK,
}

enum class MemoryStatus {
    PROPOSED,
    APPROVED,
    SUPERSEDED,
    REVOKED,
}

data class MemoryAsset(
    val id: String,
    val tenantId: String,
    val level: MemoryLevel,
    val status: MemoryStatus,
    val titleZh: String,
    val contentZh: String,
    val sourceCaseIds: Set<String>,
    val sourceEvidenceIds: Set<String>,
    val validFrom: Instant,
    val validTo: Instant? = null,
    val expiresAt: Instant? = null,
    val ownerId: String,
    val aclRef: String,
    val modelVersion: String,
    val embeddingVersion: String?,
    val supersedesId: String? = null,
    val stateVersion: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(tenantId.isNotBlank()) { "tenantId is required" }
        require(sourceCaseIds.isNotEmpty()) { "Memory must retain at least one source case" }
        require(sourceEvidenceIds.isNotEmpty()) { "Memory must retain source evidence" }
        require(stateVersion >= 0) { "stateVersion cannot be negative" }
        require(validTo == null || !validTo.isBefore(validFrom)) { "validTo cannot precede validFrom" }
        require(expiresAt == null || !expiresAt.isBefore(validFrom)) { "expiresAt cannot precede validFrom" }
    }
}
