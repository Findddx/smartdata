package io.smartdata.foundation.port

import io.smartdata.foundation.domain.ActionDefinition
import io.smartdata.foundation.domain.ActionReceipt
import io.smartdata.foundation.domain.ActionRequest
import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.Claim
import io.smartdata.foundation.domain.DataClassification
import io.smartdata.foundation.domain.ExceptionCase
import io.smartdata.foundation.domain.Recommendation
import io.smartdata.foundation.domain.VerificationResult

data class ConnectorCapability(
    val id: String,
    val sourceSystem: String,
    val readable: Boolean,
    val executable: Boolean,
    val verifiable: Boolean,
)

data class SourceSnapshot(
    val sourceSystem: String,
    val sourceRecordId: String,
    val claims: List<Claim>,
)

data class ConnectorExecutionResult(
    val receipt: ActionReceipt,
    val ambiguous: Boolean,
)

interface ConnectorPort {
    fun capabilities(tenantId: String): Set<ConnectorCapability>
    fun snapshot(tenantId: String, sourceSystem: String, sourceRecordId: String): SourceSnapshot
    fun requestEvidence(tenantId: String, caseId: String, observation: String): List<Claim>
    fun execute(definition: ActionDefinition, request: ActionRequest): ConnectorExecutionResult
    fun compensate(definition: ActionDefinition, request: ActionRequest, receipt: ActionReceipt): ActionReceipt
    fun verify(definition: ActionDefinition, request: ActionRequest, receipt: ActionReceipt): VerificationResult
    fun verifyCompensation(definition: ActionDefinition, request: ActionRequest, receipt: ActionReceipt): VerificationResult
    fun health(tenantId: String): Map<String, Boolean>
}

interface ModelPort {
    val modelVersion: String
    fun recommend(case: ExceptionCase, definitions: Collection<ActionDefinition>): Recommendation
}

interface EmbeddingPort {
    val embeddingVersion: String
    fun embed(tenantId: String, texts: List<String>): List<FloatArray>
}

interface ToolExecutorPort {
    fun execute(definition: ActionDefinition, request: ActionRequest): ConnectorExecutionResult
}

data class PolicyDecision(
    val allowed: Boolean,
    val policyVersion: String,
    val reasonZh: String,
)

interface PolicyDecisionPort {
    /** Implementations must throw or return denied when the policy service is unavailable. */
    fun evaluate(
        actor: Actor,
        case: ExceptionCase,
        definition: ActionDefinition,
        input: Map<String, Any>,
    ): PolicyDecision
}

interface DataAccessDecisionPort {
    /** Unknown ACL forms and unavailable policy backends must return false. */
    fun canRead(
        actor: Actor,
        resourceTenantId: String,
        aclRef: String,
        classification: DataClassification,
    ): Boolean
}
