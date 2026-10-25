package io.smartdata.foundation.repository

import io.smartdata.foundation.domain.ActionReceipt
import io.smartdata.foundation.domain.ActionRequest
import io.smartdata.foundation.domain.AuditFact
import io.smartdata.foundation.domain.ExceptionCase
import io.smartdata.foundation.domain.MemoryAsset
import io.smartdata.foundation.domain.Recommendation
import io.smartdata.foundation.domain.VerificationResult
import io.smartdata.foundation.domain.VerificationPurpose
import io.smartdata.foundation.service.OptimisticStateException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryCaseRepository(initial: Collection<ExceptionCase> = emptyList()) : CaseRepository {
    private val values = ConcurrentHashMap<String, ExceptionCase>()

    init {
        initial.forEach { values[key(it.tenantId, it.id)] = it }
    }

    override fun findAll(tenantId: String): List<ExceptionCase> = values.values
        .filter { it.tenantId == tenantId }
        .sortedWith(compareByDescending<ExceptionCase> { it.priority }.thenBy { it.createdAt })

    override fun findById(tenantId: String, id: String): ExceptionCase? = values[key(tenantId, id)]

    @Synchronized
    override fun save(case: ExceptionCase, expectedStateVersion: Long?): ExceptionCase {
        val storageKey = key(case.tenantId, case.id)
        val current = values[storageKey]
        if (expectedStateVersion != null && current?.stateVersion != expectedStateVersion) {
            throw OptimisticStateException("case", case.id, expectedStateVersion, current?.stateVersion)
        }
        values[storageKey] = case
        return case
    }
}

class InMemoryRecommendationRepository : RecommendationRepository {
    private val values = ConcurrentHashMap<String, Recommendation>()
    override fun findById(tenantId: String, id: String): Recommendation? = values[key(tenantId, id)]
    override fun save(recommendation: Recommendation): Recommendation = recommendation.also {
        values[key(it.tenantId, it.id)] = it
    }
}

class InMemoryActionRequestRepository : ActionRequestRepository {
    private val values = ConcurrentHashMap<String, ActionRequest>()
    private val idempotencyIndex = ConcurrentHashMap<String, String>()

    override fun findById(tenantId: String, id: String): ActionRequest? = values[key(tenantId, id)]

    override fun findByIdempotencyKey(tenantId: String, key: String): ActionRequest? =
        idempotencyIndex[key(tenantId, key)]?.let(values::get)

    @Synchronized
    override fun save(request: ActionRequest, expectedStateVersion: Long?): ActionRequest {
        val storageKey = key(request.tenantId, request.id)
        val current = values[storageKey]
        if (expectedStateVersion != null && current?.stateVersion != expectedStateVersion) {
            throw OptimisticStateException("actionRequest", request.id, expectedStateVersion, current?.stateVersion)
        }
        val indexKey = key(request.tenantId, request.idempotencyKey)
        val indexedId = idempotencyIndex[indexKey]
        if (indexedId != null && indexedId != storageKey) {
            throw IllegalStateException("Duplicate idempotency key")
        }
        values[storageKey] = request
        idempotencyIndex[indexKey] = storageKey
        return request
    }
}

class InMemoryActionReceiptRepository : ActionReceiptRepository {
    private val values = ConcurrentHashMap<String, ActionReceipt>()
    override fun findByActionRequestId(tenantId: String, actionRequestId: String): ActionReceipt? =
        values[key(tenantId, actionRequestId)]

    override fun save(receipt: ActionReceipt): ActionReceipt = receipt.also {
        values[key(it.tenantId, it.actionRequestId)] = it
    }
}

class InMemoryVerificationRepository : VerificationRepository {
    private val values = ConcurrentHashMap<String, VerificationResult>()
    override fun findByActionRequestId(
        tenantId: String,
        actionRequestId: String,
        purpose: VerificationPurpose,
    ): VerificationResult? = values[key(tenantId, "$actionRequestId::${purpose.name}")]

    override fun save(result: VerificationResult): VerificationResult = result.also {
        values[key(it.tenantId, "${it.actionRequestId}::${it.purpose.name}")] = it
    }
}

class InMemoryMemoryRepository : MemoryRepository {
    private val values = ConcurrentHashMap<String, MemoryAsset>()
    override fun findAll(tenantId: String): List<MemoryAsset> = values.values
        .filter { it.tenantId == tenantId }
        .sortedByDescending { it.createdAt }

    override fun findById(tenantId: String, id: String): MemoryAsset? = values[key(tenantId, id)]

    @Synchronized
    override fun save(asset: MemoryAsset, expectedStateVersion: Long?): MemoryAsset {
        val storageKey = key(asset.tenantId, asset.id)
        val current = values[storageKey]
        if (expectedStateVersion != null && current?.stateVersion != expectedStateVersion) {
            throw OptimisticStateException("memoryAsset", asset.id, expectedStateVersion, current?.stateVersion)
        }
        values[storageKey] = asset
        return asset
    }
}

class InMemoryAuditRepository : AuditRepository {
    private val facts = CopyOnWriteArrayList<AuditFact>()
    override fun append(fact: AuditFact) {
        facts += fact
    }

    override fun findByAggregate(tenantId: String, aggregateId: String): List<AuditFact> = facts
        .filter { it.tenantId == tenantId && it.aggregateId == aggregateId }
        .sortedBy { it.occurredAt }

    override fun findByCase(tenantId: String, caseId: String): List<AuditFact> = facts
        .filter {
            it.tenantId == tenantId &&
                (it.aggregateId == caseId || it.details["caseId"] == caseId)
        }
        .sortedBy { it.occurredAt }
}

private fun key(tenantId: String, id: String): String = "$tenantId::$id"
