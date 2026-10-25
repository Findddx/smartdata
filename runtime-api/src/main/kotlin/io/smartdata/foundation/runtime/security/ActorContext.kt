package io.smartdata.foundation.runtime.security

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.BusinessRole
import io.smartdata.foundation.runtime.config.LocalRuntimeConfiguration
import io.smartdata.foundation.service.AuthorizationDeniedException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

interface ActorContextResolver {
    fun resolve(request: HttpServletRequest): Actor
}

@Component
@Profile("local")
class LocalActorContextResolver : ActorContextResolver {
    private val actors = listOf(
        Actor("order-specialist", "订单专员", LocalRuntimeConfiguration.DEMO_TENANT, setOf(BusinessRole.ORDER_SPECIALIST)),
        Actor("warehouse-approver", "仓库审批人", LocalRuntimeConfiguration.DEMO_TENANT, setOf(BusinessRole.WAREHOUSE_APPROVER)),
        Actor("sales-owner", "销售与客户负责人", LocalRuntimeConfiguration.DEMO_TENANT, setOf(BusinessRole.SALES_OWNER)),
        Actor("auditor", "审计员", LocalRuntimeConfiguration.DEMO_TENANT, setOf(BusinessRole.AUDITOR)),
        Actor("admin", "平台管理员", LocalRuntimeConfiguration.DEMO_TENANT, setOf(BusinessRole.ADMIN)),
    )
    private val byId = actors.associateBy { it.id }

    override fun resolve(request: HttpServletRequest): Actor {
        val selected = request.getHeader(DEMO_ACTOR_HEADER)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "order-specialist"
        return byId[selected] ?: throw AuthorizationDeniedException("未知的本地演示身份")
    }

    fun listActors(): List<Actor> = actors

    companion object {
        const val DEMO_ACTOR_HEADER = "X-Demo-Actor"
    }
}

@Component
@Profile("!local")
class JwtActorContextResolver(
    @param:Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private val requiredIssuer: String,
    @param:Value("\${smartdata.security.jwt.audience}")
    private val requiredAudience: String,
    @param:Value("\${smartdata.security.jwt.client-id}")
    private val requiredClientId: String,
) : ActorContextResolver {
    override fun resolve(request: HttpServletRequest): Actor {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: throw AuthorizationDeniedException("缺少已验证的 JWT 身份")
        val jwt = authentication.token
        if (jwt.issuer?.toString() != requiredIssuer || requiredAudience !in jwt.audience.orEmpty()) {
            throw AuthorizationDeniedException("JWT issuer 或 audience 不适用于 SmartData")
        }
        val authorizedParty = jwt.getClaimAsString("azp") ?: jwt.getClaimAsString("client_id")
        if (authorizedParty != requiredClientId) {
            throw AuthorizationDeniedException("JWT 未签发给允许的 SmartData 客户端")
        }
        val tenantId = jwt.getClaimAsString("tenant_id")?.takeIf(TENANT_PATTERN::matches)
            ?: throw AuthorizationDeniedException("JWT 缺少有效 tenant_id")
        // Deliberately ignore realm-wide/generic roles. Only the claim mapped
        // for this resource server can grant a SmartData business role.
        val roleNames = jwt.getClaimAsStringList("smartdata_roles").orEmpty()
        val roles = roleNames.mapNotNull { claim ->
            BusinessRole.entries.firstOrNull { it.name.equals(claim.removePrefix("ROLE_"), ignoreCase = true) }
        }.toSet()
        if (roles.isEmpty()) throw AuthorizationDeniedException("JWT 未映射到任何允许的业务角色")
        val subject = jwt.subject ?: throw AuthorizationDeniedException("JWT 缺少 subject")
        return Actor(
            id = subject,
            displayName = jwt.getClaimAsString("name") ?: subject,
            tenantId = tenantId,
            roles = roles,
        )
    }

    private companion object {
        val TENANT_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,63}")
    }
}
