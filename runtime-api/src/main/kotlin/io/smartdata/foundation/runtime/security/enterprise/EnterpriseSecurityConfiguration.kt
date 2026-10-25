package io.smartdata.foundation.runtime.security.enterprise

import io.smartdata.foundation.runtime.postgres.PostgresRepositories
import io.smartdata.foundation.runtime.security.ActorContextResolver
import io.smartdata.foundation.runtime.workspace.WorkspaceTransactionAccessPort
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI
import java.time.Clock
import java.time.Instant

@Configuration
@Profile("enterprise")
@EnableConfigurationProperties(EnterpriseProperties::class)
class EnterpriseSecurityConfiguration {
    @Bean
    fun enterpriseDirectoryService(repositories:PostgresRepositories,properties:EnterpriseProperties,
        @Qualifier("enterpriseClock") clock:Clock):EnterpriseDirectoryService {
        properties.validate()
        return EnterpriseDirectoryService(repositories,properties,clock)
    }
    @Bean fun enterprisePrincipalAccess(directory:EnterpriseDirectoryService)=EnterprisePrincipalAccess(directory)
    // The concrete access bean implements both ports; no second resolver or
    // browser-supplied Actor is registered in this profile.
    @Bean fun enterpriseWorkspaceTransactionAccess(directory:EnterpriseDirectoryService):WorkspaceTransactionAccessPort=EnterpriseWorkspaceTransactionAccess(directory)

    @Bean
    fun enterpriseJwtDecoder(properties:EnterpriseProperties):JwtDecoder {
        properties.validate()
        return NimbusJwtDecoder.withIssuerLocation(properties.issuerUri).jwsAlgorithm(SignatureAlgorithm.RS256).validateType(false).build().also {
            it.setJwtValidator(JwtValidators.createAtJwtValidator().issuer(properties.issuerUri).audience(properties.audience).clientId(properties.clientId).build())
        }
    }

    /** Explicit Authorization headers always take a stateless, bearer-only path. */
    @Bean
    @Order(1)
    fun enterpriseResourceSecurity(http:HttpSecurity,enterpriseJwtDecoder:JwtDecoder):SecurityFilterChain {
        http.securityMatcher(RequestMatcher { it.requestURI.startsWith("/api/") && it.getHeader("Authorization")!=null })
        http.csrf { it.disable() }
        http.cors { it.disable() }
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        http.requestCache { it.requestCache(NullRequestCache()) }
        http.authorizeHttpRequests { it.anyRequest().authenticated() }
        http.oauth2ResourceServer { it.jwt { jwt -> jwt.decoder(enterpriseJwtDecoder) } }
        http.exceptionHandling { it.authenticationEntryPoint { _,response,_ -> problem(response,401,"请提供有效的企业访问令牌") } }
        return http.build()
    }

    @Bean
    @Order(2)
    fun enterpriseBrowserSecurity(http:HttpSecurity,registrations:ClientRegistrationRepository,
        directory:EnterpriseDirectoryService,properties:EnterpriseProperties,
        @Qualifier("enterpriseClock") clock:Clock):SecurityFilterChain {
        properties.validate()
        val registration=registrations.findByRegistrationId("enterprise")?:error("Enterprise OIDC registration is required")
        require(registration.providerDetails.issuerUri==properties.issuerUri && registration.clientId==properties.clientId && registration.clientSecret.isNotBlank()) { "Enterprise OIDC registration does not match the fixed provider/client" }
        listOf(registration.providerDetails.authorizationUri,registration.providerDetails.tokenUri,registration.providerDetails.jwkSetUri).forEach { endpoint ->
            val uri=URI(endpoint)
            require(uri.scheme=="https" && uri.host!=null && uri.rawUserInfo==null && uri.rawFragment==null) { "OIDC endpoints require trusted HTTPS" }
        }
        val resolver=DefaultOAuth2AuthorizationRequestResolver(registrations,"/oauth2/authorization")
        resolver.setAuthorizationRequestCustomizer { builder ->
            OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder)
            builder.redirectUri(properties.externalBaseUrl.trimEnd('/')+"/login/oauth2/code/enterprise")
        }
        val delegate=OidcUserService()
        val users=OAuth2UserService<OidcUserRequest,OidcUser> { request ->
            val user=delegate.loadUser(request)
            val identity=EnterpriseAccounts.identity(user.idToken.issuer.toString(),user.subject,user.getClaimAsString("name"))
            try { directory.recordLogin(identity) } catch(failure:RuntimeException) {
                throw enterpriseIdentityFailure(failure)
            }
            val claims=LinkedHashMap(user.userInfo?.claims.orEmpty())
            claims["sub"]=user.subject
            claims["smartdata_account_id"]=identity.accountId
            DefaultOidcUser(user.authorities,user.idToken,OidcUserInfo(claims),"smartdata_account_id")
        }
        // /me returns Spring's masked attribute token. Decode the same masked
        // value from headers instead of spa()'s Angular/raw-cookie convention.
        http.csrf { it.spa().csrfTokenRequestHandler(enterpriseCsrfRequestHandler()) }
        http.cors { it.disable() }
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED).sessionFixation { fixation -> fixation.changeSessionId() } }
        http.requestCache { it.requestCache(NullRequestCache()) }
        http.authorizeHttpRequests {
            it.requestMatchers("/api/v1/me","/actuator/health","/actuator/health/**","/oauth2/**","/login/**").permitAll()
                .requestMatchers("/","/index.html","/assets/**","/art/**","/brand/**","/third-party-licenses/**","/favicon.ico").permitAll()
                .anyRequest().authenticated()
        }
        val loginFailures=EnterpriseLoginFailureDiagnostics()
        http.oauth2Login { login ->
            login.authorizedClientRepository(IdentityOnlyAuthorizedClientRepository())
            login.authorizationEndpoint { it.authorizationRequestResolver(resolver) }
            login.userInfoEndpoint { it.oidcUserService(users) }
            login.successHandler { request,response,_ ->
                request.getSession(true).setAttribute(AUTHENTICATED_AT,clock.instant().toString())
                response.sendRedirect(properties.externalBaseUrl.trimEnd('/')+"/")
            }
            login.failureHandler { _,response,failure -> loginFailures.reject(response,failure) }
        }
        // Local logout is independent of provider availability. It removes the
        // shared session; no claim is made about IdP-wide token revocation.
        http.logout { it.logoutUrl("/logout").invalidateHttpSession(true).clearAuthentication(true)
            .logoutSuccessHandler { _,response,_ -> response.status=204 } }
        http.exceptionHandling {
            it.authenticationEntryPoint { _,response,_ -> problem(response,401,"请先登录企业账号") }
            it.accessDeniedHandler { _,response,_ -> problem(response,403,"请求未通过身份或CSRF校验") }
        }
        http.addFilterBefore(EnterpriseAbsoluteSessionFilter(properties,clock),AuthorizationFilter::class.java)
        return http.build()
    }

    companion object {
        const val AUTHENTICATED_AT="smartdata.authenticatedAt"
        fun problem(response:HttpServletResponse,status:Int,detail:String) {
            response.status=status;response.characterEncoding="UTF-8";response.contentType="application/problem+json"
            response.setHeader("Cache-Control","no-store")
            response.writer.write("{\"status\":$status,\"detail\":\"$detail\"}")
        }
    }
}

internal fun enterpriseCsrfRequestHandler()=XorCsrfTokenRequestAttributeHandler().also { it.setCsrfRequestAttributeName(null) }

/** This slice authenticates people and never relays IdP access/refresh tokens. */
internal class IdentityOnlyAuthorizedClientRepository:OAuth2AuthorizedClientRepository {
    override fun <T:OAuth2AuthorizedClient> loadAuthorizedClient(clientRegistrationId:String,principal:Authentication,request:HttpServletRequest):T?=null
    override fun saveAuthorizedClient(authorizedClient:OAuth2AuthorizedClient,principal:Authentication,request:HttpServletRequest,response:HttpServletResponse) = Unit
    override fun removeAuthorizedClient(clientRegistrationId:String,principal:Authentication,request:HttpServletRequest,response:HttpServletResponse) = Unit
}

internal class EnterpriseAbsoluteSessionFilter(private val properties:EnterpriseProperties,private val clock:Clock):OncePerRequestFilter() {
    override fun doFilterInternal(request:HttpServletRequest,response:HttpServletResponse,filterChain:FilterChain) {
        if(SecurityContextHolder.getContext().authentication is OAuth2AuthenticationToken) {
            val session=request.getSession(false)
            val started=(session?.getAttribute(EnterpriseSecurityConfiguration.AUTHENTICATED_AT) as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if(started==null || started.isAfter(clock.instant().plusSeconds(60)) || !clock.instant().isBefore(started.plusSeconds(properties.sessionMaxHours*3600L))) {
                session?.invalidate();SecurityContextHolder.clearContext()
                EnterpriseSecurityConfiguration.problem(response,401,"企业会话已到期，请重新登录")
                return
            }
        }
        filterChain.doFilter(request,response)
    }
}
