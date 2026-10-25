package io.smartdata.foundation.runtime.security.enterprise

import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/** No exception message, stack trace, identity, endpoint or provider description enters this record. */
internal class EnterpriseLoginFailureDiagnostics(
    private val writeRecord:(String)->Unit = { LOGGER.warn(it) },
) {
    fun reject(response:HttpServletResponse,failure:Throwable) {
        val seen=Collections.newSetFromMap(IdentityHashMap<Throwable,Boolean>())
        val chain=generateSequence(failure) { it.cause }.takeWhile(seen::add).take(12).toList()
        val rawCode=chain.filterIsInstance<OAuth2AuthenticationException>().firstOrNull()?.error?.errorCode
        val errorCode=when {
            rawCode==null -> "authentication_failure"
            !CODE_FORMAT.matches(rawCode) -> "invalid_error_code"
            rawCode in SAFE_CODES -> rawCode
            else -> "other_oauth_error"
        }
        val sqlState=chain.filterIsInstance<SQLException>().firstNotNullOfOrNull { exception ->
            exception.sqlState?.takeIf(SQL_STATE_FORMAT::matches)
        } ?: "none"
        val auditId=UUID.randomUUID().toString()
        val exceptionClass=safeClass(failure)
        val causeClass=chain.drop(1).lastOrNull()?.let(::safeClass) ?: "none"
        // Pass only the bounded record, never a Throwable, to the logging API.
        writeRecord("enterprise_login_failed auditId=$auditId errorCode=$errorCode exceptionClass=$exceptionClass sqlState=$sqlState causeClass=$causeClass")
        response.setHeader("X-Audit-Id",auditId)
        EnterpriseSecurityConfiguration.problem(response,401,"企业登录未完成，请重新发起登录")
    }

    private fun safeClass(failure:Throwable)=failure.javaClass.name.takeIf(CLASS_FORMAT::matches) ?: "Throwable"

    companion object {
        private val LOGGER=LoggerFactory.getLogger(EnterpriseLoginFailureDiagnostics::class.java)
        private val CODE_FORMAT=Regex("[a-z][a-z0-9_]{0,63}")
        private val SQL_STATE_FORMAT=Regex("[A-Z0-9]{5}")
        private val CLASS_FORMAT=Regex("[A-Za-z0-9_.$]{1,200}")
        // Unknown provider strings are categories, never unreviewed text in the log.
        private val SAFE_CODES=setOf("enterprise_identity_unavailable","invalid_request","unauthorized_client",
            "access_denied","unsupported_response_type","invalid_scope","server_error","temporarily_unavailable",
            "invalid_client","invalid_grant","unsupported_grant_type","invalid_token","insufficient_scope",
            "invalid_token_response","invalid_user_info_response","invalid_id_token","invalid_nonce",
            "invalid_state_parameter","authorization_request_not_found","login_required","interaction_required",
            "account_selection_required","consent_required")
    }
}

internal fun enterpriseIdentityFailure(cause:RuntimeException)=OAuth2AuthenticationException(
    OAuth2Error("enterprise_identity_unavailable","企业尚未开通或身份目录暂不可用",null),
    "企业尚未开通或身份目录暂不可用",cause,
)
