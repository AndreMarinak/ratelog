package org.ratelog.ratings.scroll

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.movie.top.GetTopMoviesResponse
import org.ratelog.test.InMemoryHomeRepository
import org.ratelog.test.MutableClock
import org.ratelog.test.UserFactory
import org.ratelog.tile.RankingTileActionsInterceptor
import org.ratelog.tvshow.top.GetTopTvShowsResponse
import org.ratelog.watchlist.seen.TileActionsView
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.web.servlet.ModelAndView

import org.thymeleaf.context.WebContext
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.LocalDate
import java.util.Locale

/** The ranking's footer (sentinel + "Show more" fallback + end line) and the scripts behind it, rendered for real. */
class RankingScrollTemplatesTest {

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

    private fun render(template: String, fragments: Set<String>, vars: Map<String, Any?>): String {
        // A web context, for the cards' @{/movie/...} links.
        val application = JakartaServletWebApplication.buildApplication(MockServletContext())
        val context = WebContext(application.buildExchange(MockHttpServletRequest(), MockHttpServletResponse()), Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, fragments, context)
    }

    private fun resource(path: String) = javaClass.getResource(path)!!.readText()

    private fun footer(page: RankingPage?) = render("fragments/ranking-scroll", setOf("more"), mapOf("p" to page))

    @Test
    fun `while there is more, the footer is the sentinel with the next page and a Show more link that works without JS`() {
        val page = RankingScroll.page("/movies", RankingScroll.window(null, null), 31, listOf("category" to "28"))
        val html = footer(page)

        assertTrue(html.contains("class=\"ranking-more\" data-ranking-more"), html)
        assertTrue(html.contains("data-next=\"/movies?category=28&amp;offset=30&amp;limit=30&amp;partial=1\""), html)
        assertTrue(html.contains("data-address=\"/movies?category=28\""), html)
        assertTrue(html.contains("data-shown=\"30\""))
        assertTrue(html.contains("""<a class="ranking-more-link" href="/movies?category=28&amp;limit=60#rank-pos-31" data-ranking-more-link>"""), html)
        assertTrue(html.contains("<span>Show more</span>"))
        assertTrue(html.contains("data-loading=\"Loading…\""))
        assertTrue(html.contains("data-retry=\"Try again\""))
        assertTrue(html.contains("data-error=\"Couldn&#39;t load more.\""), html)
        assertFalse(html.contains("ranking-end"), "no end line yet")
    }

    @Test
    fun `after the last title, a quiet That's all N and nothing left to load`() {
        val html = footer(RankingScroll.page("/tvshows", RankingScroll.window(30, 30), 15, emptyList()))

        assertTrue(html.contains("""<p class="ranking-end">That&#39;s all 45</p>"""), html)
        assertFalse(html.contains("data-next"), "the script stops here")
        assertFalse(html.contains("data-ranking-more-link"))
        assertTrue(html.contains("data-address=\"/tvshows?limit=45\""))
    }

    @Test
    fun `an empty ranking has no footer (the empty message says it)`() {
        assertEquals("", footer(RankingScroll.page("/movies", RankingScroll.window(null, null), 0, emptyList())).trim())
        assertEquals("", footer(null).trim())
    }

    @Test
    fun `the ranking fragment alone renders the cards with their place in the list and the footer`() {
        val tops = (31..33).map { GetTopMoviesResponse(it, 600 + it, "Movie $it", 1999, "/p$it.jpg", 8.0, emptyList()) }
        val actions = tops.associate {
            it.tmdbId to TileActionsView.of(it.tmdbId, MediaType.movie, null, true, null, LocalDate.parse("2026-11-23"), Locale.ENGLISH, score = 8.0)
        }
        val page = RankingScroll.page("/movies", RankingScroll.window(30, 30), 3, emptyList())
        val html = render("movies", setOf("ranking"), mapOf("tops" to tops, "rankingPage" to page, "rankingActions" to actions))

        assertEquals(3, Regex("""class="rank-item"""").findAll(html).count(), html)
        assertTrue(html.contains("id=\"rank-pos-31\""))
        assertTrue(html.contains("id=\"rank-pos-33\""))
        assertTrue(html.contains("""<span class="rank">31</span>"""), "ranks continue")
        assertTrue(html.contains("data-key=\"movie:631\""), "appended cards get the ★/⋯ actions")
        assertTrue(html.contains("That&#39;s all 33"))
        assertFalse(html.contains("class=\"filter\""), "the fragment is the ranking only, not the filters")
        assertFalse(html.contains("premieres"))
    }

    @Test
    fun `both ranking pages expose the fragment, the footer and the script, and drop the count select`() {
        listOf("movies.html", "tvshows.html").forEach { template ->
            val source = resource("/templates/$template")
            assertTrue(source.contains("""<div th:if="${'$'}{tops != null}" th:fragment="ranking">"""), template)
            assertTrue(source.contains("""<div th:replace="~{fragments/ranking-scroll :: more(${'$'}{rankingPage})}"></div>"""), template)
            assertTrue(source.contains("""<script src="/ranking-scroll.js" defer></script>"""), template)
            assertTrue(source.contains("rank-pos-"), template)
            assertFalse(source.contains("""name="limit""""), "$template: the ranking loads as you scroll, no count select")
        }
    }

    @Test
    fun `the script appends in place, replaces the URL, and never adds history entries`() {
        val js = resource("/static/ranking-scroll.js")
        assertTrue(js.contains("new IntersectionObserver("))
        assertTrue(js.contains("observer.observe(box)"))
        assertTrue(js.contains("history.replaceState(Object.assign({}, keep, { rankingScroll: true }), '', url)"), "keeps other scripts' state")
        assertFalse(js.contains("pushState"), "one Back leaves the page")
        assertTrue(js.contains("event.preventDefault();"), "the Show more link loads in place with JS")
        assertTrue(js.contains("box.before(...cards)"))
        assertTrue(js.contains("!shown.has(keyOf(item))"), "a card already shown is skipped")
        assertTrue(js.contains("response.status === 429"), "rate limited: a toast, no retry loop")
        assertTrue(js.contains("window.lucide.createIcons()"), "icons on appended cards")
        assertTrue(js.contains("document.addEventListener('ratings:swapped'"), "a filter brings a new first page")

        val filters = resource("/static/ratings-filters.js")
        assertTrue(filters.contains("document.dispatchEvent(new CustomEvent('ratings:swapped'"))

        val css = resource("/static/ratings.css")
        assertTrue(css.contains(".ratings-switch ~ .grid .ranking-more-link {"))
        assertTrue(css.contains(".ratings-switch ~ .grid .ranking-end,"))
        assertFalse(css.contains("data-filter=\"limit\""), "no styles left for the removed count select")
    }

    @Test
    fun `the next-page fragment gets the poster quick actions too`() {
        val home = InMemoryHomeRepository()
        val user = UserFactory.aUser(id = 1)
        val interceptor = RankingTileActionsInterceptor(home, { user }, MutableClock())
        listOf(
            "movies :: ranking" to GetTopMoviesResponse(31, 603, "The Matrix", 1999, null, 9.0, emptyList()),
            "tvshows :: ranking" to GetTopTvShowsResponse(31, 1396, "Breaking Bad", 2008, null, 9.0, emptyList()),
        ).forEach { (view, top) ->
            val mav = ModelAndView(view, mapOf("tops" to listOf(top)))
            interceptor.postHandle(MockHttpServletRequest(), MockHttpServletResponse(), Any(), mav)
            assertNotNull(mav.model["rankingActions"], view)
        }
    }

    // --- The To rate view's tabs (2026-11-23): in place, no Back step ---

    @Test
    fun `the To rate tabs swap the view in place and the script never holds on to the old section`() {
        val source = resource("/templates/ratings-unrated.html")
        assertTrue(source.contains("""id="unratedView" class="unrated" data-unrated data-view data-view-fallback="type""""), "a view-swap.js region")
        assertTrue(source.contains("""<nav class="unrated-tabs" data-view-links="type""""), "the tabs are its links")
        assertTrue(resource("/templates/fragments/ratings-switch.html").contains("""<script src="/view-swap.js" defer></script>"""), "loaded on the page")

        val js = resource("/static/ratings-unrated.js")
        assertFalse(js.contains("const section = document.querySelector('[data-unrated]');\n    if (!section) return;"), "looked up per event")
        assertTrue(js.contains("const section = document.querySelector('[data-unrated]');"))
    }
}
