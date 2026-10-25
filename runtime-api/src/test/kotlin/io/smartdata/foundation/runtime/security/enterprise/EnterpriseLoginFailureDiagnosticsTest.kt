package io.smartdata.foundation.runtime.security.enterprise

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import java.sql.SQLException
import java.util.UUID

class EnterpriseLoginFailureDiagnosticsTest {
    @Test fun `directory wrapper keeps the cause without copying sensitive exception text`() {
        val cause=IllegalStateException("password=private-diagnostic-fixture")
        val failure=enterpriseIdentityFailure(cause)
        assertThat(failure.cause).isSameAs(cause)
        assertThat(failure.message).doesNotContain("private-diagnostic-fixture")
        assertThat(failure.error.errorCode).isEqualTo("enterprise_identity_unavailable")
    }

    @Test fun `failure record contains only safe categories and SQLState while response stays generic`() {
        val secret="private-diagnostic-fixture"
        val cause=SQLException("token=$secret https://idp.invalid/?code=$secret email=$secret", "42501")
        val failure=enterpriseIdentityFailure(IllegalStateException("sub=$secret",cause))
        val records=mutableListOf<String>()
        val response=MockHttpServletResponse()
        EnterpriseLoginFailureDiagnostics(records::add).reject(response,failure)
        assertThat(records).hasSize(1)
        assertThat(records.single()).contains("errorCode=enterprise_identity_unavailable","sqlState=42501",
            "exceptionClass=org.springframework.security.oauth2.core.OAuth2AuthenticationException","causeClass=java.sql.SQLException")
            .doesNotContain(secret,"https://","token=","email=","sub=","\n","\r")
        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentAsString).doesNotContain(secret,"42501","SQLException")
        val auditId=requireNotNull(response.getHeader("X-Audit-Id"))
        assertThat(UUID.fromString(auditId).toString()).isEqualTo(auditId)
        assertThat(records.single()).contains("auditId=$auditId")
    }

    @Test fun `untrusted OAuth codes and malformed SQLStates cannot inject records`() {
        val records=mutableListOf<String>()
        val failure=OAuth2AuthenticationException(OAuth2Error("invalid_request\nforged_record", "private-description", "https://private-uri.invalid/"),
            "private-message",SQLException("private-sql-message","42\n01"))
        EnterpriseLoginFailureDiagnostics(records::add).reject(MockHttpServletResponse(),failure)
        assertThat(records.single()).contains("errorCode=invalid_error_code","sqlState=none")
            .doesNotContain("forged_record","private-","https://","\n","\r")
        val unknown=mutableListOf<String>()
        EnterpriseLoginFailureDiagnostics(unknown::add).reject(MockHttpServletResponse(),OAuth2AuthenticationException("private_provider_value"))
        assertThat(unknown.single()).contains("errorCode=other_oauth_error").doesNotContain("private_provider_value")
    }
}
