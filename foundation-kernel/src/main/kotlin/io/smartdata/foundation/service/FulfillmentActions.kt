package io.smartdata.foundation.service

import io.smartdata.foundation.domain.ActionDefinition
import io.smartdata.foundation.domain.ActionRisk
import io.smartdata.foundation.domain.BusinessRole
import java.time.Duration

object FulfillmentActions {
    const val REQUEST_STOCK_RECOUNT = "request_stock_recount"
    const val RESERVE_SUBSTITUTE_INVENTORY = "reserve_substitute_inventory"
    const val RELEASE_RESERVATION_COMPENSATION = "release_reservation"

    val definitions: Map<String, ActionDefinition> = listOf(
        ActionDefinition(
            id = REQUEST_STOCK_RECOUNT,
            version = "1.0.0",
            titleZh = "请求库存复核",
            inputFields = setOf("caseId", "warehouseCode", "sku", "assigneeId", "dueAt"),
            risk = ActionRisk.LOW,
            requestRoles = setOf(BusinessRole.ORDER_SPECIALIST, BusinessRole.WAREHOUSE_APPROVER, BusinessRole.ADMIN),
            approvalRoles = setOf(BusinessRole.WAREHOUSE_APPROVER, BusinessRole.ADMIN),
            timeout = Duration.ofSeconds(30),
            verifierId = "stock_recount_task_created",
        ),
        ActionDefinition(
            id = RESERVE_SUBSTITUTE_INVENTORY,
            version = "1.0.0",
            titleZh = "预留替代库存",
            inputFields = setOf("caseId", "orderLineId", "warehouseCode", "sku", "quantity"),
            risk = ActionRisk.MEDIUM,
            requestRoles = setOf(BusinessRole.ORDER_SPECIALIST, BusinessRole.ADMIN),
            approvalRoles = setOf(BusinessRole.WAREHOUSE_APPROVER, BusinessRole.ADMIN),
            timeout = Duration.ofSeconds(30),
            compensationActionId = RELEASE_RESERVATION_COMPENSATION,
            verifierId = "substitute_reservation_confirmed",
            compensationVerifierId = "reservation_release_confirmed",
            resolvableGapCodes = setOf("SUPPLIER_DELAY"),
        ),
    ).associateBy { it.id }
}
