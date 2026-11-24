package org.ratelog.system

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
import java.util.Locale

/**
 * "Filters replace, navigation pushes" (user feedback 2026-11-23: "the back button thing is REAL annoying on the phone,
 * as well as refreshes for filtered list when it shouldn't need a refresh"; docs/design-decisions.md
 * "One Back leaves the page"). The browser check covers the behaviour; these keep the hooks from being dropped.
 */
class SmoothNavTemplatesTest {
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

    private fun resource(path: String) = javaClass.getResource(path)!!.readText()

    @Test
    fun `no in-place filter script adds a history entry, and each keeps the entry's other state`() {
        listOf("/static/view-swap.js", "/static/list-filter.js", "/static/ratings-filters.js").forEach { path ->
            val js = resource(path)
            assertFalse(js.contains("history.pushState("), "$path pushes a history entry")
            assertTrue(js.contains("history.replaceState(Object.assign({}, keep, {"), "$path replaces the URL, keeping history.state")
            assertTrue(js.contains("window.location.assign(url)") || js.contains("window.location.href = url"), "$path falls back to loading the page")
        }
    }

    @Test
    fun `Discover's view is one swappable region with named link groups`() {
        val page = resource("/templates/discover.html")
        assertTrue(page.contains("""<div id="discoverView" class="discover-view" data-view data-view-fallback="type">"""))
        assertTrue(page.contains("""data-seen-toggle data-view-links="seen""""), "the hide-seen switch applies in place")
        assertTrue(page.contains("""class="browse-first" th:href="${'$'}{firstPageHref}" data-view-links="pages""""))
        assertTrue(page.contains("""<script src="/view-swap.js" defer></script>"""))
        // The toast and the scripts stay outside the region that is replaced.
        assertTrue(page.indexOf("id=\"browseToast\"") > page.indexOf("id=\"browseMore\""))

        val bar = resource("/templates/fragments/browse-bar.html")
        listOf("data-view-links=\"bar\"", "data-view-links=\"type\"", "data-view-links=\"chips\"").forEach {
            assertTrue(bar.contains(it), "browse bar has $it")
        }
    }

    @Test
    fun `the watchlist tabs and type filter switch in place`() {
        val page = resource("/templates/watchlist.html")
        assertTrue(page.contains("""<div id="watchlistView" data-view data-view-fallback="status">"""))
        assertTrue(page.contains("""th:aria-label="#{watchlist.filter.status}" data-view-links="status""""))
        assertTrue(page.contains("""th:aria-label="#{watchlist.filter.type}" data-view-links="type""""))
        assertTrue(page.contains("""<script src="/view-swap.js" defer></script>"""))
    }

    @Test
    fun `the Ratings switch replaces the history entry instead of adding one`() {
        val html = engine.process("fragments/ratings-switch", setOf("ratingsSwitch"), Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("current", "tvshows")
        })
        assertEquals(3, Regex("<a [^>]*data-view-replace").findAll(html).count(), html)
        assertTrue(html.contains("""<script src="/view-swap.js" defer></script>"""))
    }

    @Test
    fun `list tabs and sort swap in place, and the tick-off and reorder modes replace the entry`() {
        val js = resource("/static/list-filter.js")
        assertTrue(js.contains("'.list-filter-tabs a.status-segment, .list-sort a.list-sort-option'"))
        assertTrue(js.contains("'a.list-tick-start, a.list-tick-done, a.list-order-start, a.list-order-done'"))
        assertTrue(js.contains("window.location.replace(mode.href)"))
        assertTrue(resource("/static/list-order.js").contains("window.location.replace(done.href)"), "Done after pending moves replaces too")
    }

    @Test
    fun `Discover's grid and search results bring back the pages you had loaded`() {
        val browse = resource("/static/browse.js")
        assertTrue(browse.contains("const KEY = 'ratelog.discover.grid';"))
        assertTrue(browse.contains("window.RatelogBackNav && window.RatelogBackNav.returned"), "the title page's back link counts as returning")
        assertTrue(browse.contains("document.addEventListener('view:swapped'"), "a filter applied in place starts again at page 1")
        val search = resource("/static/search.js")
        assertTrue(search.contains("KEY: 'ratelog.search.pages'"))
        assertTrue(resource("/static/back-nav.js").contains("window.RatelogBackNav = { returned: true };"))
    }
}
