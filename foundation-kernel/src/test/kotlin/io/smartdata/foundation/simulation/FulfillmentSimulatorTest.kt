package io.smartdata.foundation.simulation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FulfillmentSimulatorTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-07T08:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `provides 32 combinable templates and 500 deterministic cases`() {
        val first = DeterministicFulfillmentSimulator(clock)
        val second = DeterministicFulfillmentSimulator(clock)
        assertThat(first.templates).hasSize(32)
        assertThat(first.templates.flatMap { it.kinds }.toSet())
            .containsExactlyInAnyOrderElementsOf(ScenarioKind.entries)

        val firstRun = first.generateCases()
        val secondRun = second.generateCases()
        assertThat(firstRun).hasSize(500)
        assertThat(firstRun.map { it.id }).containsExactlyElementsOf(secondRun.map { it.id })
        assertThat(firstRun.map { it.titleZh }).containsExactlyElementsOf(secondRun.map { it.titleZh })
        assertThat(firstRun.flatMap { it.claims }.all { it.evidenceRefs.isNotEmpty() }).isTrue()
    }

    @Test
    fun `four source systems expose independent records and health`() {
        val simulator = DeterministicFulfillmentSimulator(clock)
        val case = simulator.generateCases(1).single()
        val sku = case.claims.first { it.predicate == "sku" }.value
        val warehouse = case.claims.first { it.predicate == "warehouseCode" }.value
        assertThat(simulator.erp.get(case.tenantId, case.orderId)).isNotNull()
        assertThat(simulator.wms.get(case.tenantId, warehouse, sku)).isNotNull()
        assertThat(simulator.tms.get(case.tenantId, case.orderId)).isNotNull()
        assertThat(simulator.qms.get(case.tenantId, case.orderId)).isNotNull()
        assertThat(simulator.health(case.tenantId)).containsOnlyKeys("ERP", "WMS", "TMS", "QMS")
    }
}
