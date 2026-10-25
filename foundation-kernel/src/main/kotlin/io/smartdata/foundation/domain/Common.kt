package io.smartdata.foundation.domain

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

object StableIds {
    fun from(vararg parts: Any?): String = UUID.nameUUIDFromBytes(
        parts.joinToString("|") { it?.toString().orEmpty() }.toByteArray(StandardCharsets.UTF_8),
    ).toString()
}

enum class BusinessRole {
    ORDER_SPECIALIST,
    WAREHOUSE_APPROVER,
    SALES_OWNER,
    AUDITOR,
    ADMIN,
}

data class Actor(
    val id: String,
    val displayName: String,
    val tenantId: String,
    val roles: Set<BusinessRole>,
    // Enterprise requests pin the current membership revision; local/demo actors have no revision.
    val authorizationVersion: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "actor id is required" }
        require(tenantId.isNotBlank()) { "tenantId is required" }
        require(roles.isNotEmpty()) { "at least one business role is required" }
    }
}

enum class DataClassification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED,
}

data class VersionedRef(
    val id: String,
    val version: String,
)

data class AuditFact(
    val tenantId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val actorId: String,
    val occurredAt: Instant,
    val details: Map<String, String> = emptyMap(),
)
