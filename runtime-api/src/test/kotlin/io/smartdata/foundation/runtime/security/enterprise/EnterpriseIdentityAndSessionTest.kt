package io.smartdata.foundation.runtime.security.enterprise

import io.smartdata.foundation.service.AuthorizationDeniedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class EnterpriseIdentityAndSessionTest {
    private val now=Instant.parse("2026-10-25T12:00:00Z")
    private val key=Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val codec=EnterpriseSessionJsonCodec(key)

    @AfterEach fun clear()=SecurityContextHolder.clearContext()

    @Test fun `stable accounts namespace identical subjects by issuer and preserve tuple boundaries`() {
        val a=EnterpriseAccounts.identity("https://one.example.test","same-user","甲")
        val again=EnterpriseAccounts.identity("https://one.example.test","same-user","改名")
        val b=EnterpriseAccounts.identity("https://two.example.test","same-user","甲")
        assertThat(a.accountId).matches("acct_[0-9a-f]{64}").isEqualTo(again.accountId).isNotEqualTo(b.accountId)
        assertThat(EnterpriseAccounts.identity("https://one.example.test/a","b|c",null).accountId)
            .isNotEqualTo(EnterpriseAccounts.identity("https://one.example.test/a|b","c",null).accountId)
    }

    @Test fun `missing malformed and oversized subjects cannot become accounts`() {
        listOf(null,""," ","evil\nsubject","x".repeat(256)).forEach { subject ->
            assertThatThrownBy { EnterpriseAccounts.identity("https://one.example.test",subject,null) }.isInstanceOf(AuthorizationDeniedException::class.java)
        }
        assertThatThrownBy { EnterpriseAccounts.identity(null,"user",null) }.isInstanceOf(AuthorizationDeniedException::class.java)
        assertThat(EnterpriseAccounts.identity("https://one.example.test","user","unsafe\nname").displayName).isEqualTo("企业成员")
    }

    @Test fun `deployment properties require HTTPS tenant and exact bootstrap IDs`() {
        val properties=EnterpriseProperties(true,"tenant-one","示例企业","https://one.example.test","enterprise-console","smartdata-runtime","https://console.example.test")
        properties.validate()
        properties.issuerUri="http://127.0.0.1:10000"
        assertThatThrownBy { properties.validate() }.isInstanceOf(IllegalArgumentException::class.java)
        properties.issuerUri="https://one.example.test"
        properties.bootstrapOwners=listOf("email@example.test")
        assertThatThrownBy { properties.validate() }.isInstanceOf(IllegalArgumentException::class.java)
        properties.bootstrapOwners=emptyList()
        listOf("https://console.example.test/path","https://console.example.test:0","https://console.example.test:65536","https://console.example.test\n").forEach { value ->
            properties.externalBaseUrl=value
            assertThatThrownBy { properties.validate() }.isInstanceOf(IllegalArgumentException::class.java)
        }
        properties.externalBaseUrl="https://console.example.test:8443/"
        properties.issuerUri="https://one.example.test/realms/enterprise"
        properties.validate()
    }

    @Test fun `session encryption uses fresh nonces and rejects tampering and another key`() {
        val a=codec.encode(now.toString()); val b=codec.encode(now.toString())
        assertThat(a).isNotEqualTo(b)
        assertThat(codec.decode(a)).isEqualTo(now.toString())
        assertThat(String(a,Charsets.ISO_8859_1)).doesNotContain(now.toString())
        val tampered=a.copyOf().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
        assertThatThrownBy { codec.decode(tampered) }.isInstanceOf(InvalidEnterpriseSessionException::class.java)
        val other=EnterpriseSessionJsonCodec(Base64.getEncoder().encodeToString(ByteArray(32) { (it+1).toByte() }))
        assertThatThrownBy { other.decode(a) }.isInstanceOf(InvalidEnterpriseSessionException::class.java)
    }

    @Test fun `framework OIDC security context survives encrypted JSON without client roles`() {
        val original=SecurityContextImpl(authentication())
        val bytes=codec.encode(original)
        assertThat(String(bytes,Charsets.ISO_8859_1)).doesNotContain("id-token-fixture")
        val restored=codec.decode(bytes) as SecurityContextImpl
        val authentication=restored.authentication as OAuth2AuthenticationToken
        assertThat(authentication.authorizedClientRegistrationId).isEqualTo("enterprise")
        assertThat((authentication.principal as DefaultOidcUser).subject).isEqualTo("user-42")
        assertThat(authentication.authorities.map { it.authority }).containsExactly("OIDC_USER")
    }

    @Test fun `authorization request keeps PKCE verifier only inside encrypted storage`() {
        val request=OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://one.example.test/authorize")
            .clientId("enterprise-console").redirectUri("https://console.example.test/login/oauth2/code/enterprise")
            .scopes(setOf("openid","profile")).state("fixture-state").attributes { it["code_verifier"]="private-fixture-pkce-verifier" }.build()
        val bytes=codec.encode(request)
        assertThat(String(bytes,Charsets.ISO_8859_1)).doesNotContain("private-fixture-pkce-verifier")
        val restored=codec.decode(bytes) as OAuth2AuthorizationRequest
        assertThat(restored.getAttribute<String>("code_verifier")).isEqualTo("private-fixture-pkce-verifier")
        assertThat(restored.redirectUri).isEqualTo(request.redirectUri)
    }

    @Test fun `native Java streams arbitrary DTOs and oversized attributes are refused`() {
        assertThatThrownBy { codec.decode(byteArrayOf(0xac.toByte(),0xed.toByte(),0,5)) }.isInstanceOf(InvalidEnterpriseSessionException::class.java)
        assertThatThrownBy { codec.encode(mapOf("businessRows" to listOf("private row"))) }.isInstanceOf(InvalidEnterpriseSessionException::class.java)
        assertThatThrownBy { codec.encode("x".repeat(201)) }.isInstanceOf(InvalidEnterpriseSessionException::class.java)
        assertThatThrownBy { EnterpriseSessionJsonCodec("not a base64 key") }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `absolute lifetime refuses old missing and future session timestamps`() {
        listOf<String?>(null,now.minusSeconds(8*3600).toString(),now.plusSeconds(61).toString()).forEach { timestamp ->
            SecurityContextHolder.getContext().authentication=authentication()
            val request=MockHttpServletRequest()
            val session=requireNotNull(request.getSession(true))
            timestamp?.let { session.setAttribute(EnterpriseSecurityConfiguration.AUTHENTICATED_AT,it) }
            val response=MockHttpServletResponse()
            EnterpriseAbsoluteSessionFilter(EnterpriseProperties(sessionMaxHours=8),Clock.fixed(now,ZoneOffset.UTC)).doFilter(request,response,MockFilterChain())
            assertThat(response.status).isEqualTo(401)
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }
    }

    @Test fun `session still within absolute lifetime reaches the protected handler`() {
        SecurityContextHolder.getContext().authentication=authentication()
        val request=MockHttpServletRequest()
        requireNotNull(request.getSession(true)).setAttribute(EnterpriseSecurityConfiguration.AUTHENTICATED_AT,now.minusSeconds(60).toString())
        val chain=MockFilterChain()
        EnterpriseAbsoluteSessionFilter(EnterpriseProperties(sessionMaxHours=8),Clock.fixed(now,ZoneOffset.UTC)).doFilter(request,MockHttpServletResponse(),chain)
        assertThat(chain.request).isSameAs(request)
    }

    private fun authentication():OAuth2AuthenticationToken {
        val claims=mapOf("iss" to "https://one.example.test","sub" to "user-42","aud" to listOf("enterprise-console"))
        val id=OidcIdToken("id-token-fixture",now,now.plusSeconds(300),claims)
        val account=EnterpriseAccounts.identity("https://one.example.test","user-42","测试成员")
        val user=DefaultOidcUser(listOf(SimpleGrantedAuthority("OIDC_USER")),id,
            OidcUserInfo(mapOf("sub" to "user-42","smartdata_account_id" to account.accountId)),"smartdata_account_id")
        return OAuth2AuthenticationToken(user,user.authorities,"enterprise")
    }
}
