package io.smartdata.foundation.runtime.security.enterprise

import io.smartdata.foundation.domain.Actor
import io.smartdata.foundation.domain.BusinessRole
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Instant
import jakarta.validation.constraints.Min

/** Deployment configuration, never a browser-supplied tenant or role mapping. */
@ConfigurationProperties("smartdata.enterprise")
data class EnterpriseProperties(
    var enabled: Boolean = false,
    var tenantId: String = "",
    var tenantDisplayName: String = "",
    var issuerUri: String = "",
    var clientId: String = "",
    var audience: String = "smartdata-runtime",
    var externalBaseUrl: String = "",
    var bootstrapOwners: List<String> = emptyList(),
    var sessionIdleMinutes: Int = 30,
    var sessionMaxHours: Int = 8,
) {
    fun validate() {
        require(enabled) { "Enterprise deployment must be explicitly enabled" }
        require(tenantId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,63}"))) { "Invalid configured enterprise tenant" }
        require(tenantDisplayName.isNotBlank() && tenantDisplayName.length <= 100) { "Enterprise display name is required" }
        require(clientId.isNotBlank() && clientId.length <= 200 && audience.isNotBlank()) { "OIDC client and resource audience are required" }
        listOf(issuerUri, externalBaseUrl).forEach { value ->
            require(value.length<=2048 && !value.any(Char::isISOControl)) { "Enterprise URL contains invalid characters" }
            val uri = try { URI(value) } catch(_:Exception) { throw IllegalArgumentException("Enterprise URL is malformed") }
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && (uri.port==-1 || uri.port in 1..65535)) { "Enterprise identity and browser origins require trusted HTTPS URLs" }
        }
        require(URI(externalBaseUrl).rawPath.orEmpty() in setOf("","/")) { "Enterprise browser base URL must be an origin" }
        require(bootstrapOwners.all(ACCOUNT_ID::matches) && bootstrapOwners.size <= 10) { "Bootstrap owners require exact account IDs" }
        require(sessionIdleMinutes in 1..120 && sessionMaxHours in 1..24) { "Session lifetime is outside the permitted range" }
    }

    companion object { val ACCOUNT_ID = Regex("acct_[0-9a-f]{64}") }
}

enum class EnterpriseMemberStatus { ACTIVE, SUSPENDED }
enum class EnterpriseInvitationStatus { PENDING, ACCEPTED, REVOKED, EXPIRED }

data class EnterpriseTenantView(val id: String, val displayName: String)
data class EnterpriseMembershipView(val status: EnterpriseMemberStatus, val stateVersion: Long)
data class EnterpriseCapabilities(val canUseWorkspace: Boolean, val canManageMembers: Boolean, val localRoleSwitch: Boolean = false)
data class EnterpriseCsrfView(val headerName: String, val token: String)
data class EnterpriseInvitationView(
    val id: String, val accountId: String, val roles: Set<BusinessRole>, val status: EnterpriseInvitationStatus,
    val stateVersion: Long, val createdBy: String, val expiresAt: Instant,
)
data class EnterpriseMemberView(
    val accountId: String, val displayName: String?, val roles: Set<BusinessRole>, val status: EnterpriseMemberStatus,
    val stateVersion: Long, val updatedAt: Instant,
)
data class EnterpriseDirectoryView(val stateVersion: Long, val members: List<EnterpriseMemberView>, val invitations: List<EnterpriseInvitationView>)
data class EnterpriseMeView(
    val mode: String = "ENTERPRISE", val authenticated: Boolean, val accountId: String?, val displayName: String?,
    val tenant: EnterpriseTenantView?, val membership: EnterpriseMembershipView?, val roles: Set<BusinessRole>,
    val capabilities: EnterpriseCapabilities, val loginUrl: String? = "/oauth2/authorization/enterprise",
    val logoutUrl: String? = "/logout", val csrf: EnterpriseCsrfView?, val invitations: List<EnterpriseInvitationView> = emptyList(),
)

data class EnterpriseInviteCommand(@field:Min(0) val expectedStateVersion: Long, val accountId: String, val roles: Set<BusinessRole>)
data class EnterpriseMemberRolesCommand(@field:Min(0) val expectedStateVersion: Long, @field:Min(1) val expectedMembershipVersion: Long, val roles: Set<BusinessRole>)
data class EnterpriseMemberStateCommand(@field:Min(0) val expectedStateVersion: Long, @field:Min(1) val expectedMembershipVersion: Long)
data class EnterpriseVersionCommand(@field:Min(0) val expectedStateVersion: Long)

/** Created only from Spring's validated OIDC/JWT principal. Never deserialize from an API body. */
data class EnterpriseAccountIdentity(val accountId: String, val issuer: String, val subject: String, val displayName: String)

interface EnterpriseAccessPort {
    fun currentIdentity(): EnterpriseAccountIdentity?
    fun currentActor(): Actor
}
