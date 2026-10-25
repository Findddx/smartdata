package io.smartdata.foundation.simulation

import io.smartdata.foundation.domain.ActionAttempt
import io.smartdata.foundation.domain.ActionDefinition
import io.smartdata.foundation.domain.ActionReceipt
import io.smartdata.foundation.domain.ActionRequest
import io.smartdata.foundation.domain.CasePriority
import io.smartdata.foundation.domain.CaseStatus
import io.smartdata.foundation.domain.Claim
import io.smartdata.foundation.domain.ClaimStatus
import io.smartdata.foundation.domain.DataClassification
import io.smartdata.foundation.domain.EvidenceRef
import io.smartdata.foundation.domain.ExceptionCase
import io.smartdata.foundation.domain.KnowledgeGap
import io.smartdata.foundation.domain.KnowledgeGapKind
import io.smartdata.foundation.domain.ReceiptOutcome
import io.smartdata.foundation.domain.StableIds
import io.smartdata.foundation.domain.VerificationAssertion
import io.smartdata.foundation.domain.VerificationOutcome
import io.smartdata.foundation.domain.VerificationPurpose
import io.smartdata.foundation.domain.VerificationResult
import io.smartdata.foundation.port.ConnectorCapability
import io.smartdata.foundation.port.ConnectorExecutionResult
import io.smartdata.foundation.port.ConnectorPort
import io.smartdata.foundation.port.SourceSnapshot
import io.smartdata.foundation.port.ToolExecutorPort
import io.smartdata.foundation.service.FulfillmentActions
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

enum class ScenarioKind {
    STALE_ERP_STATUS,
    DUPLICATE_ORDER_ID,
    INVENTORY_MISMATCH,
    PARTIAL_SHIPMENT,
    SUPPLIER_DELAY,
    MISSING_QUALITY_INSPECTION,
    ETA_CONFLICT,
    AMBIGUOUS_TIMEOUT,
}

data class ScenarioTemplate(val id: String, val kinds: Set<ScenarioKind>)

data class ErpOrderRecord(
    val tenantId: String,
    val orderId: String,
    val customerId: String,
    val sku: String,
    val quantity: Int,
    val status: String,
    val promisedAt: Instant,
    val updatedAt: Instant,
)

data class WmsInventoryRecord(
    val tenantId: String,
    val warehouseId: String,
    val sku: String,
    val bookedQuantity: Int,
    val physicalQuantity: Int,
    val updatedAt: Instant,
)

data class TmsShipmentRecord(
    val tenantId: String,
    val orderId: String,
    val status: String,
    val shippedQuantity: Int,
    val eta: Instant,
    val updatedAt: Instant,
)

data class QmsInspectionRecord(
    val tenantId: String,
    val orderId: String,
    val status: String,
    val inspectedAt: Instant?,
)

/** Each simulator owns its records; there is deliberately no shared source-system database. */
class ErpSimulator {
    private val records = ConcurrentHashMap<String, ErpOrderRecord>()
    fun reset() = records.clear()
    fun put(record: ErpOrderRecord) { records[key(record.tenantId, record.orderId)] = record }
    fun get(tenantId: String, orderId: String): ErpOrderRecord? = records[key(tenantId, orderId)]
}

class WmsSimulator {
    private data class RecountTask(
        val tenantId: String,
        val caseId: String,
        val warehouseId: String,
        val sku: String,
        val assigneeId: String,
        val dueAt: Instant,
    )

    private data class Reservation(
        val tenantId: String,
        val orderLineId: String,
        val warehouseId: String,
        val sku: String,
        val quantity: Int,
    )

    private val inventory = ConcurrentHashMap<String, WmsInventoryRecord>()
    private val recountTasks = ConcurrentHashMap<String, RecountTask>()
    private val reservations = ConcurrentHashMap<String, Reservation>()
    private val reservationLocks = ConcurrentHashMap<String, Any>()
    fun reset() { inventory.clear(); recountTasks.clear(); reservations.clear(); reservationLocks.clear() }
    fun put(record: WmsInventoryRecord) {
        inventory[key(record.tenantId, record.warehouseId, record.sku)] = record
    }
    fun get(tenantId: String, warehouseId: String, sku: String): WmsInventoryRecord? =
        inventory[key(tenantId, warehouseId, sku)]
    fun getBySku(tenantId: String, sku: String): WmsInventoryRecord? =
        inventory.values.firstOrNull { it.tenantId == tenantId && it.sku == sku }
    fun createRecount(
        requestId: String,
        tenantId: String,
        caseId: String,
        warehouseId: String,
        sku: String,
        assigneeId: String,
        dueAt: Instant,
    ): Boolean = recountTasks.putIfAbsent(
        requestId,
        RecountTask(tenantId, caseId, warehouseId, sku, assigneeId, dueAt),
    ) == null
    fun recountFieldMatches(
        requestId: String,
        tenantId: String,
        caseId: String,
        warehouseId: String,
        sku: String,
        assigneeId: String,
        dueAt: Instant,
    ): Map<String, Boolean> {
        val actual = recountTasks[requestId]
        return linkedMapOf(
            "task_exists" to (actual != null),
            "tenant_id" to (actual?.tenantId == tenantId),
            "case_id" to (actual?.caseId == caseId),
            "warehouse_code" to (actual?.warehouseId == warehouseId),
            "sku" to (actual?.sku == sku),
            "assignee_id" to (actual?.assigneeId == assigneeId),
            "due_at" to (actual?.dueAt == dueAt),
        )
    }
    fun reserve(
        requestId: String,
        tenantId: String,
        orderLineId: String,
        warehouseId: String,
        sku: String,
        quantity: Int,
    ): Boolean {
        if (reservations.containsKey(requestId)) return true
        val inventoryKey = key(tenantId, warehouseId, sku)
        return synchronized(reservationLocks.computeIfAbsent(inventoryKey) { Any() }) {
            if (reservations.containsKey(requestId)) return@synchronized true
            val current = get(tenantId, warehouseId, sku) ?: return@synchronized false
            val alreadyReserved = reservations.values
                .filter { it.tenantId == tenantId && it.warehouseId == warehouseId && it.sku == sku }
                .sumOf { it.quantity }
            if (current.physicalQuantity - alreadyReserved < quantity) return@synchronized false
            reservations[requestId] = Reservation(tenantId, orderLineId, warehouseId, sku, quantity)
            true
        }
    }
    fun reservationFieldMatches(
        requestId: String,
        tenantId: String,
        orderLineId: String,
        warehouseId: String,
        sku: String,
        quantity: Int,
    ): Map<String, Boolean> {
        val actual = reservations[requestId]
        return linkedMapOf(
            "reservation_exists" to (actual != null),
            "tenant_id" to (actual?.tenantId == tenantId),
            "order_line_id" to (actual?.orderLineId == orderLineId),
            "warehouse_code" to (actual?.warehouseId == warehouseId),
            "sku" to (actual?.sku == sku),
            "quantity" to (actual?.quantity == quantity),
        )
    }
    fun release(requestId: String): Boolean = reservations.remove(requestId) != null
    fun reservationAbsent(requestId: String): Boolean = !reservations.containsKey(requestId)
}

class TmsSimulator {
    private val records = ConcurrentHashMap<String, TmsShipmentRecord>()
    fun reset() = records.clear()
    fun put(record: TmsShipmentRecord) { records[key(record.tenantId, record.orderId)] = record }
    fun get(tenantId: String, orderId: String): TmsShipmentRecord? = records[key(tenantId, orderId)]
}

class QmsSimulator {
    private val records = ConcurrentHashMap<String, QmsInspectionRecord>()
    fun reset() = records.clear()
    fun put(record: QmsInspectionRecord) { records[key(record.tenantId, record.orderId)] = record }
    fun get(tenantId: String, orderId: String): QmsInspectionRecord? = records[key(tenantId, orderId)]
}

class DeterministicFulfillmentSimulator(
    private val clock: Clock,
    val erp: ErpSimulator = ErpSimulator(),
    val wms: WmsSimulator = WmsSimulator(),
    val tms: TmsSimulator = TmsSimulator(),
    val qms: QmsSimulator = QmsSimulator(),
) : ConnectorPort, ToolExecutorPort {
    private val scenarioByCase = ConcurrentHashMap<String, Set<ScenarioKind>>()
    private val gapIdsByCase = ConcurrentHashMap<String, Map<ScenarioKind, String>>()
    private val executionByRequest = ConcurrentHashMap<String, ConnectorExecutionResult>()
    private val baseTime = Instant.parse("2026-01-01T00:00:00Z")

    val templates: List<ScenarioTemplate> = buildTemplates()

    init {
        check(templates.size == 32) { "The simulator must expose exactly 32 deterministic templates" }
    }

    fun reset() {
        erp.reset(); wms.reset(); tms.reset(); qms.reset()
        scenarioByCase.clear(); gapIdsByCase.clear(); executionByRequest.clear()
    }

    fun generateCases(count: Int = 500, tenantId: String = "demo-cn-001"): List<ExceptionCase> {
        require(count >= 0)
        reset()
        return (0 until count).map { generateCase(tenantId, it, templates[it % templates.size]) }
    }

    private fun generateCase(tenantId: String, sequence: Int, template: ScenarioTemplate): ExceptionCase {
        val observedAt = baseTime.plus(sequence.toLong(), ChronoUnit.MINUTES)
        val orderId = "SO-${100000 + sequence}"
        val caseId = StableIds.from("case", tenantId, sequence, template.id)
        val sku = "SKU-${1000 + sequence % 31}"
        val warehouseId = listOf("WH-SH-01", "WH-SZ-01", "WH-CD-01")[sequence % 3]
        val orderQuantity = 10 + sequence % 10
        val physical = if (ScenarioKind.INVENTORY_MISMATCH in template.kinds) orderQuantity - 2 else orderQuantity + 5
        val shipped = if (ScenarioKind.PARTIAL_SHIPMENT in template.kinds) orderQuantity - 3 else orderQuantity
        val promisedAt = observedAt.plus(3, ChronoUnit.DAYS)
        val eta = if (ScenarioKind.ETA_CONFLICT in template.kinds) promisedAt.plus(2, ChronoUnit.DAYS) else promisedAt.minus(4, ChronoUnit.HOURS)
        val erpRecord = ErpOrderRecord(
            tenantId, orderId, "C-${sequence % 73}", sku, orderQuantity,
            if (ScenarioKind.STALE_ERP_STATUS in template.kinds) "待分配" else "履约中",
            promisedAt,
            if (ScenarioKind.STALE_ERP_STATUS in template.kinds) observedAt.minus(3, ChronoUnit.DAYS) else observedAt,
        )
        val wmsRecord = WmsInventoryRecord(tenantId, warehouseId, sku, orderQuantity, physical, observedAt)
        val tmsRecord = TmsShipmentRecord(
            tenantId, orderId,
            if (shipped < orderQuantity) "部分发运" else "已揽收",
            shipped, eta, observedAt,
        )
        val qmsRecord = QmsInspectionRecord(
            tenantId, orderId,
            if (ScenarioKind.MISSING_QUALITY_INSPECTION in template.kinds) "缺失" else "合格",
            if (ScenarioKind.MISSING_QUALITY_INSPECTION in template.kinds) null else observedAt.minus(2, ChronoUnit.HOURS),
        )
        erp.put(erpRecord); wms.put(wmsRecord); tms.put(tmsRecord); qms.put(qmsRecord)

        val claims = mutableListOf<Claim>()
        claims += claim(tenantId, orderId, "Order", "sku", sku, "ERP", orderId, erpRecord.updatedAt)
        claims += claim(tenantId, orderId, "Order", "warehouseCode", warehouseId, "WMS", sku, wmsRecord.updatedAt)
        claims += claim(tenantId, orderId, "Order", "orderedQuantity", orderQuantity.toString(), "ERP", orderId, erpRecord.updatedAt)
        claims += claim(tenantId, orderId, "Order", "availableQuantity", physical.toString(), "WMS", sku, wmsRecord.updatedAt)
        claims += claim(tenantId, orderId, "Order", "missingQuantity", (orderQuantity - shipped).coerceAtLeast(1).toString(), "TMS", orderId, tmsRecord.updatedAt)
        claims += claim(tenantId, orderId, "Order", "erpStatus", erpRecord.status, "ERP", orderId, erpRecord.updatedAt,
            if (ScenarioKind.STALE_ERP_STATUS in template.kinds) ClaimStatus.CONFLICTED else ClaimStatus.ASSERTED)
        claims += claim(tenantId, orderId, "Shipment", "eta", eta.toString(), "TMS", orderId, tmsRecord.updatedAt,
            if (ScenarioKind.ETA_CONFLICT in template.kinds) ClaimStatus.CONFLICTED else ClaimStatus.ASSERTED)
        claims += claim(tenantId, orderId, "QualityInspection", "status", qmsRecord.status, "QMS", orderId, observedAt)

        val gaps = template.kinds.map { kind -> gapFor(tenantId, orderId, kind, observedAt) }
        val case = ExceptionCase(
            id = caseId,
            tenantId = tenantId,
            orderId = if (ScenarioKind.DUPLICATE_ORDER_ID in template.kinds) "$orderId（存在重复映射）" else orderId,
            titleZh = "订单履约异常：${template.kinds.joinToString("、") { scenarioName(it) }}",
            priority = when {
                ScenarioKind.AMBIGUOUS_TIMEOUT in template.kinds -> CasePriority.CRITICAL
                template.kinds.size > 1 || ScenarioKind.SUPPLIER_DELAY in template.kinds -> CasePriority.HIGH
                else -> CasePriority.MEDIUM
            },
            status = CaseStatus.OPEN,
            scenarioKinds = template.kinds.map { it.name }.toSet(),
            claims = claims,
            knowledgeGaps = gaps,
            stateVersion = 0,
            domainVersion = "order-fulfillment@1.0.0",
            policyVersion = "approval-policy@1.0.0",
            createdAt = observedAt,
            updatedAt = observedAt,
        )
        scenarioByCase[key(tenantId, caseId)] = template.kinds
        gapIdsByCase[key(tenantId, caseId)] = template.kinds.associateWith {
            StableIds.from("gap", tenantId, orderId, it)
        }
        return case
    }

    override fun capabilities(tenantId: String): Set<ConnectorCapability> = setOf(
        ConnectorCapability("erp-read", "ERP", true, false, false),
        ConnectorCapability("wms-actions", "WMS", true, true, true),
        ConnectorCapability("tms-read", "TMS", true, false, true),
        ConnectorCapability("qms-read", "QMS", true, false, true),
    )

    override fun snapshot(tenantId: String, sourceSystem: String, sourceRecordId: String): SourceSnapshot {
        val now = clock.instant()
        val claims = when (sourceSystem.uppercase()) {
            "ERP" -> erp.get(tenantId, sourceRecordId)?.let {
                listOf(claim(tenantId, it.orderId, "Order", "erpStatus", it.status, "ERP", it.orderId, it.updatedAt))
            }
            "WMS" -> wms.getBySku(tenantId, sourceRecordId)?.let {
                listOf(claim(tenantId, it.sku, "Inventory", "physicalQuantity", it.physicalQuantity.toString(), "WMS", it.sku, it.updatedAt))
            }
            "TMS" -> tms.get(tenantId, sourceRecordId)?.let {
                listOf(claim(tenantId, it.orderId, "Shipment", "status", it.status, "TMS", it.orderId, it.updatedAt))
            }
            "QMS" -> qms.get(tenantId, sourceRecordId)?.let {
                listOf(claim(tenantId, it.orderId, "QualityInspection", "status", it.status, "QMS", it.orderId, now))
            }
            else -> null
        } ?: emptyList()
        return SourceSnapshot(sourceSystem.uppercase(), sourceRecordId, claims)
    }

    override fun requestEvidence(tenantId: String, caseId: String, observation: String): List<Claim> {
        val observedAt = clock.instant()
        return listOf(claim(tenantId, caseId, "ExceptionCase", "requestedObservation", observation, "WMS", caseId, observedAt))
    }

    override fun execute(definition: ActionDefinition, request: ActionRequest): ConnectorExecutionResult {
        return executionByRequest.computeIfAbsent(key(request.tenantId, request.id)) {
        val startedAt = clock.instant()
        val success = when (definition.id) {
            FulfillmentActions.REQUEST_STOCK_RECOUNT -> wms.createRecount(
                request.id,
                request.tenantId,
                request.input.getValue("caseId").toString(),
                request.input.getValue("warehouseCode").toString(),
                request.input.getValue("sku").toString(),
                request.input.getValue("assigneeId").toString(),
                Instant.parse(request.input.getValue("dueAt").toString()),
            )
            FulfillmentActions.RESERVE_SUBSTITUTE_INVENTORY -> wms.reserve(
                request.id,
                request.tenantId,
                request.input.getValue("orderLineId").toString(),
                request.input.getValue("warehouseCode").toString(),
                request.input.getValue("sku").toString(),
                (request.input.getValue("quantity") as? Number)?.toInt()
                    ?: request.input.getValue("quantity").toString().toInt(),
            )
            else -> false
        }
        val ambiguous = ScenarioKind.AMBIGUOUS_TIMEOUT in scenarioByCase[key(request.tenantId, request.caseId)].orEmpty()
        val outcome = when {
            ambiguous -> ReceiptOutcome.UNKNOWN
            success -> ReceiptOutcome.SUCCEEDED
            else -> ReceiptOutcome.FAILED
        }
        val completedAt = clock.instant()
        val receipt = ActionReceipt(
            id = StableIds.from("receipt", request.tenantId, request.id),
            tenantId = request.tenantId,
            actionRequestId = request.id,
            externalRequestId = "WMS-${request.id}",
            externalReceiptId = if (ambiguous) null else StableIds.from("wms-receipt", request.id),
            outcome = outcome,
            attempts = listOf(ActionAttempt(1, startedAt, completedAt, outcome,
                if (ambiguous) "外部系统超时，结果未知，禁止盲目重试" else if (success) "WMS 已受理" else "WMS 拒绝动作")),
            createdAt = completedAt,
            updatedAt = completedAt,
        )
            ConnectorExecutionResult(receipt, ambiguous)
        }
    }

    override fun compensate(
        definition: ActionDefinition,
        request: ActionRequest,
        receipt: ActionReceipt,
    ): ActionReceipt {
        val released = definition.compensationActionId == FulfillmentActions.RELEASE_RESERVATION_COMPENSATION &&
            wms.release(request.id)
        return receipt.copy(
            outcome = if (released) ReceiptOutcome.COMPENSATED else receipt.outcome,
            compensationReceiptId = if (released) StableIds.from("release", request.id) else null,
            updatedAt = clock.instant(),
        )
    }

    override fun verify(
        definition: ActionDefinition,
        request: ActionRequest,
        receipt: ActionReceipt,
    ): VerificationResult {
        val observedAt = clock.instant()
        val fieldMatches = when (definition.id) {
            FulfillmentActions.REQUEST_STOCK_RECOUNT -> wms.recountFieldMatches(
                request.id,
                request.tenantId,
                request.input.getValue("caseId").toString(),
                request.input.getValue("warehouseCode").toString(),
                request.input.getValue("sku").toString(),
                request.input.getValue("assigneeId").toString(),
                Instant.parse(request.input.getValue("dueAt").toString()),
            )
            FulfillmentActions.RESERVE_SUBSTITUTE_INVENTORY -> wms.reservationFieldMatches(
                request.id,
                request.tenantId,
                request.input.getValue("orderLineId").toString(),
                request.input.getValue("warehouseCode").toString(),
                request.input.getValue("sku").toString(),
                (request.input.getValue("quantity") as? Number)?.toInt()
                    ?: request.input.getValue("quantity").toString().toInt(),
            )
            else -> mapOf("known_action" to false)
        }
        val observed = fieldMatches.values.all { it }
        val evidence = evidence(
            request.tenantId, "WMS", request.id,
            "verify:${definition.id}:${fieldMatches.toSortedMap()}", observedAt,
        )
        return VerificationResult(
            id = StableIds.from("verification", request.tenantId, request.id),
            tenantId = request.tenantId,
            actionRequestId = request.id,
            verifierId = definition.verifierId,
            independentSource = "WMS_READ_MODEL",
            outcome = if (observed) VerificationOutcome.PASSED else VerificationOutcome.FAILED,
            assertions = fieldMatches.map { (name, passed) ->
                VerificationAssertion(
                    name = name,
                    expected = "matches_typed_action_request",
                    observed = if (passed) "matched" else "mismatched",
                    passed = passed,
                )
            },
            evidenceRefs = listOf(evidence),
            resolvedKnowledgeGapIds = if (
                definition.id == FulfillmentActions.RESERVE_SUBSTITUTE_INVENTORY && observed
            ) {
                setOfNotNull(gapIdsByCase[key(request.tenantId, request.caseId)]?.get(ScenarioKind.SUPPLIER_DELAY))
            } else {
                emptySet()
            },
            observedFrom = receipt.createdAt,
            observedUntil = observedAt,
            antiGamingMetrics = mapOf("source_readback" to 1.0, "agent_self_report" to 0.0),
            createdAt = observedAt,
        )
    }

    override fun verifyCompensation(
        definition: ActionDefinition,
        request: ActionRequest,
        receipt: ActionReceipt,
    ): VerificationResult {
        val observedAt = clock.instant()
        val released = wms.reservationAbsent(request.id)
        val evidence = evidence(
            request.tenantId,
            "WMS",
            request.id,
            "verify:${definition.compensationActionId}:reservation_absent=$released",
            observedAt,
        )
        return VerificationResult(
            id = StableIds.from("compensation-verification", request.tenantId, request.id),
            tenantId = request.tenantId,
            actionRequestId = request.id,
            verifierId = definition.compensationVerifierId.orEmpty(),
            purpose = VerificationPurpose.COMPENSATION,
            independentSource = "WMS_READ_MODEL",
            outcome = if (released) VerificationOutcome.PASSED else VerificationOutcome.FAILED,
            assertions = listOf(VerificationAssertion(
                name = "reservation_absent",
                expected = "true",
                observed = released.toString(),
                passed = released,
            )),
            evidenceRefs = listOf(evidence),
            observedFrom = receipt.updatedAt,
            observedUntil = observedAt,
            antiGamingMetrics = mapOf("source_readback" to 1.0, "agent_self_report" to 0.0),
            createdAt = observedAt,
        )
    }

    override fun health(tenantId: String): Map<String, Boolean> =
        mapOf("ERP" to true, "WMS" to true, "TMS" to true, "QMS" to true)

    private fun buildTemplates(): List<ScenarioTemplate> {
        val kinds = ScenarioKind.entries
        val singles = kinds.map { setOf(it) }
        val pairs = buildList {
            for (left in kinds.indices) for (right in left + 1 until kinds.size) add(setOf(kinds[left], kinds[right]))
        }.take(24)
        return (singles + pairs).mapIndexed { index, set -> ScenarioTemplate("TPL-${(index + 1).toString().padStart(2, '0')}", set) }
    }

    private fun gapFor(tenantId: String, orderId: String, kind: ScenarioKind, at: Instant): KnowledgeGap {
        val gapKind = when (kind) {
            ScenarioKind.STALE_ERP_STATUS -> KnowledgeGapKind.STALE
            ScenarioKind.DUPLICATE_ORDER_ID, ScenarioKind.INVENTORY_MISMATCH, ScenarioKind.ETA_CONFLICT -> KnowledgeGapKind.CONFLICTING
            else -> KnowledgeGapKind.UNKNOWN
        }
        return KnowledgeGap(
            id = StableIds.from("gap", tenantId, orderId, kind), tenantId = tenantId, kind = gapKind,
            code = kind.name,
            subjectId = orderId, descriptionZh = scenarioName(kind), affectedDecision = "能否按承诺完成履约",
            requiredObservation = when (kind) {
                ScenarioKind.INVENTORY_MISMATCH, ScenarioKind.STALE_ERP_STATUS -> "WMS 现场库存复核"
                ScenarioKind.MISSING_QUALITY_INSPECTION -> "QMS 质检记录"
                ScenarioKind.ETA_CONFLICT -> "TMS 最新轨迹"
                else -> "来源系统独立回读"
            },
            dueAt = at.plus(4, ChronoUnit.HOURS),
        )
    }

    private fun scenarioName(kind: ScenarioKind): String = when (kind) {
        ScenarioKind.STALE_ERP_STATUS -> "ERP 状态滞后"
        ScenarioKind.DUPLICATE_ORDER_ID -> "订单编号重复"
        ScenarioKind.INVENTORY_MISMATCH -> "库存账实不符"
        ScenarioKind.PARTIAL_SHIPMENT -> "部分发货"
        ScenarioKind.SUPPLIER_DELAY -> "供应商延期"
        ScenarioKind.MISSING_QUALITY_INSPECTION -> "缺少质检"
        ScenarioKind.ETA_CONFLICT -> "预计到达时间冲突"
        ScenarioKind.AMBIGUOUS_TIMEOUT -> "外部调用结果不确定"
    }

    private fun claim(
        tenantId: String,
        subjectId: String,
        subjectType: String,
        predicate: String,
        value: String,
        source: String,
        recordId: String,
        at: Instant,
        status: ClaimStatus = ClaimStatus.ASSERTED,
    ): Claim {
        val ev = evidence(tenantId, source, recordId, "$subjectId|$predicate|$value", at)
        return Claim(
            id = StableIds.from("claim", tenantId, subjectId, predicate, source, value),
            tenantId = tenantId, subjectType = subjectType, subjectId = subjectId, predicate = predicate,
            value = value, evidenceRefs = listOf(ev), validFrom = at, recordedAt = at,
            expiresAt = if (status == ClaimStatus.CONFLICTED) at.plus(1, ChronoUnit.HOURS) else null,
            classification = DataClassification.INTERNAL, aclRef = "tenant:$tenantId", status = status,
        )
    }

    private fun evidence(tenantId: String, source: String, recordId: String, content: String, at: Instant): EvidenceRef =
        EvidenceRef(
            id = StableIds.from("evidence", tenantId, source, recordId, content), tenantId = tenantId,
            sourceSystem = source, sourceRecordId = recordId, contentHash = sha256(content), observedAt = at,
            uri = "sim://$source/$recordId", classification = DataClassification.INTERNAL, aclRef = "tenant:$tenantId",
        )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun key(vararg parts: String): String = parts.joinToString("::")
