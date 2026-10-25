package io.smartdata.foundation.runtime.config

import io.smartdata.foundation.port.ConnectorPort
import io.smartdata.foundation.repository.InMemoryActionReceiptRepository
import io.smartdata.foundation.repository.InMemoryActionRequestRepository
import io.smartdata.foundation.repository.InMemoryAuditRepository
import io.smartdata.foundation.repository.InMemoryCaseRepository
import io.smartdata.foundation.repository.InMemoryMemoryRepository
import io.smartdata.foundation.repository.InMemoryRecommendationRepository
import io.smartdata.foundation.repository.InMemoryVerificationRepository
import io.smartdata.foundation.service.DeterministicRecommendationEngine
import io.smartdata.foundation.service.DeterministicApprovalPolicy
import io.smartdata.foundation.service.DeterministicDataAccessPolicy
import io.smartdata.foundation.service.FoundationKernel
import io.smartdata.foundation.simulation.DeterministicFulfillmentSimulator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Clock

@Configuration
@Profile("local")
class LocalRuntimeConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun simulator(clock: Clock): DeterministicFulfillmentSimulator =
        DeterministicFulfillmentSimulator(clock)

    @Bean
    fun foundationKernel(
        clock: Clock,
        simulator: DeterministicFulfillmentSimulator,
    ): FoundationKernel {
        val generatedCases = simulator.generateCases(500, DEMO_TENANT)
        return FoundationKernel(
            cases = InMemoryCaseRepository(generatedCases),
            recommendations = InMemoryRecommendationRepository(),
            actions = InMemoryActionRequestRepository(),
            receipts = InMemoryActionReceiptRepository(),
            verifications = InMemoryVerificationRepository(),
            memories = InMemoryMemoryRepository(),
            audits = InMemoryAuditRepository(),
            connector = simulator,
            model = DeterministicRecommendationEngine(clock),
            policy = DeterministicApprovalPolicy(),
            dataAccess = DeterministicDataAccessPolicy(),
            clock = clock,
        )
    }

    @Bean
    fun connectorPort(simulator: DeterministicFulfillmentSimulator): ConnectorPort = simulator

    companion object {
        const val DEMO_TENANT = "demo-cn-001"
    }
}
