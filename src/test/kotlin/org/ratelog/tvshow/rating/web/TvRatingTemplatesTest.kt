package org.ratelog.tvshow.rating.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.WebContext
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale

/**
 * Renders the TV page's rating modals with the real messages. Rating, editing and deleting a season must save in place
 * (user, 2026-11-23: "it reloads the page after each rating"), and still be plain posts without JS.
 */
class TvRatingTemplatesTest {

    data class Show(val id: Long, val tmdbId: Int, val name: String)

    private val messages = ResourceBundleMessageSource().apply {
        setBasenames("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    private val appContext = StaticApplicationContext().apply { refresh() }
    private val engine = SpringTemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
            isCacheable = false
        })
        setTemplateEngineMessageSource(messages)
    }

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(template: String, fragment: String, vars: Map<String, Any?>): String {
        // The modals use @{...} links, which need a web context.
        val application = JakartaServletWebApplication.buildApplication(MockServletContext())
        val context = WebContext(application.buildExchange(MockHttpServletRequest(), MockHttpServletResponse()), Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t0k3n"))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, setOf(fragment), context)
            .also { assertFalse(it.contains("??"), "every message key resolves: $it") }
    }

    private val show = Show(id = 7, tmdbId = 1396, name = "Breaking Bad")

    @Test
    fun `the season rate modal saves in place and refreshes the seasons and the panel`() {
        val html = render("fragments/rate-tvshow-modal", "rateTvShowModal(show)", mapOf("show" to show))

        assertTrue(html.contains("""action="/tv/rate""""), html)
        assertTrue(html.contains("""method="post""""), "without JS it is a plain post")
        assertTrue(html.contains("""data-inplace="#seasons, .title-panel""""), html)
        assertTrue(html.contains("""data-update-action="/tv/rate/update""""), html)
    }

    @Test
    fun `deleting a season saves in place too`() {
        val html = render("fragments/delete-tvshow-rating-modal", "deleteTvShowRatingModal(show)", mapOf("show" to show))

        assertTrue(html.contains("""action="/tvshows/delete/7""""), html)
        assertTrue(html.contains("""data-inplace="#seasons, .title-panel""""), html)
    }

    @Test
    fun `Rate show gives the whole show one score, in place, with a delete form for edit mode`() {
        val html = render("fragments/rate-show-modal", "rateShowModal(show)", mapOf("show" to show))

        assertTrue(html.contains("""id="rateShowModal""""), html)
        assertTrue(html.contains("""action="/tv/rate/show""""), html)
        assertTrue(html.contains("""action="/tv/rate/show/delete""""), html)
        assertTrue(Regex("""<form[^>]*action="/tv/rate/show"[^>]*data-inplace="#seasons, .title-panel"""").containsMatchIn(html), html)
        assertTrue(Regex("""<form[^>]*action="/tv/rate/show/delete"[^>]*hidden""").containsMatchIn(html), "delete only shows in edit mode")
        assertTrue(html.contains("""data-confirm="Delete your whole-show score?""""), html)
        assertTrue(html.contains("""data-name="score""""), "the quick-rating slider")
        assertTrue(html.contains("""name="tvShowId" value="7""""), html)
        assertTrue(html.contains("If you rate any season, your score becomes the average of your rated seasons"), html)
    }
}
