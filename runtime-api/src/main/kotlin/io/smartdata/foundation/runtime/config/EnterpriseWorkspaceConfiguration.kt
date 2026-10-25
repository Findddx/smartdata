package io.smartdata.foundation.runtime.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.smartdata.foundation.runtime.api.CommandIdempotencyGate
import io.smartdata.foundation.runtime.postgres.PostgresRepositories
import io.smartdata.foundation.runtime.workspace.DeterministicAssistantReasoningPort
import io.smartdata.foundation.runtime.workspace.JsonSnapshotSource
import io.smartdata.foundation.runtime.workspace.SourceKind
import io.smartdata.foundation.runtime.workspace.WorkspaceCapabilities
import io.smartdata.foundation.runtime.workspace.WorkspaceService
import io.smartdata.foundation.runtime.workspace.WorkspaceTransactionAccessPort
import io.smartdata.foundation.runtime.workspace.postgres.PostgresWorkspaceStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource
import java.time.Clock
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

/** Explicit enterprise assembly: no local identity, file store, model export or source writes. */
@Configuration
@Profile("enterprise")
class EnterpriseWorkspaceConfiguration {
    @Bean fun enterpriseClock(): Clock = Clock.systemUTC()

    @Bean(destroyMethod = "close")
    @Primary
    fun dataSource(environment: Environment): HikariDataSource = pool(environment, "spring.datasource", "smartdata-business", 12)

    @Bean(name = ["enterpriseSessionDataSource"], destroyMethod = "close")
    @SpringSessionDataSource
    fun enterpriseSessionDataSource(environment: Environment): HikariDataSource {
        require(environment.getRequiredProperty("smartdata.session.datasource.username") !=
            environment.getRequiredProperty("spring.datasource.username")) { "会话与业务运行账号必须分离" }
        return pool(environment, "smartdata.session.datasource", "smartdata-session", 4)
    }

    @Bean
    fun enterpriseWorkspaceStore(repositories: PostgresRepositories, access: WorkspaceTransactionAccessPort, clock: Clock) =
        PostgresWorkspaceStore(repositories.unitOfWork, access, clock)

    @Bean
    fun workspaceService(store: PostgresWorkspaceStore, clock: Clock) = WorkspaceService(store,
        listOf(JsonSnapshotSource()), clock,
        WorkspaceCapabilities(sourceKinds = listOf(SourceKind.JSON_SNAPSHOT)), DeterministicAssistantReasoningPort())

    /** Atomic, authorized command replay belongs to the repository, never to a cached HTTP response. */
    @Bean
    fun enterpriseCommandBoundary(): CommandIdempotencyGate = object : CommandIdempotencyGate {
        override fun <T : Any> execute(tenantId: String, key: String, fingerprint: String, revalidate: (T) -> Unit, operation: () -> T): T {
            require(tenantId.isNotBlank() && key.length in 8..128 && fingerprint.startsWith("v2:")) { "命令边界参数无效" }
            return operation().also(revalidate)
        }
    }

    private fun pool(environment: Environment, prefix: String, name: String, maximum: Int): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = environment.getRequiredProperty("$prefix.url")
            validateDatabaseUrl(jdbcUrl)
            username = environment.getRequiredProperty("$prefix.username")
            password = environment.getRequiredProperty("$prefix.password")
            maximumPoolSize = environment.getProperty("$prefix.maximum-pool-size", Int::class.java, maximum).also { require(it in 1..50) }
            minimumIdle = 0
            connectionTimeout = 5_000
            validationTimeout = 2_000
            initializationFailTimeout = 10_000
            poolName = name
        }
        return HikariDataSource(config)
    }

    internal fun validateDatabaseUrl(value: String) {
        require(value.startsWith("jdbc:postgresql://")) { "企业数据面只接受明确主机的 PostgreSQL JDBC 地址" }
        val address = URI(value.removePrefix("jdbc:"))
        require(!address.host.isNullOrBlank() && address.rawUserInfo == null && address.rawFragment == null) { "数据库地址不能携带用户信息或片段" }
        val parameters = address.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map {
            val pair = it.split('=', limit = 2)
            require(pair.size == 2) { "数据库连接参数无效" }
            URLDecoder.decode(pair[0], UTF_8).lowercase() to URLDecoder.decode(pair[1], UTF_8)
        }
        require(parameters.map { it.first }.distinct().size == parameters.size && parameters.none { it.first in setOf("user", "password", "sslpassword") }) {
            "数据库凭据必须通过受控配置提供，连接参数不能重复"
        }
        val loopback = address.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        require(loopback || parameters.any { it.first == "sslmode" && it.second == "verify-full" }) {
            "远程企业数据库必须启用 sslmode=verify-full 校验证书和主机"
        }
    }
}
