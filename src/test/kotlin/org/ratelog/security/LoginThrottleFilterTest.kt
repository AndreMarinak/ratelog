package org.ratelog.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.test.MutableClock
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.TestingAuthenticationToken

class LoginThrottleFilterTest {

    private val throttle = LoginThrottle(MutableClock(), maxFailuresPerUsername = 2, maxFailuresPerIp = 100)
    private val clientIp = ClientIpResolver()
    private val filter = LoginThrottleFilter(throttle, clientIp, "/login/process", "/login?error=locked")
    private val failureHandler = ThrottlingFailureHandler(throttle, clientIp, "/login?error=true", "/login?error=locked")
    private val successHandler = ThrottlingSuccessHandler(throttle, "/")

    private fun loginPost(username: String = "admin", realIp: String? = null) = MockHttpServletRequest("POST", "/login/process").apply {
        servletPath = "/login/process"
        addParameter("username", username)
        addParameter("password", "guess")
        realIp?.let { addHeader("X-Real-IP", it) }
    }

    private fun fail(request: MockHttpServletRequest = loginPost()): MockHttpServletResponse =
        MockHttpServletResponse().also { failureHandler.onAuthenticationFailure(request, it, BadCredentialsException("bad")) }

    @Test
    fun `a sign-in for an unlocked username goes on to the password check`() {
        val chain = MockFilterChain()
        filter.doFilter(loginPost(), MockHttpServletResponse(), chain)

        assertNotNull(chain.request)
    }

    @Test
    fun `failures show the usual error until the limit, then the lock notice`() {
        assertEquals("/login?error=true", fail().redirectedUrl)
        assertEquals("/login?error=locked", fail().redirectedUrl)
    }

    @Test
    fun `a locked username is refused before the password is checked`() {
        repeat(2) { fail() }
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()

        filter.doFilter(loginPost(), response, chain)

        assertNull(chain.request, "authentication never ran")
        assertEquals("/login?error=locked", response.redirectedUrl)
    }

    @Test
    fun `other requests are never throttled`() {
        repeat(2) { fail() }
        val get = MockHttpServletRequest("GET", "/login/process").apply { servletPath = "/login/process"; addParameter("username", "admin") }
        val otherPost = MockHttpServletRequest("POST", "/edit-profile").apply { servletPath = "/edit-profile"; addParameter("username", "admin") }

        listOf(get, otherPost).forEach { request ->
            val chain = MockFilterChain()
            filter.doFilter(request, MockHttpServletResponse(), chain)
            assertNotNull(chain.request)
        }
    }

    @Test
    fun `a success clears the username's failures and redirects home`() {
        fail()
        val response = MockHttpServletResponse()

        successHandler.onAuthenticationSuccess(loginPost(), response, TestingAuthenticationToken("admin", null))

        assertEquals("/", response.redirectedUrl)
        assertEquals("/login?error=true", fail().redirectedUrl, "count restarted")
    }

    @Test
    fun `the client IP comes from X-Real-IP, else the socket address`() {
        val viaProxy = loginPost(realIp = "203.0.113.7, 10.0.0.1").apply { remoteAddr = "10.0.0.1" }
        val direct = loginPost().apply { remoteAddr = "198.51.100.2" }

        assertEquals("203.0.113.7", clientIp.resolve(viaProxy))
        assertEquals("198.51.100.2", clientIp.resolve(direct))
        assertEquals("10.0.0.1", ClientIpResolver("").resolve(viaProxy), "a blank header setting uses the socket address")
    }

    @Test
    fun `IP failures are counted per X-Real-IP`() {
        val ipThrottle = LoginThrottle(MutableClock(), maxFailuresPerUsername = 100, maxFailuresPerIp = 2)
        val handler = ThrottlingFailureHandler(ipThrottle, clientIp, "/login?error=true", "/login?error=locked")
        repeat(2) { i -> handler.onAuthenticationFailure(loginPost("user$i", "203.0.113.7"), MockHttpServletResponse(), BadCredentialsException("bad")) }

        assertTrue(ipThrottle.isLocked("anyone", "203.0.113.7"))
        assertTrue(!ipThrottle.isLocked("anyone", "203.0.113.8"))
    }
}
