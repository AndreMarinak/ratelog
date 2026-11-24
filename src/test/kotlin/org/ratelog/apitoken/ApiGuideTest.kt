package org.ratelog.apitoken

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import org.ratelog.apitoken.create.CreateApiTokenHandler
import org.ratelog.apitoken.revoke.RevokeApiTokenHandler
import java.util.Locale

/** The "API guide for AI assistants": its text, the Markdown download and the box on /settings/api. */
class ApiGuideTest {

    private val guide = ApiGuide()

    private val controller = ApiSettingsController(
        apiTokenRepository = mock<ApiTokenRepository>(),
        createHandler = mock<CreateApiTokenHandler>(),
        revokeHandler = mock<RevokeApiTokenHandler>(),
        apiGuide = guide,
    )

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    @Test
    fun `the guide names the request's base URL everywhere and keeps no placeholder`() {
        val text = guide.render("https://ratelog.example")
        assertTrue(text.contains("Base URL: `https://ratelog.example`"))
        assertTrue(text.contains("https://ratelog.example/list/{id}"))
        assertFalse(text.contains("{{BASE_URL}}"))
    }

    @Test
    fun `the guide covers the documented endpoints and rules`() {
        val text = guide.render("http://localhost:8080")
        listOf(
            "Authorization: Bearer <YOUR_TOKEN>", "POST /api/v1/lists", "/api/v1/lists/{id}/items", "PUT /api/v1/lists/{id}/order",
            "PATCH /api/v1/lists/{id}/items/{type}/{tmdbId}", "GET /api/v1/ratings", "PUT /api/v1/ratings/movie/949",
            "/api/v1/ratings/tv/{tmdbId}/seasons/{season}", "PUT /api/v1/ratings/tv/{tmdbId}", "/api/v1/watchlist/{type}/{tmdbId}",
            "POST /api/v1/statuses", "GET /api/v1/lists/official", "PUT /api/v1/lists/{id}/saved", "GET /api/v1/recommendations",
            "GET /api/v1/stats", "unresolved", "candidates", "100 items per request", "rate_limited", "Retry-After", "429",
            "`type` is always required with `tmdbId`",
        ).forEach { assertTrue(text.contains(it), "missing: $it") }
        assertTrue(text.lines().size in 100..220, "guide is ${text.lines().size} lines")
    }

    @Test
    fun `the guide holds no real token and no admin or hidden features`() {
        val text = guide.render("http://localhost:8080")
        assertFalse(Regex("rl_[A-Za-z0-9_-]{8,}").containsMatchIn(text), "looks like a real token")
        assertFalse(text.contains("isOfficial\": true"), "admin marking is left out")
        assertFalse(text.contains("/tags"), "tags are behind a feature flag")
    }

    @Test
    fun `guide md is served as markdown from the address the request used`() {
        val mvc = MockMvcBuilders.standaloneSetup(controller).build()
        val result = mvc.perform(
            get("/settings/api/guide.md").with { it.scheme = "https"; it.serverName = "ratelog.example"; it.serverPort = 443; it }
        ).andReturn().response
        assertEquals(200, result.status)
        assertTrue(result.contentType!!.startsWith("text/markdown"), result.contentType)
        assertTrue(result.contentType!!.contains("charset=UTF-8", ignoreCase = true))
        val body = result.contentAsString
        assertTrue(body.contains("https://ratelog.example/api/v1"), "base URL missing")
        assertFalse(Regex("rl_[A-Za-z0-9_-]{8,}").containsMatchIn(body))
    }

    @Test
    fun `the settings page section renders the box, copy button, download link and warning`() {
        val messages = ResourceBundleMessageSource().apply {
            setBasenames("messages"); setDefaultEncoding("UTF-8"); setFallbackToSystemLocale(false)
        }
        val engine = SpringTemplateEngine().apply {
            setTemplateResolver(ClassLoaderTemplateResolver().apply {
                prefix = "templates/"; suffix = ".html"; templateMode = TemplateMode.HTML; characterEncoding = "UTF-8"; isCacheable = false
            })
            setTemplateEngineMessageSource(messages)
        }
        val app = JakartaServletWebApplication.buildApplication(MockServletContext())
        val context = WebContext(app.buildExchange(MockHttpServletRequest(), MockHttpServletResponse()), Locale.ENGLISH).apply {
            setVariable(
                ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                ThymeleafEvaluationContext(StaticApplicationContext().apply { refresh() }, null),
            )
            setVariable("guideText", guide.render("http://localhost:8080"))
        }
        val html = engine.process("fragments/api-guide", setOf("guide"), context)

        assertTrue(html.contains("Use Ratelog with an AI assistant"))
        assertTrue(html.contains("""id="apiGuideText""""))
        assertTrue(html.contains("readonly"))
        assertTrue(html.contains("""id="apiGuideCopy""""))
        assertTrue(html.contains("Download (.md)"))
        assertTrue(html.contains("""href="/settings/api/guide.md""""))
        assertTrue(html.contains("full access to your account"))
        assertTrue(html.contains("revoke it on this page"))
        assertTrue(html.contains("Base URL: `http://localhost:8080`"))
        assertFalse(Regex("rl_[A-Za-z0-9_-]{8,}").containsMatchIn(html))
        assertFalse(html.contains(" onclick="), "CSP: no inline handlers")
    }
}
