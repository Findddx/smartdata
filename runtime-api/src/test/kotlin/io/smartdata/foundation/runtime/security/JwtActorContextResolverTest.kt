package io.smartdata.foundation.runtime.security

import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.service.AuthorizationDeniedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class JwtActorContextResolverTest {
    private val resolver = JwtActorContextResolver(
        "https://identity.example.test/realms/smartdata",
        "smartdata-runtime",
        "smartdata-console",
    )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `derives tenant actor and allowlisted roles from verified token`() {
        authenticate(Jwt.withTokenValue("verified-token")
            .header("alg", "RS256")
            .issuer("https://identity.example.test/realms/smartdata")
            .audience(listOf("smartdata-runtime"))
            .subject("user-42")
            .claim("tenant_id", "tenant-cn-01")
            .claim("name", "测试审批人")
            .claim("azp", "smartdata-console")
            .claim("smartdata_roles", listOf("WAREHOUSE_APPROVER", "unrecognized-role"))
            .build())

        val actor = resolver.resolve(MockHttpServletRequest().apply {
            addHeader("X-Tenant-Id", "attacker-tenant")
        })
        assertThat(actor.id).isEqualTo("user-42")
        assertThat(actor.tenantId).isEqualTo("tenant-cn-01")
        assertThat(actor.roles).containsExactly(BusinessRole.WAREHOUSE_APPROVER)
    }

    @Test
    fun `rejects token without valid tenant claim`() {
        authenticate(Jwt.withTokenValue("verified-token")
            .header("alg", "RS256")
            .issuer("https://identity.example.test/realms/smartdata")
            .audience(listOf("smartdata-runtime"))
            .subject("user-42")
            .claim("azp", "smartdata-console")
            .claim("smartdata_roles", listOf("ADMIN"))
            .build())
        assertThatThrownBy { resolver.resolve(MockHttpServletRequest()) }
            .isInstanceOf(AuthorizationDeniedException::class.java)
            .hasMessageContaining("tenant_id")
    }

    @Test
    fun `rejects a valid token for another audience and ignores realm admin`() {
        authenticate(Jwt.withTokenValue("verified-token")
            .header("alg", "RS256")
            .issuer("https://identity.example.test/realms/smartdata")
            .audience(listOf("another-api"))
            .subject("user-42")
            .claim("tenant_id", "tenant-cn-01")
            .claim("azp", "smartdata-console")
            .claim("realm_access", mapOf("roles" to listOf("ADMIN")))
            .build())

        assertThatThrownBy { resolver.resolve(MockHttpServletRequest()) }
            .isInstanceOf(AuthorizationDeniedException::class.java)
            .hasMessageContaining("audience")
    }

    @Test
    fun `resource server validator rejects wrong audience or authorized party before routing`() {
        val validator = SmartDataJwtValidator("smartdata-runtime", "smartdata-console")
        val valid = token(audience = "smartdata-runtime", clientId = "smartdata-console")
        val wrongAudience = token(audience = "another-api", clientId = "smartdata-console")
        val wrongClient = token(audience = "smartdata-runtime", clientId = "another-client")

        assertThat(validator.validate(valid).hasErrors()).isFalse()
        assertThat(validator.validate(wrongAudience).hasErrors()).isTrue()
        assertThat(validator.validate(wrongClient).hasErrors()).isTrue()
    }

    private fun token(audience: String, clientId: String): Jwt = Jwt.withTokenValue("verified-token")
        .header("alg", "RS256")
        .issuer("https://identity.example.test/realms/smartdata")
        .audience(listOf(audience))
        .subject("user-42")
        .claim("azp", clientId)
        .build()

    private fun authenticate(jwt: Jwt) {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }
}
