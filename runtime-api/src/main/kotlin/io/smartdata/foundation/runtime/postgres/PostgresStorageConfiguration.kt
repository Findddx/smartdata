package io.smartdata.foundation.runtime.postgres

import io.smartdata.foundation.repository.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import javax.sql.DataSource

/** The profile combination is invalid even when the storage-enable flag is absent. */
@Configuration
@Profile("postgres")
class PostgresProfileSafetyGate(environment:Environment) {
    init { require(!environment.acceptsProfiles(Profiles.of("local"))) { "postgres持久化与本地演示身份不能同时启用" } }
}

/**
 * Storage capability only. It deliberately supplies no simulated connector,
 * permissive policy, demo identity, model or production FoundationKernel.
 */
@Configuration
@Profile("postgres")
@ConditionalOnProperty(prefix="smartdata.persistence",name=["enabled"],havingValue="true")
class PostgresStorageConfiguration {
    @Bean
    fun postgresRepositories(dataSource:DataSource,environment:Environment,
        @Value("\${smartdata.persistence.query-timeout-seconds:5}") timeout:Int):PostgresRepositories {
        require(!environment.acceptsProfiles(Profiles.of("local"))) { "postgres持久化与本地演示身份不能同时启用" }
        return PostgresRepositories(dataSource,queryTimeoutSeconds=timeout)
    }
    @Bean fun repositoryUnitOfWork(repositories:PostgresRepositories):RepositoryUnitOfWork=repositories.unitOfWork
    @Bean fun caseRepository(repositories:PostgresRepositories):CaseRepository=repositories.cases
    @Bean fun recommendationRepository(repositories:PostgresRepositories):RecommendationRepository=repositories.recommendations
    @Bean fun actionRequestRepository(repositories:PostgresRepositories):ActionRequestRepository=repositories.actions
    @Bean fun actionReceiptRepository(repositories:PostgresRepositories):ActionReceiptRepository=repositories.receipts
    @Bean fun verificationRepository(repositories:PostgresRepositories):VerificationRepository=repositories.verifications
    @Bean fun memoryRepository(repositories:PostgresRepositories):MemoryRepository=repositories.memories
    @Bean fun auditRepository(repositories:PostgresRepositories):AuditRepository=repositories.audits
}
