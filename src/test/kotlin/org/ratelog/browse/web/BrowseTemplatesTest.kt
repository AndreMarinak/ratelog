package org.ratelog.browse.web

import arrow.core.right
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Genre
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowseProvider
import org.ratelog.browse.BrowseTitle
import org.ratelog.browse.LibraryMark
import org.ratelog.browse.TmdbBrowseClient
import org.ratelog.browse.discover.BrowseTile
import org.ratelog.test.MutableClock
import org.ratelog.watchlist.WatchStatus
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.LocalDate
import java.util.Locale

/** Renders the real templates: the bar must work when included with no model at all (e.g. on the home page). */
class BrowseTemplatesTest {

    private val messages = BrowseBarViewsTest.testMessageSource()
    private val client: TmdbBrowseClient = mock()
    private val views = BrowseBarViews(messages, client, MutableClock())
    private val appContext = StaticApplicationContext().apply {
        beanFactory.registerSingleton("browseBarViews", views)
        refresh()
    }
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
        whenever(client.providers(any(), any(), any())).thenReturn(listOf(BrowseProvider(8, "Netflix", "/n.png", 0)).right())
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(template: String, fragment: String, vars: Map<String, Any?> = emptyMap()): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, setOf(fragment), context)
    }

    @Test
    fun `given the bar is included without a model then it renders a default movie bar with search`() {
        val html = render("fragments/browse-bar", "browseBar")

        assertTrue(html.contains("""data-type="movie""""), html)
        assertTrue(html.contains("/browse-bar.css"))
        assertTrue(html.contains("/browse-bar.js"))
        assertTrue(html.contains("Browse by"))
        listOf("Year", "Rating", "Popular", "Genre", "Service", "More").forEach { assertTrue(html.contains(">$it<"), it) }
        assertTrue(html.contains("""href="/discover?type=movie&amp;genre=horror""""), "genre links")
        assertTrue(html.contains("""href="/discover?type=movie&amp;provider=8""""), "service links")
        assertTrue(html.contains("https://image.tmdb.org/t/p/w92/n.png"))
        // The header search is the only search field (the bar's "Find a title" duplicated it on Home).
        assertFalse(html.contains("bb-find"), "no second search field in the bar")
        assertFalse(html.contains("""name="q""""), "no second search field in the bar")
        assertFalse(html.contains("bb-sort"), "no Sort menu outside /browse")
        assertFalse(html.contains("??"), "every message key resolves")
    }

    @Test
    fun `given browseType tv then the default bar starts on TV`() {
        val html = render("fragments/browse-bar", "browseBar", mapOf("browseType" to "tv"))

        assertTrue(html.contains("""data-type="tv""""))
        assertTrue(html.contains("""href="/discover?type=tv&amp;genre=sci-fi-fantasy""""))
        assertFalse(html.contains("Upcoming"))
    }

    @Test
    fun `given a filter state then renders chips, active options and the Sort menu`() {
        val bar = views.build(
            BrowseFilters(genre = Genre.HORROR, provider = 8, hideRated = true),
            ProviderOptions(WatchRegion("US"), listOf(BrowseProvider(8, "Netflix", null, 0)), false),
            locale = Locale.ENGLISH,
        )

        val html = render("fragments/browse-bar", "browseBar", mapOf("browseBar" to bar))

        assertTrue(html.contains("bb-sort"))
        assertTrue(html.contains("""aria-label="Remove Horror""""))
        assertTrue(html.contains("""aria-label="Remove Netflix""""))
        assertTrue(html.contains("Clear all"))
        val genreTag = Regex("""<details[^>]*data-menu="genre"[^>]*>""").find(html)?.value
        assertTrue(genreTag != null && genreTag.contains("is-set"), genreTag ?: html)
        val yearTag = Regex("""<details[^>]*data-menu="year"[^>]*>""").find(html)?.value
        assertTrue(yearTag != null && !yearTag.contains("is-set"), yearTag ?: html)
        assertTrue(html.contains("""aria-current="true""""))
        assertFalse(html.contains("??"))
    }

    @Test
    fun `given tiles then each has a bookmark and a Seen form beside the link, never inside it`() {
        val matrix = BrowseTitle(603, MediaType.movie, "The Matrix", LocalDate.parse("1999-03-31"), "/m.jpg", 8.2, 100, emptyList(), "en")
        val tiles = views.tiles(
            listOf(
                BrowseTile(matrix, LibraryMark(null, false, WatchStatus.WANT_TO_WATCH)),
                BrowseTile(matrix.copy(tmdbId = 604, title = "Reloaded"), LibraryMark(7.0, true, WatchStatus.WATCHED)),
            ),
            Locale.ENGLISH,
        )

        val html = render(
            "discover", "results",
            mapOf(
                "tiles" to tiles,
                "nextHref" to "/discover?type=movie&page=2",
                "firstPageHref" to null,
                "_csrf" to DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"),
            ),
        )

        val bookmarks = Regex("""<form class="tile-act tile-watchlist[^"]*"[^>]*>""").findAll(html).map { it.value }.toList()
        assertEquals(2, bookmarks.size, "rendered for both, so un-seeing can bring it back")
        assertEquals(1, bookmarks.count { it.contains("hidden") }, "the Watched title's bookmark is hidden")
        assertEquals(2, Regex("""<form class="tile-act tile-seen""").findAll(html).count())
        Regex("""<a href="[^"]+" class="browse-tile-link">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach {
            assertFalse(it.groupValues[1].contains("<form"), "quick actions must not be nested in the tile link")
        }
        assertTrue(html.contains("""action="/watchlist/toggle""""))
        assertTrue(html.contains("""action="/seen/toggle""""))
        assertTrue(html.contains("""name="_csrf" value="tok123""""))
        assertTrue(html.contains("""name="tmdbId" value="603""""))
        assertTrue(html.contains("""name="type" value="movie""""))
        assertTrue(html.contains("""class="tile-act tile-watchlist is-on""""), "Want to watch is shown as set")
        assertTrue(html.contains("""aria-label="Remove from watchlist: The Matrix""""))
        assertTrue(html.contains("""aria-label="Mark as seen: The Matrix""""))
        assertTrue(html.contains("""class="tile-act tile-seen is-on is-locked""""), "rated: seen, locked")
        assertTrue(html.contains("""aria-label="Seen (you rated it): Reloaded""""))
        assertTrue(html.contains("""data-lucide="bookmark-check""""))
        assertFalse(html.contains("tile-upcoming"), "released titles get no Upcoming badge")
        assertTrue(html.contains("""href="/movie/603""""))
        assertTrue(html.contains("""data-key="movie-604""""))
        assertTrue(html.contains("Load more"))
        assertTrue(html.contains("""href="/discover?type=movie&amp;page=2""""))
        assertFalse(html.contains("??"))
    }

    @Test
    fun `given grid tiles then posters load a 2x image from srcset`() {
        val tiles = views.tiles(listOf(BrowseTile(BrowseTitle(603, MediaType.movie, "The Matrix", null, "/m.jpg", null, null, emptyList(), null), null)), Locale.ENGLISH)

        val html = render("discover", "results", mapOf("tiles" to tiles, "_csrf" to DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t")))

        assertTrue(html.contains("""srcset="https://image.tmdb.org/t/p/w342/m.jpg 342w, https://image.tmdb.org/t/p/w500/m.jpg 500w""""), html)
    }

    @Test
    fun `given landing rows then loaded rows show tiles, pending ones a skeleton and their source, all with More links`() {
        val matrix = BrowseTitle(603, MediaType.movie, "The Matrix", LocalDate.parse("1999-03-31"), "/m.jpg", 8.2, 100, emptyList(), "en")
        val loaded = DiscoverRowView(
            id = "discover-row-horror",
            title = "Horror",
            moreHref = "/discover?type=movie&genre=horror&hideSeen=0",
            srcHref = null,
            tiles = views.tiles(
                listOf(BrowseTile(matrix, null), BrowseTile(matrix.copy(tmdbId = 604, title = "Reloaded"), LibraryMark(8.0, true, WatchStatus.WATCHED))),
                Locale.ENGLISH,
            ),
        )
        val pending = DiscoverRowView("discover-row-drama", "Drama", "/discover?type=movie&genre=drama", "/discover/row?type=movie&row=drama", emptyList())

        val html = render(
            "fragments/discover-rows", "rows",
            mapOf("rows" to listOf(loaded, pending), "_csrf" to DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123")),
        )

        assertTrue(html.contains("""id="discoverRows""""))
        val horror = html.substringAfter("""id="discover-row-horror"""").substringBefore("""id="discover-row-drama"""")
        val drama = html.substringAfter("""id="discover-row-drama"""")
        assertTrue(horror.contains("""href="/discover?type=movie&amp;genre=horror&amp;hideSeen=0""""), horror)
        assertTrue(horror.contains(">More<"))
        assertTrue(horror.contains("""href="/movie/603""""))
        assertTrue(horror.contains("w500/m.jpg 500w"))
        assertFalse(horror.contains("data-src"))
        assertFalse(horror.contains("aria-busy"))
        val bookmarks = Regex("""<form class="tile-act tile-watchlist[^"]*"[^>]*>""").findAll(horror).map { it.value }.toList()
        assertEquals(1, bookmarks.count { !it.contains("hidden") }, "no bookmark on the Watched title")
        assertTrue(horror.contains("""aria-label="Add to watchlist: The Matrix""""))
        assertTrue(horror.contains("""data-title="The Matrix""""))
        assertTrue(horror.contains("""name="_csrf" value="tok123""""))
        assertTrue(horror.contains("""action="/watchlist/toggle""""))
        assertTrue(horror.contains("""action="/seen/toggle""""))
        assertTrue(horror.contains("""aria-label="Seen (you rated it): Reloaded""""), "Watched shows as the Seen eye")
        assertTrue(horror.contains(">8.0<"), "your score")
        assertTrue(horror.contains(">8.2<"), "TMDB score")
        Regex("<a class=\"home-tile\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(horror).forEach {
            assertFalse(it.groupValues[1].contains("<form"), "bookmark must not be nested in the tile link")
        }

        assertTrue(Regex("<section[^>]*class=\"home-row discover-row is-pending\"").containsMatchIn(html), html)
        assertTrue(drama.contains("""data-src="/discover/row?type=movie&amp;row=drama""""))
        assertTrue(drama.contains("""aria-busy="true""""))
        assertEquals(8, Regex("class=\"discover-skeleton\"").findAll(drama).count())
        assertTrue(drama.contains("""href="/discover?type=movie&amp;genre=drama""""), "More works before (and without) JS")
        assertFalse(html.contains("??"), "every message key resolves")
    }

    @Test
    fun `given the lazy row endpoint then it renders the row, or nothing when there is none`() {
        val row = DiscoverRowView("discover-row-crime", "Crime", "/discover?type=tv&genre=crime", null, emptyList())

        val html = render("fragments/discover-rows", "lazyRow", mapOf("row" to row, "_csrf" to DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t")))
        assertTrue(html.contains("""<section class="home-row discover-row" id="discover-row-crime""""), html)
        assertFalse(html.contains("is-pending"))

        assertEquals("", render("fragments/discover-rows", "lazyRow", mapOf("row" to null)).trim())
    }

    @Test
    fun `given a TMDB failure then the results show a friendly message with a retry link`() {
        val html = render(
            "discover", "results",
            mapOf("tiles" to emptyList<Any>(), "errorKey" to "browse.error.unreachable", "retryHref" to "/discover?type=movie"),
        )

        assertTrue(html.contains("Couldn&#39;t reach TMDB right now. Try again in a moment.") || html.contains("Couldn't reach TMDB right now."), html)
        assertTrue(html.contains("""role="alert""""))
        assertTrue(html.contains("Try again"))
        assertFalse(html.contains("Nothing matches"))
        assertFalse(html.contains("Load more"))
    }
}
