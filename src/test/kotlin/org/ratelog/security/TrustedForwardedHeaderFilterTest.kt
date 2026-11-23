package org.ratelog.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class TrustedForwardedHeaderFilterTest {

    private val filter = TrustedForwardedHeaderFilter()

    private class Captured {
        lateinit var request: HttpServletRequest
    }

    /** Runs the filter; the "app" records the request it saw and redirects to [redirectTo]. */
    private fun run(request: MockHttpServletRequest, redirectTo: String? = null): Pair<Captured, MockHttpServletResponse> {
        val captured = Captured()
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain(object : jakarta.servlet.http.HttpServlet() {
            override fun service(req: HttpServletRequest, res: HttpServletResponse) {
                captured.request = req
                redirectTo?.let { res.sendRedirect(it) }
            }
        }))
        return captured to response
    }

    private fun proxied() = MockHttpServletRequest("GET", "/profile").apply {
        serverName = "internal"
        serverPort = 8080
        addHeader("X-Forwarded-Proto", "https")
        addHeader("X-Forwarded-Host", "ratelog.example")
    }

    @Test
    fun `the proxy's proto and host headers are honored`() {
        val (captured, _) = run(proxied())

        assertTrue(captured.request.isSecure)
        assertEquals("https", captured.request.scheme)
        assertEquals("ratelog.example", captured.request.serverName)
    }

    @Test
    fun `headers only a client could have sent are ignored`() {
        val request = proxied().apply {
            addHeader("Forwarded", "proto=http;host=elsewhere.example")
            addHeader("X-Forwarded-Prefix", "/prefix")
            addHeader("X-Forwarded-Port", "1234")
            addHeader("X-Forwarded-Ssl", "off")
        }

        val (captured, _) = run(request)

        assertEquals("https", captured.request.scheme)
        assertEquals("ratelog.example", captured.request.serverName)
        assertEquals(443, captured.request.serverPort)
        assertEquals("/profile", captured.request.requestURI)
        assertEquals("", captured.request.contextPath)
        assertNull(captured.request.getHeader("Forwarded"))
        assertNull(captured.request.getHeader("X-Forwarded-Prefix"))
    }

    @Test
    fun `a request with only untrusted forwarded headers is unchanged`() {
        val request = MockHttpServletRequest("GET", "/x").apply { addHeader("X-Forwarded-Prefix", "/prefix") }

        val (captured, _) = run(request)

        assertFalse(captured.request.isSecure)
        assertEquals("/x", captured.request.requestURI)
    }

    @Test
    fun `redirects stay relative, so a forwarded host never reaches a Location header`() {
        val (_, response) = run(proxied(), redirectTo = "/login")

        assertEquals("/login", response.getHeader("Location"))
    }
}
