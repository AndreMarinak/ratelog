package org.ratelog.movie.rating.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.util.Locale

/**
 * The movie page's rate modal and delete confirmation save in place (user, 2026-11-24: "also does editing the rating when
 * clicked on movie cause a refresh?"), like the TV seasons, and are still plain posts without JS.
 */
class MovieRatingTemplatesTest {

    /** Just the fields the two modals read. */
    data class Movie(val id: Long, val tmdbId: Int, val name: String)

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

    private fun render(template: String, fragment: String): String {
        // The modals use @{...} links, which need a web context.
        val application = JakartaServletWebApplication.buildApplication(MockServletContext())
        val context = WebContext(application.buildExchange(MockHttpServletRequest(), MockHttpServletResponse()), Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t0k3n"))
            setVariable("movie", Movie(id = 7, tmdbId = 603, name = "The Matrix"))
        }
        return engine.process(template, setOf(fragment), context)
            .also { assertFalse(it.contains("??"), "every message key resolves: $it") }
    }

    private fun form(html: String): String =
        Regex("""<form[^>]*>""").find(html)?.value ?: error("no form in:\n$html")

    private val regions = """data-inplace="#your-rating, .title-panel""""

    @Test
    fun `the rate modal saves in place and refreshes Your rating and the panel`() {
        val form = form(render("fragments/rate-movie-modal", "rateMovieModal(movie)"))

        assertTrue(form.contains("""action="/movie/rate""""), form)
        assertTrue(form.contains("""method="post""""), "without JS it is a plain post: $form")
        assertTrue(form.contains(regions), form)
        assertTrue(form.contains("""data-create-action="/movie/rate""""), form)
        assertTrue(form.contains("""data-update-action="/movie/rate/update""""), "editing posts the same form to update: $form")
    }

    @Test
    fun `deleting the rating saves in place too`() {
        val form = form(render("fragments/delete-movie-rating-modal", "deleteMovieRatingModal(movie)"))

        assertTrue(form.contains("""action="/movies/delete/7""""), form)
        assertTrue(form.contains("""method="post""""), form)
        assertTrue(form.contains(regions), form)
    }

    @Test
    fun `the movie page always has one Your rating region to swap, rated or not`() {
        val source = javaClass.classLoader.getResource("templates/movie-detail.html")!!.readText()
        val sections = Regex("""<section[^>]*id="your-rating"[^>]*>""").findAll(source).map { it.value }.toList()

        // inplace.js reloads the page when a region's count differs between the page and the fresh copy.
        assertEquals(2, sections.size, sections.joinToString("\n"))
        assertTrue(sections.any { it.contains("""th:if="${'$'}{movie.hasRating}"""") && it.contains("title-rating") }, "the card when rated")
        val standIn = sections.single { it.contains("""th:unless="${'$'}{movie.hasRating}"""") }
        assertTrue(standIn.contains("hidden"), "an empty, hidden stand-in when not: $standIn")
        assertFalse(standIn.contains("class="), "no card classes, so `.title-rating` still means rated: $standIn")
        assertEquals(1, Regex("""class="title-panel"""").findAll(source).count(), "one action panel")
        assertTrue(source.contains("fragments/rate-movie-modal :: rateMovieModal"), "the page uses the in-place modal")
        assertTrue(source.contains("fragments/delete-movie-rating-modal :: deleteMovieRatingModal"), "and the in-place delete")
    }
}
