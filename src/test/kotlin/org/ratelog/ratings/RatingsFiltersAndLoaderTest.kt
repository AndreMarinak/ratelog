package org.ratelog.ratings

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.File
import java.util.Locale

/**
 * Two requests from 2026-11-23: the Ratings tab's filters apply on change with an ✕ each (ratings-filters.js, loaded by
 * the Movies | TV Shows switch), and no full-screen loader on page loads (fragments/loader.html is a thin top bar).
 * The script depends on the upstream filter form's shape, which is pinned here.
 */
class RatingsFiltersAndLoaderTest {

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

    private fun render(template: String, fragments: Set<String>? = null): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
        }
        return if (fragments == null) engine.process(template, context) else engine.process(template, fragments, context)
    }

    private fun resource(path: String) = javaClass.getResource(path)!!.readText()

    // --- Ratings filters ---

    @Test
    fun `the switch loads the filter script with its translated clear label`() {
        val html = engine.process("fragments/ratings-switch", setOf("ratingsSwitch"), Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("current", "movies")
        })

        assertTrue(html.contains("""<script src="/ratings-filters.js" defer data-clear-label="Remove filter: {0}"></script>"""), html)
        assertTrue(html.contains("""<link rel="stylesheet" href="/ratings.css">"""))
    }

    @Test
    fun `both ranking pages keep the form the script enhances, with a no-JS Search button`() {
        mapOf("movies.html" to "/movies", "tvshows.html" to "/tvshows").forEach { (template, path) ->
            val source = resource("/templates/$template")
            val switchAt = source.indexOf("fragments/ratings-switch :: ratingsSwitch")
            val formAt = source.indexOf("""<form th:action="@{$path}" class="filter" method="get">""")
            assertTrue(switchAt in 0 until formAt, "$template: the switch comes before the form (.ratings-switch ~ .grid form.filter)")
            listOf("category", "ratingCategory", "name", "limit").forEach { name ->
                assertTrue(source.contains("""name="$name""""), "$template has $name")
            }
            assertTrue(source.contains("""<input id="name" name="name" type="text""""), "$template: the name field is a text input")
            assertTrue(source.contains("""<button class="primary" type="submit""""), "$template: Search stays for no-JS")
        }
    }

    @Test
    fun `the script applies on change, clears one filter, and keeps the URL and Back working`() {
        val js = resource("/static/ratings-filters.js")

        assertTrue(js.contains("const FORM = '.ratings-switch ~ .grid form.filter';"))
        assertTrue(js.contains("DEFAULTS = { limit: '10' }"), "the server's default count is not a filter")
        assertTrue(js.contains("addEventListener('change'"))
        assertTrue(js.contains("history.pushState("))
        assertTrue(js.contains("addEventListener('popstate'"))
        assertTrue(js.contains("form.classList.add('is-live')"))
        assertTrue(js.contains("window.location.assign(url)"), "a failed fetch loads the page instead")

        val css = resource("/static/ratings.css")
        assertTrue(css.contains("form.filter.is-live {\n  > button[type=\"submit\"] {\n    display: none;") ||
            css.contains("form.filter.is-live {\r\n  > button[type=\"submit\"] {\r\n    display: none;"), "Search is hidden only once JS runs")
        assertTrue(css.contains("&[hidden] {"), "the ✕ is a button: [hidden] needs its own display: none")
    }

    // --- No full-screen loader ---

    @Test
    fun `the loader fragment is a thin bar, not an overlay`() {
        val html = render("fragments/loader").trim()

        assertEquals("""<div id="loader" class="nav-progress" aria-hidden="true"></div>""", html)
        assertFalse(html.contains("loader-overlay"))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun `the bar only shows after a delay and nothing styles a full-screen overlay any more`() {
        val js = resource("/static/loader.js")
        assertTrue(js.contains("const DELAY_MS = 400;"))
        assertTrue(js.contains("window.addEventListener('beforeunload', start);"))
        assertTrue(js.contains("window.addEventListener('pageshow', stop);"))

        val css = resource("/static/components.css")
        assertTrue(css.contains(".nav-progress {"))
        assertTrue(css.contains("pointer-events: none;"))
        assertTrue(css.contains("height: 3px;"))

        val roots = listOf("src/main/resources/templates", "src/main/resources/static").map(::File)
        val offenders = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension in setOf("html", "css", "js")) }.toList() }
            .filter { it.name != "lucide.min.js" }
            .filter { file -> file.readText().let { it.contains("loader-overlay") || it.contains("loader-logo") || it.contains("Loader.show") || it.contains("Loader.hide") } }
        assertTrue(offenders.isEmpty(), "still referencing the old loader: $offenders")
    }
}
