package org.ratelog.security

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.security.RequestRateLimiter.Limit
import org.ratelog.test.MutableClock
import org.ratelog.test.UserFactory
import org.ratelog.user.AppUserDetails
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.Locale

class RateLimitFilterTest {

    private val messages = ResourceBundleMessageSource().apply {
        setBasenames("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    private val limiter = RequestRateLimiter(reads = Limit(2, 0), writes = Limit(1, 0), clock = MutableClock())
    private val filter = RateLimitFilter(limiter, ClientIpResolver(), messages)

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    private fun request(method: String, path: String, ip: String = "203.0.113.7") = MockHttpServletRequest(method, path).apply {
        servletPath = path
        remoteAddr = ip
        setPreferredLocales(listOf(Locale.ENGLISH))
    }

    private fun run(request: MockHttpServletRequest): Pair<MockFilterChain, MockHttpServletResponse> {
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, chain)
        return chain to response
    }

    private fun signIn(id: Long) {
        val details = AppUserDetails.from(UserFactory.aUser(id = id))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(details, null, details.authorities)
    }

    @Test
    fun `over the limit a browser page gets 429, Retry-After and a short readable page`() {
        run(request("POST", "/status"))
        val (chain, response) = run(request("POST", "/status"))

        assertNull(chain.request, "the app never saw it")
        assertEquals(429, response.status)
        assertEquals("60", response.getHeader("Retry-After"))
        assertTrue(response.contentType!!.startsWith("text/html"))
        assertTrue(response.contentAsString.contains("""<div class="feedback error">Too many requests. Wait 60 seconds, then try again.</div>"""))
    }

    @Test
    fun `the in-place scripts get JSON they show as a toast`() {
        run(request("POST", "/seen"))
        val (_, response) = run(request("POST", "/seen").apply { addHeader("X-Requested-With", "fetch") })

        assertEquals(429, response.status)
        assertEquals("""{"ok":false,"message":"Too many requests. Wait 60 seconds, then try again."}""", response.contentAsString)
    }

    @Test
    fun `the API gets its JSON error format`() {
        run(request("POST", "/api/v1/lists"))
        val (_, response) = run(request("POST", "/api/v1/lists"))

        assertEquals(429, response.status)
        assertEquals("application/json", response.contentType!!.substringBefore(';'))
        assertTrue(response.contentAsString.startsWith("""{"error":"rate_limited","message":"""))
    }

    @Test
    fun `signed-in users are counted per account, not per IP`() {
        signIn(1)
        run(request("POST", "/status"))
        signIn(2)

        val (chain, _) = run(request("POST", "/status"))

        assertNotNull(chain.request, "same IP, different account")
    }

    @Test
    fun `API tokens are counted per token`() {
        run(request("POST", "/api/v1/lists").apply { setAttribute(RateLimitFilter.API_TOKEN_ID_ATTRIBUTE, 1L) })

        val (chain, _) = run(request("POST", "/api/v1/lists").apply { setAttribute(RateLimitFilter.API_TOKEN_ID_ATTRIBUTE, 2L) })

        assertNotNull(chain.request)
    }

    @Test
    fun `anonymous requests are counted per client IP`() {
        run(request("POST", "/login/x", ip = "203.0.113.7"))

        assertNotNull(run(request("POST", "/login/x", ip = "203.0.113.8")).first.request)
        assertNull(run(request("POST", "/login/x", ip = "203.0.113.7")).first.request)
    }

    @Test
    fun `reads have their own, larger bucket`() {
        run(request("POST", "/status"))

        assertNotNull(run(request("GET", "/watchlist")).first.request)
        assertNotNull(run(request("GET", "/watchlist")).first.request)
        assertNull(run(request("GET", "/watchlist")).first.request)
    }

    @Test
    fun `static files and the health check are exempt`() {
        listOf("/styles.css", "/tile-actions.js", "/img/logo.svg", "/fonts/CrimsonPro-Light.woff2", "/api/health").forEach { path ->
            repeat(5) { assertNotNull(run(request("GET", path)).first.request, path) }
        }
    }
}
