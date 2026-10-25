package io.smartdata.foundation.repository

import io.smartdata.foundation.domain.ActionReceipt
import io.smartdata.foundation.domain.ActionRequest
import io.smartdata.foundation.domain.AuditFact
import io.smartdata.foundation.domain.ExceptionCase
import io.smartdata.foundation.domain.MemoryAsset
import io.smartdata.foundation.domain.Recommendation
import io.smartdata.foundation.domain.VerificationResult
import io.smartdata.foundation.domain.VerificationPurpose

interface CaseRepository {
    fun findAll(tenantId: String): List<ExceptionCase>
    fun findById(tenantId: String, id: String): ExceptionCase?
    /** Durable adapters lock the case row in the active write unit, before action rows.
     * Never retain the lock across network I/O. The default is for in-memory compatibility. */
    fun lockById(tenantId: String, id: String): ExceptionCase? = findById(tenantId, id)
    fun save(case: ExceptionCase, expectedStateVersion: Long? = null): ExceptionCase
}

interface RecommendationRepository {
    fun findById(tenantId: String, id: String): Recommendation?
    fun save(recommendation: Recommendation): Recommendation
}

interface ActionRequestRepository {
    fun findById(tenantId: String, id: String): ActionRequest?
    fun findByIdempotencyKey(tenantId: String, key: String): ActionRequest?
    /**
     * Implementations must compare and write atomically when expectedStateVersion
     * is present. This CAS is the cross-process execution ownership boundary;
     * an in-memory single-flight is only a latency optimization.
     */
    fun save(request: ActionRequest, expectedStateVersion: Long? = null): ActionRequest
}

interface ActionReceiptRepository {
    fun findByActionRequestId(tenantId: String, actionRequestId: String): ActionReceipt?
    fun save(receipt: ActionReceipt): ActionReceipt
}

interface VerificationRepository {
    fun findByActionRequestId(
        tenantId: String,
        actionRequestId: String,
        purpose: VerificationPurpose = VerificationPurpose.ACTION,
    ): VerificationResult?
    fun save(result: VerificationResult): VerificationResult
}

interface MemoryRepository {
    fun findAll(tenantId: String): List<MemoryAsset>
    fun findById(tenantId: String, id: String): MemoryAsset?
    fun save(asset: MemoryAsset, expectedStateVersion: Long? = null): MemoryAsset
}

interface AuditRepository {
    fun append(fact: AuditFact)
    fun findByAggregate(tenantId: String, aggregateId: String): List<AuditFact>
    fun findByCase(tenantId: String, caseId: String): List<AuditFact>
}
