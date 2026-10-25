package io.smartdata.foundation.runtime.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset

@Configuration
@Profile("local")
class LocalSecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }
        http.cors { it.disable() }
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        http.authorizeHttpRequests { it.anyRequest().permitAll() }
        http.headers { headers ->
            headers.frameOptions { it.deny() }
            headers.contentTypeOptions { }
            headers.cacheControl { }
            headers.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'") }
        }
        return http.build()
    }

}

@Configuration
@Profile("!local")
class ProductionSecurityConfiguration(
    @param:Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private val requiredIssuer: String,
    @param:Value("\${smartdata.security.jwt.audience}")
    private val requiredAudience: String,
    @param:Value("\${smartdata.security.jwt.client-id}")
    private val requiredClientId: String,
) {
    @Bean
    fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withIssuerLocation(requiredIssuer).build().also { decoder ->
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(
            JwtValidators.createDefaultWithIssuer(requiredIssuer),
            SmartDataJwtValidator(requiredAudience, requiredClientId),
        ))
    }

    @Bean
    fun productionSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }
        http.cors { it.disable() }
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        http.authorizeHttpRequests {
            it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().authenticated()
        }
        http.oauth2ResourceServer { it.jwt { } }
        http.headers { headers ->
            headers.frameOptions { it.deny() }
            headers.contentTypeOptions { }
            headers.cacheControl { }
            headers.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'") }
        }
        return http.build()
    }
}

internal class SmartDataJwtValidator(
    private val requiredAudience: String,
    private val requiredClientId: String,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val authorizedParty = token.getClaimAsString("azp") ?: token.getClaimAsString("client_id")
        return if (requiredAudience in token.audience.orEmpty() && authorizedParty == requiredClientId) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error(
                "invalid_token",
                "JWT audience or authorized party is not valid for SmartData",
                null,
            ))
        }
    }
}

@Configuration
class RequestBoundaryConfiguration {
    @Bean
    fun boundaryFilterRegistration(): FilterRegistrationBean<BoundaryFilter> =
        FilterRegistrationBean(BoundaryFilter()).also {
            it.order = Ordered.HIGHEST_PRECEDENCE
        }
}

class PayloadTooLargeException : RuntimeException("请求正文超过 256 KiB 限制")

class BoundaryFilter(
    private val maxBodyBytes: Long = 256 * 1024,
) : OncePerRequestFilter() {
    private val forbiddenIdentityHeaders = listOf("X-Tenant-Id", "X-Roles", "X-Actor-Id")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        addHeaders(response)
        if (forbiddenIdentityHeaders.any { request.getHeader(it) != null }) {
            writeProblem(response, 400, "不可信身份头", "租户和角色只能由服务端认证上下文解析")
            return
        }
        if (request.contentLengthLong > maxBodyBytes) {
            writeProblem(response, 413, "请求过大", "请求正文不得超过 256 KiB")
            return
        }
        try {
            filterChain.doFilter(LimitedBodyRequest(request, maxBodyBytes), response)
        } catch (_: PayloadTooLargeException) {
            if (!response.isCommitted) writeProblem(response, 413, "请求过大", "请求正文不得超过 256 KiB")
        }
    }

    private fun addHeaders(response: HttpServletResponse) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        response.setHeader("Cache-Control", "no-store")
    }

    private fun writeProblem(response: HttpServletResponse, status: Int, title: String, detail: String) {
        response.status = status
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = "application/problem+json"
        response.writer.write("{\"title\":\"$title\",\"status\":$status,\"detail\":\"$detail\"}")
    }
}

private class LimitedBodyRequest(
    request: HttpServletRequest,
    private val limit: Long,
) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream = LimitedServletInputStream(super.getInputStream(), limit)

    override fun getReader(): BufferedReader = BufferedReader(
        InputStreamReader(inputStream, characterEncoding?.let(Charset::forName) ?: Charsets.UTF_8),
    )
}

private class LimitedServletInputStream(
    private val delegate: ServletInputStream,
    private val limit: Long,
) : ServletInputStream() {
    private var consumed = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val read = delegate.read(bytes, offset, length)
        if (read > 0) account(read.toLong())
        return read
    }

    private fun account(amount: Long) {
        consumed += amount
        if (consumed > limit) throw PayloadTooLargeException()
    }

    override fun isFinished(): Boolean = delegate.isFinished
    override fun isReady(): Boolean = delegate.isReady
    override fun setReadListener(readListener: ReadListener?) = delegate.setReadListener(readListener)
}
