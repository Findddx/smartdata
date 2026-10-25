package io.smartdata.foundation.runtime.security.enterprise

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.runtime.security.ActorContextResolver
import io.smartdata.foundation.runtime.workspace.WorkspaceTransactionAccessPort
import io.smartdata.foundation.service.AuthorizationDeniedException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class EnterprisePrincipalAccess(private val directory:EnterpriseDirectoryService):EnterpriseAccessPort,ActorContextResolver {
    override fun currentIdentity():EnterpriseAccountIdentity? {
        val authentication=SecurityContextHolder.getContext().authentication
        if(authentication==null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) return null
        val identity=when(authentication) {
            is OAuth2AuthenticationToken -> {
                if(authentication.authorizedClientRegistrationId!="enterprise") deny()
                val user=authentication.principal as? OidcUser?:deny()
                EnterpriseAccounts.identity(user.idToken.issuer.toString(),user.idToken.subject,user.getClaimAsString("name"))
            }
            is JwtAuthenticationToken -> {
                val token=authentication.token
                if(token.issuer?.toString()!=directory.properties.issuerUri || directory.properties.audience !in token.audience.orEmpty() || token.getClaimAsString("client_id")!=directory.properties.clientId) deny()
                EnterpriseAccounts.identity(requireNotNull(token.issuer).toString(),token.subject,token.getClaimAsString("name"))
            }
            else -> deny()
        }
        if(identity.issuer!=directory.properties.issuerUri) deny()
        return identity
    }
    override fun currentActor():Actor=directory.actor(currentIdentity()?:deny())
    override fun resolve(request:HttpServletRequest):Actor=currentActor()
    private fun deny():Nothing=throw AuthorizationDeniedException("请使用已登记的企业登录身份")
}

class EnterpriseWorkspaceTransactionAccess(private val directory:EnterpriseDirectoryService):WorkspaceTransactionAccessPort {
    override fun checkCurrent(actor:Actor,write:Boolean)=directory.checkCurrent(actor,write)
}
