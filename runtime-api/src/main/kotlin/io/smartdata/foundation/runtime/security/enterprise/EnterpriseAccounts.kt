package io.smartdata.foundation.runtime.security.enterprise

import io.smartdata.foundation.service.AuthorizationDeniedException
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object EnterpriseAccounts {
    private val mapper = JsonMapper.builder().build()

    fun identity(issuer: String?, subject: String?, displayName: String?): EnterpriseAccountIdentity {
        if (issuer.isNullOrBlank() || subject.isNullOrBlank() || subject.length > 255 ||
            subject.any { it.isISOControl() || it.code>127 } || issuer.any(Char::isISOControl)) {
            throw AuthorizationDeniedException("身份提供方未返回有效的固定账号标识")
        }
        val bytes = mapper.writeValueAsString(listOf("smartdata-oidc-account-v1", issuer, subject))
            .toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val name = displayName?.takeIf { it.isNotBlank() && it.length <= 100 && !it.any(Char::isISOControl) }
            ?: "企业成员"
        return EnterpriseAccountIdentity("acct_$digest", issuer, subject, name)
    }
}
