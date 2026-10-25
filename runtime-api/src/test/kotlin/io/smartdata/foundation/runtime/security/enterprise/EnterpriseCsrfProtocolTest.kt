package io.smartdata.foundation.runtime.security.enterprise

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfToken

/** Executes Spring 7.1.1's actual filter, cookie repository and masked-header handler. */
class EnterpriseCsrfProtocolTest {
    @Test fun `me style masked token authorizes the protected POST`() {
        val issued=issue()
        assertThat(post(issued,issued.maskedToken)).isEqualTo(200)
    }

    @Test fun `raw cookie token is not accepted as the me masked header contract`() {
        val issued=issue()
        assertThat(post(issued,issued.cookie.value)).isEqualTo(403)
    }

    @Test fun `wrong masked token cannot authorize another browser cookie`() {
        val first=issue();val second=issue()
        assertThat(post(first,second.maskedToken)).isEqualTo(403)
    }

    @Test fun `missing csrf header cannot authorize a protected POST`() {
        assertThat(post(issue(),null)).isEqualTo(403)
    }

    @Test fun `mask changes between responses while both masked values resolve to the same cookie`() {
        val first=issue()
        val again=issue(first.cookie)
        assertThat(again.maskedToken).isNotEqualTo(first.maskedToken).isNotEqualTo(first.cookie.value)
        assertThat(post(first,first.maskedToken)).isEqualTo(200)
        assertThat(post(first,again.maskedToken)).isEqualTo(200)
    }

    private data class Issued(val cookie:Cookie,val headerName:String,val maskedToken:String)

    private fun filter()=CsrfFilter(CookieCsrfTokenRepository.withHttpOnlyFalse()).also {
        it.setRequestHandler(enterpriseCsrfRequestHandler())
    }

    private fun issue(cookie:Cookie?=null):Issued {
        val request=MockHttpServletRequest("GET","/api/v1/me").also {
            it.isSecure=true
            if(cookie!=null) it.setCookies(cookie)
        }
        val response=MockHttpServletResponse()
        var exposed:CsrfToken?=null
        filter().doFilter(request,response,FilterChain { req,_ ->
            exposed=req.getAttribute(CsrfToken::class.java.name) as CsrfToken
            // Read exactly what EnterpriseController returns in /me JSON.
            requireNotNull(exposed).token
        })
        val csrf=requireNotNull(exposed)
        val actualCookie=cookie?:requireNotNull(response.getCookie("XSRF-TOKEN"))
        assertThat(csrf.token).isNotEqualTo(actualCookie.value)
        return Issued(actualCookie,csrf.headerName,csrf.token)
    }

    private fun post(issued:Issued,header:String?):Int {
        val request=MockHttpServletRequest("POST","/api/v1/enterprise/invitations").also {
            it.isSecure=true;it.setCookies(issued.cookie)
            if(header!=null) it.addHeader(issued.headerName,header)
        }
        val response=MockHttpServletResponse()
        var reached=false
        filter().doFilter(request,response,FilterChain { _,_ -> reached=true })
        assertThat(reached).isEqualTo(response.status==200)
        return response.status
    }
}
