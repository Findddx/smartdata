package io.smartdata.foundation.runtime.security.enterprise

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.support.GenericConversionService
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.jackson.SecurityJacksonModules
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.session.config.SessionRepositoryCustomizer
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.session.jdbc.config.annotation.SpringSessionTransactionManager
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsontype.TypeSerializer
import tools.jackson.databind.module.SimpleModule
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

@Configuration
@Profile("enterprise")
@EnableJdbcHttpSession(tableName="smartdata_session.spring_session")
class EnterpriseSessionConfiguration {
    @Bean
    @SpringSessionTransactionManager
    fun enterpriseSessionTransactionManager(@Qualifier("enterpriseSessionDataSource") dataSource:DataSource)=DataSourceTransactionManager(dataSource)

    @Bean
    fun enterpriseSessionCustomizer(properties:EnterpriseProperties):SessionRepositoryCustomizer<JdbcIndexedSessionRepository> =
        SessionRepositoryCustomizer { repository -> repository.setDefaultMaxInactiveInterval(Duration.ofMinutes(properties.sessionIdleMinutes.toLong())) }

    @Bean(name=["cookieSerializer"])
    fun cookieSerializer():CookieSerializer=DefaultCookieSerializer().also {
        it.setCookieName(COOKIE_NAME);it.setCookiePath("/");it.setUseSecureCookie(true)
        it.setUseHttpOnlyCookie(true);it.setSameSite("Lax")
    }

    @Bean
    fun enterpriseSessionCodec(@Value("\${smartdata.session.encryption-key}") encodedKey:String):EnterpriseSessionJsonCodec = EnterpriseSessionJsonCodec(encodedKey)

    @Bean(name=["springSessionConversionService"])
    fun sessionConversionService(codec:EnterpriseSessionJsonCodec):ConversionService=GenericConversionService().also {
        it.addConverter(Any::class.java,ByteArray::class.java,Converter<Any,ByteArray> { value -> codec.encode(value) })
        it.addConverter(ByteArray::class.java,Any::class.java,Converter<ByteArray,Any> { value -> codec.decode(value) })
    }

    @Bean
    fun enterpriseSessionRoleGuard(@Qualifier("enterpriseSessionDataSource") dataSource:DataSource,
        @Qualifier("dataSource") businessDataSource:DataSource)=EnterpriseSessionRoleGuard(dataSource,businessDataSource)

    @Bean
    fun invalidEnterpriseSessionBoundary():FilterRegistrationBean<InvalidEnterpriseSessionFilter> =
        FilterRegistrationBean(InvalidEnterpriseSessionFilter()).also { it.order=Ordered.HIGHEST_PRECEDENCE+40 }

    companion object { const val COOKIE_NAME="__Host-SMARTDATA_SESSION" }
}

/** Framework allowlisted JSON, authenticated-encrypted at rest; never ObjectInputStream. */
class EnterpriseSessionJsonCodec(encodedKey:String) {
    private val key:SecretKeySpec
    private val mapper=JsonMapper.builder().addModules(SecurityJacksonModules.getModules(javaClass.classLoader))
        .addModule(SimpleModule("enterprise-session-url-text").addSerializer(URL::class.java,SessionUrlTextSerializer())).build()
    private val random=SecureRandom()

    init {
        val raw=try { Base64.getDecoder().decode(encodedKey) } catch(_:IllegalArgumentException) { throw IllegalStateException("Session encryption key must contain 32 Base64-encoded bytes") }
        require(raw.size==32) { "Session encryption key must contain 32 Base64-encoded bytes" }
        key=SecretKeySpec(raw,"AES")
    }
    fun encode(value:Any):ByteArray {
        allow(value)
        val json=mapper.writeValueAsBytes(value)
        if(json.size>MAX_BYTES) throw InvalidEnterpriseSessionException()
        val nonce=ByteArray(12).also(random::nextBytes)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,nonce));cipher.updateAAD(MAGIC)
        return MAGIC+nonce+cipher.doFinal(json)
    }
    fun decode(value:ByteArray):Any {
        try {
            if(value.size !in 33..MAX_BYTES+32 || !value.copyOfRange(0,4).contentEquals(MAGIC)) throw InvalidEnterpriseSessionException()
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,value.copyOfRange(4,16)));cipher.updateAAD(MAGIC)
            val result=mapper.readValue(cipher.doFinal(value.copyOfRange(16,value.size)),Any::class.java)
            allow(result)
            return result
        } catch(failure:Exception) {
            if(failure is InvalidEnterpriseSessionException) throw failure
            throw InvalidEnterpriseSessionException(failure)
        }
    }
    private fun allow(value:Any) {
        if(value.javaClass !in ROOT_TYPES || (value is String && value.length>200)) throw InvalidEnterpriseSessionException()
        if(value is SecurityContextImpl) {
            val authentication=value.authentication
            if(authentication!=null && (authentication !is OAuth2AuthenticationToken ||
                authentication.authorizedClientRegistrationId!="enterprise" || authentication.principal.javaClass!=DefaultOidcUser::class.java)) throw InvalidEnterpriseSessionException()
        }
    }
    companion object {
        private const val MAX_BYTES=262144
        private val MAGIC="SDJ1".toByteArray(StandardCharsets.US_ASCII)
        private val ROOT_TYPES:Set<Class<*>> = setOf(SecurityContextImpl::class.java,OAuth2AuthorizationRequest::class.java,
            String::class.java,java.lang.Long::class.java)
    }
}

/** Persist verified OIDC URL claims as JSON text; URL type IDs remain forbidden on read. */
private class SessionUrlTextSerializer:ValueSerializer<URL>() {
    override fun serialize(value:URL,generator:JsonGenerator,context:SerializationContext) {
        generator.writeString(value.toExternalForm())
    }
    override fun serializeWithType(value:URL,generator:JsonGenerator,context:SerializationContext,typeSerializer:TypeSerializer) {
        serialize(value,generator,context)
    }
}

class InvalidEnterpriseSessionException(cause:Throwable?=null):RuntimeException("Stored enterprise session is invalid",cause)

class InvalidEnterpriseSessionFilter:OncePerRequestFilter() {
    override fun doFilterInternal(request:HttpServletRequest,response:HttpServletResponse,filterChain:FilterChain) {
        try { filterChain.doFilter(request,response) } catch(error:Exception) {
            val sessionFailure=generateSequence<Throwable>(error) { it.cause }.take(12).any { it is InvalidEnterpriseSessionException }
            if(!sessionFailure) throw error
            SecurityContextHolder.clearContext()
            if(!response.isCommitted) {
                response.setHeader("Set-Cookie",EnterpriseSessionConfiguration.COOKIE_NAME+"=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax")
                EnterpriseSecurityConfiguration.problem(response,401,"企业会话无效，请重新登录")
            }
        }
    }
}

/** Session credentials cannot own/migrate tables or read the business data plane. */
class EnterpriseSessionRoleGuard(dataSource:DataSource,businessDataSource:DataSource) {
    init {
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("""
                select r.rolsuper,r.rolbypassrls,r.rolcreaterole,r.rolcreatedb,
                  has_schema_privilege(current_user,'public','CREATE') as can_create,
                  exists(select 1 from pg_roles p where pg_has_role(p.oid,'MEMBER') and
                    (p.rolsuper or p.rolbypassrls or p.rolcreaterole or p.rolcreatedb or has_schema_privilege(p.oid,'public','CREATE'))) as can_escalate,
                  exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace join pg_roles p on pg_has_role(p.oid,'MEMBER') where n.nspname='public' and c.relkind in ('r','p') and
                    (has_table_privilege(p.oid,c.oid,'SELECT') or has_table_privilege(p.oid,c.oid,'INSERT') or has_table_privilege(p.oid,c.oid,'UPDATE') or has_table_privilege(p.oid,c.oid,'DELETE') or has_table_privilege(p.oid,c.oid,'TRUNCATE'))) as business_access
                from pg_roles r where r.rolname=current_user
            """.trimIndent()).use { row ->
                require(row.next() && (1..7).all { !row.getBoolean(it) }) { "Session runtime role must be isolated from schema ownership, privilege escalation and business tables" }
            }
            statement.executeQuery("""
                select c.relname,pg_has_role(c.relowner,'MEMBER') as owner_member,
                  has_table_privilege(current_user,c.oid,'TRUNCATE') as can_truncate,
                  has_table_privilege(current_user,c.oid,'SELECT') and has_table_privilege(current_user,c.oid,'INSERT') and
                    has_table_privilege(current_user,c.oid,'UPDATE') and has_table_privilege(current_user,c.oid,'DELETE') as can_use
                from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='smartdata_session' and c.relname in ('spring_session','spring_session_attributes') and c.relkind='r'
            """.trimIndent()).use { rows ->
                var count=0
                while(rows.next()) { count++;require(!rows.getBoolean(2)&&!rows.getBoolean(3)&&rows.getBoolean(4)) { "Session runtime role requires only session-table DML permissions" } }
                require(count==2) { "Session migration must complete before runtime startup" }
            }
        } }
        businessDataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("""
                select exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
                  join pg_roles p on pg_has_role(p.oid,'MEMBER')
                  where n.nspname='smartdata_session' and c.relkind in ('r','p') and
                  (has_table_privilege(p.oid,c.oid,'SELECT') or has_table_privilege(p.oid,c.oid,'INSERT') or
                   has_table_privilege(p.oid,c.oid,'UPDATE') or has_table_privilege(p.oid,c.oid,'DELETE') or
                   has_table_privilege(p.oid,c.oid,'TRUNCATE')))
            """.trimIndent()).use { row ->
                require(row.next()&&!row.getBoolean(1)) { "Business runtime role and every role it can assume must be isolated from session storage" }
            }
        } }
    }
}
