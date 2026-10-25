package io.smartdata.foundation.service

import io.smartdata.foundation.domain.ActionDefinition
import io.smartdata.foundation.domain.ClaimStatus
import io.smartdata.foundation.domain.ExceptionCase
import io.smartdata.foundation.domain.Recommendation
import io.smartdata.foundation.domain.StableIds
import io.smartdata.foundation.port.ModelPort
import java.time.Clock

class DeterministicRecommendationEngine(
    private val clock: Clock,
) : ModelPort {
    override val modelVersion: String = "deterministic-rules-1.0.0"

    override fun recommend(
        case: ExceptionCase,
        definitions: Collection<ActionDefinition>,
    ): Recommendation {
        val byId = definitions.associateBy { it.id }
        val gapCodes = case.knowledgeGaps.map { it.code }.toSet()
        val actionId = when {
            gapCodes.any { it in recountGapCodes } -> FulfillmentActions.REQUEST_STOCK_RECOUNT
            "SUPPLIER_DELAY" in gapCodes -> FulfillmentActions.RESERVE_SUBSTITUTE_INVENTORY
            else -> throw BusinessValidationException(
                "当前案件没有首版可安全执行的类型化动作；必须由人工补充观测或接管",
            )
        }
        requireNotNull(byId[actionId]) { "Required action definition '$actionId' is unavailable" }

        val sku = trustedClaimValue(case, "sku")
        val warehouse = trustedClaimValue(case, "warehouseCode")
        val input: Map<String, Any> = if (actionId == FulfillmentActions.REQUEST_STOCK_RECOUNT) {
            mapOf(
                "caseId" to case.id,
                "warehouseCode" to warehouse,
                "sku" to sku,
                "assigneeId" to "warehouse-team:$warehouse",
                "dueAt" to clock.instant().plusSeconds(4 * 60 * 60).toString(),
            )
        } else {
            val missing = trustedClaimValue(case, "missingQuantity").toIntOrNull()
                ?.takeIf { it in 1..100_000 }
                ?: throw BusinessValidationException("缺少可信、正整数的缺口数量，禁止预留库存")
            mapOf(
                "caseId" to case.id,
                "orderLineId" to "${case.orderId}-L1",
                "warehouseCode" to warehouse,
                "sku" to sku,
                "quantity" to missing,
            )
        }
        val evidenceIds = case.claims
            .filter { it.status != ClaimStatus.REVOKED && isCurrent(it.validFrom, it.validTo, it.expiresAt) }
            .flatMap { it.evidenceRefs }
            .map { it.id }
            .toSortedSet()
        return Recommendation(
            id = StableIds.from("recommendation", case.tenantId, case.id, case.stateVersion, actionId),
            tenantId = case.tenantId,
            caseId = case.id,
            caseStateVersion = case.stateVersion,
            actionDefinitionId = actionId,
            actionInput = input,
            rationaleZh = if (actionId == FulfillmentActions.REQUEST_STOCK_RECOUNT) {
                "当前证据不能可靠确定可用库存；先建立可验证的人工复核任务，再决定后续履约动作。"
            } else {
                "现有证据表明原履约路径无法按期完成，替代库存预留是可补偿、可验证的最小动作。"
            },
            evidenceIds = evidenceIds,
            createdAt = clock.instant(),
            domainVersion = case.domainVersion,
            policyVersion = case.policyVersion,
            modelVersion = modelVersion,
        )
    }

    private fun trustedClaimValue(case: ExceptionCase, predicate: String): String {
        val values = case.claims.asSequence()
            .filter { it.predicate == predicate }
            .filter { it.status in setOf(ClaimStatus.ASSERTED, ClaimStatus.INFERRED) }
            .filter { it.confidence >= MIN_TRUSTED_CONFIDENCE }
            .filter { isCurrent(it.validFrom, it.validTo, it.expiresAt) }
            .map { it.value }
            .distinct()
            .toList()
        if (values.size != 1) {
            throw BusinessValidationException("字段 $predicate 缺失、过期、冲突或置信度不足，禁止生成写动作")
        }
        return values.single()
    }

    private fun isCurrent(validFrom: java.time.Instant, validTo: java.time.Instant?, expiresAt: java.time.Instant?): Boolean {
        val now = clock.instant()
        return !now.isBefore(validFrom) &&
            (validTo == null || now.isBefore(validTo)) &&
            (expiresAt == null || now.isBefore(expiresAt))
    }

    private companion object {
        const val MIN_TRUSTED_CONFIDENCE = 0.95
        val recountGapCodes = setOf("INVENTORY_MISMATCH", "STALE_ERP_STATUS")
    }
}
