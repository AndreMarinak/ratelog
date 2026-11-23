package org.ratelog.system

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale

/** The rate modals carry a "Delete rating" button (hidden until title-page.js opens them in edit mode). */
class RateModalDeleteTemplateTest {

    /** Just the fields the rate modals read. */
    class Title(val id: Long, val tmdbId: Long, val name: String)

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
        // The modals use context-relative links (@{/movie/rate}); outside a web request the context path is empty.
        setLinkBuilder(object : org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            override fun computeContextPath(context: org.thymeleaf.context.IExpressionContext, base: String, parameters: MutableMap<String, Any>?) = ""
        })
    }

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(template: String, fragment: String, variable: String): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            setVariable(variable, Title(7, 862, "Toy Story"))
        }
        return engine.process(template, setOf(fragment), context)
    }

    private fun deleteButton(html: String): String =
        Regex("""<button[^>]*rate-modal-delete[^>]*>.*?</button>""", RegexOption.DOT_MATCHES_ALL).find(html)?.value
            ?: error("no Delete rating button in:\n$html")

    @Test
    fun `movie rate modal has a hidden delete button that opens the movie delete confirmation`() {
        val button = deleteButton(render("fragments/rate-movie-modal", "rateMovieModal(movie)", "movie"))
        assertTrue(button.contains("hidden"), button)
        assertTrue(button.contains("""data-delete-modal="deleteMovieRatingModal""""), button)
        assertTrue(button.contains("Delete rating"), button)
        assertTrue(button.contains("""type="button""""), "must not submit the rating form: $button")
    }

    @Test
    fun `tv rate modal has a hidden delete button that opens the season delete confirmation`() {
        val button = deleteButton(render("fragments/rate-tvshow-modal", "rateTvShowModal(show)", "show"))
        assertTrue(button.contains("hidden"), button)
        assertTrue(button.contains("""data-delete-modal="deleteTvShowRatingModal""""), button)
        assertTrue(button.contains("Delete rating"), button)
    }
}
