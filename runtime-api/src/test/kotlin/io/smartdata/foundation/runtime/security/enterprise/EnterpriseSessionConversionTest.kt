package io.smartdata.foundation.runtime.security.enterprise

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.convert.TypeDescriptor
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority
import org.springframework.security.web.authentication.WebAuthenticationDetails
import tools.jackson.databind.exc.InvalidTypeIdException
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Matches JDBC's explicit Object -> byte[] -> Object descriptors, not just the codec API. */
class EnterpriseSessionConversionTest {
    private val now=Instant.parse("2026-10-25T12:00:00Z")
    private val codec=EnterpriseSessionJsonCodec(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
    private val conversion=EnterpriseSessionConfiguration().sessionConversionService(codec)

    @Test fun `real OIDC authorities claims and web details survive the codec`() {
        val original=context()
        assertContext(original,codec.decode(codec.encode(original)))
    }

    @Test fun `JDBC conversion preserves timestamp strings rather than bypassing decoding`() {
        val value=now.toString()
        val encoded=serialize(value)
        assertThat(encoded.copyOfRange(0,4)).isEqualTo("SDJ1".toByteArray())
        assertThat(String(encoded,Charsets.ISO_8859_1)).doesNotContain(value)
        val restored=deserialize(encoded)
        assertThat(restored).isInstanceOf(String::class.java).isEqualTo(value)
        assertThat(Instant.parse(restored as String)).isEqualTo(now)
    }

    @Test fun `JDBC conversion preserves complete login context rather than byte array or generic map`() {
        val original=context()
        val encoded=serialize(original)
        assertThat(encoded.copyOfRange(0,4)).isEqualTo("SDJ1".toByteArray())
        assertThat(String(encoded,Charsets.ISO_8859_1)).doesNotContain("real-shape-id-token-fixture")
        assertContext(original,deserialize(encoded))
    }

    @Test fun `URL claims become text without URL hashing DNS lookup or connection`() {
        val networkOperations=AtomicInteger()
        val issuer=URL(null,"https://no-network.invalid/issuer",object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection {
                networkOperations.incrementAndGet();throw AssertionError("Session serialization must not open a URL")
            }
            override fun getHostAddress(url:URL):InetAddress? {
                networkOperations.incrementAndGet();throw AssertionError("Session serialization must not resolve DNS")
            }
            override fun hashCode(url:URL):Int {
                networkOperations.incrementAndGet();throw AssertionError("Session serialization must not hash a URL")
            }
        })
        val restored=deserialize(serialize(context(issuer))) as SecurityContextImpl
        val authentication=restored.authentication as OAuth2AuthenticationToken
        val user=authentication.principal as DefaultOidcUser
        assertThat(user.idToken.getClaim<Any>("iss")).isInstanceOf(String::class.java).isEqualTo(issuer.toExternalForm())
        val authority=authentication.authorities.filterIsInstance<OidcUserAuthority>().single()
        assertThat(authority.idToken.getClaim<Any>("iss")).isInstanceOf(String::class.java).isEqualTo(issuer.toExternalForm())
        assertThat(networkOperations.get()).isZero()
    }

    @Test fun `forged URL Runtime and arbitrary class type IDs remain denied by framework validator`() {
        listOf("[\"java.net.URL\",\"https://no-network.invalid/issuer\"]",
            "[\"java.lang.Runtime\",{}]","[\"java.io.File\",\"synthetic-file\"]").forEach { payload ->
            assertThatThrownBy { codec.decode(forgedEnvelope(payload)) }
                .isInstanceOf(InvalidEnterpriseSessionException::class.java)
                .hasRootCauseInstanceOf(InvalidTypeIdException::class.java)
        }
    }

    private fun serialize(value:Any)=requireNotNull(conversion.convert(value,TypeDescriptor.valueOf(Any::class.java),
        TypeDescriptor.valueOf(ByteArray::class.java))) as ByteArray

    private fun deserialize(value:ByteArray)=requireNotNull(conversion.convert(value,TypeDescriptor.valueOf(ByteArray::class.java),
        TypeDescriptor.valueOf(Any::class.java)))

    private fun context(issuer:URL=URI("https://one.example.test").toURL()):SecurityContextImpl {
        val claims=linkedMapOf<String,Any>("iss" to issuer,"sub" to "user-42",
            "aud" to listOf("enterprise-console"),"iat" to now,"exp" to now.plusSeconds(300),"nonce" to "synthetic-nonce")
        val id=OidcIdToken("real-shape-id-token-fixture",now,now.plusSeconds(300),claims)
        val account=EnterpriseAccounts.identity("https://one.example.test","user-42","测试成员")
        val info=OidcUserInfo(mapOf("sub" to "user-42","name" to "测试成员","smartdata_account_id" to account.accountId))
        val authorities=listOf<GrantedAuthority>(OidcUserAuthority(id,info),SimpleGrantedAuthority("SCOPE_openid"),SimpleGrantedAuthority("SCOPE_profile"))
        val user=DefaultOidcUser(authorities,id,info,"smartdata_account_id")
        val authentication=OAuth2AuthenticationToken(user,authorities,"enterprise")
        val request=MockHttpServletRequest().also {
            it.remoteAddr="192.0.2.10"
            requireNotNull(it.getSession(true))
        }
        authentication.details=WebAuthenticationDetails(request)
        return SecurityContextImpl(authentication)
    }

    private fun forgedEnvelope(payload:String):ByteArray {
        val magic="SDJ1".toByteArray()
        val nonce=ByteArray(12) { it.toByte() }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(ByteArray(32) { it.toByte() },"AES"),GCMParameterSpec(128,nonce))
        cipher.updateAAD(magic)
        return magic+nonce+cipher.doFinal(payload.toByteArray())
    }

    private fun assertContext(original:SecurityContextImpl,restored:Any) {
        assertThat(restored).isInstanceOf(SecurityContextImpl::class.java)
        val before=original.authentication as OAuth2AuthenticationToken
        val after=(restored as SecurityContextImpl).authentication as OAuth2AuthenticationToken
        assertThat(after.isAuthenticated).isTrue()
        assertThat(after.authorizedClientRegistrationId).isEqualTo("enterprise")
        assertThat(after.name).isEqualTo(before.name)
        assertThat(after.authorities.map { it.authority }).containsExactlyInAnyOrder("OIDC_USER","SCOPE_openid","SCOPE_profile")
        assertThat(after.authorities.filterIsInstance<OidcUserAuthority>()).hasSize(1)
        val user=after.principal as DefaultOidcUser
        assertThat(user.subject).isEqualTo("user-42")
        assertThat(user.idToken.getClaim<Any>("iat")).isEqualTo(now)
        assertThat(user.idToken.getClaim<Any>("exp")).isEqualTo(now.plusSeconds(300))
        assertThat(user.idToken.issuer.toString()).isEqualTo("https://one.example.test")
        val beforeDetails=before.details as WebAuthenticationDetails
        val afterDetails=after.details as WebAuthenticationDetails
        assertThat(afterDetails.remoteAddress).isEqualTo(beforeDetails.remoteAddress)
        assertThat(afterDetails.sessionId).isEqualTo(beforeDetails.sessionId)
    }
}
