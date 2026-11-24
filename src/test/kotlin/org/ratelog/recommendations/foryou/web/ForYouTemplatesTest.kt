package org.ratelog.recommendations.foryou.web

import arrow.core.right
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.browse.BrowseProvider
import org.ratelog.browse.TmdbBrowseClient
import org.ratelog.browse.web.BrowseBarViews
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.browse.web.DiscoverRowView
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.ListSummary
import org.ratelog.home.HomeTitle
import org.ratelog.home.dashboard.DiscoverState
import org.ratelog.home.dashboard.HomeDashboard
import org.ratelog.home.dashboard.HomeRow
import org.ratelog.home.dashboard.HomeRowKind
import org.ratelog.home.dashboard.toView
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.foryou.ForYouPick
import org.ratelog.recommendations.foryou.ForYouSeed
import org.ratelog.test.MutableClock
import org.ratelog.user.User
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.context.LazyContextVariable
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.LocalDate
import java.util.Locale

/** Renders the real Home and Discover templates with the real messages: where "Recommended for you" goes, and its row. */
class ForYouTemplatesTest {

    private val messages = BrowseBarViewsTest.testMessageSource()
    private val browseClient: TmdbBrowseClient = mock()
    private val barViews = BrowseBarViews(messages, browseClient, MutableClock())
    private val views = ForYouViews(messages, barViews)
    private val appContext = StaticApplicationContext().apply {
        beanFactory.registerSingleton("browseBarViews", barViews)
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
    private val csrf = DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123")
    private val today = LocalDate.of(2026, 11, 23)

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        whenever(browseClient.providers(any(), any(), any())).thenReturn(listOf(BrowseProvider(8, "Netflix", "/n.png", 0)).right())
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(template: String, fragment: String, vars: Map<String, Any?>): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", csrf)
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, setOf(fragment), context).also { assertFalse(it.contains("??"), "every message key resolves: $it") }
    }

    private fun ids(html: String, prefix: String): List<String> =
        Regex("""<section[^>]*\bid="($prefix[^"]*)"""").findAll(html).map { it.groupValues[1] }.toList()

    private fun title(id: Int, type: MediaType = MediaType.movie) =
        HomeTitle(tmdbId = id, mediaType = type, title = "Title $id", year = 2020, posterPath = "/$id.jpg", releaseDate = today.plusDays(10))

    // --- Home ---

    @Test
    fun `Home shows the new-this-week rows, then your rows with Recommended for you after Up next, and no Classics or Popular TV`() {
        val list = ListSummary(CustomList.Id(7), User.Id(1), "me", "Heists", false, 3, emptyList(), 1)
        val rows = HomeRowKind.entries.map { kind ->
            when (kind) {
                HomeRowKind.RECOMMENDED -> HomeRow(kind, lazy = true)
                HomeRowKind.YOUR_LISTS -> HomeRow(kind, lists = listOf(list))
                else -> HomeRow(kind, titles = listOf(title(kind.ordinal + 1)))
            }
        }
        val home = HomeDashboard(rows, DiscoverState.OK).toView(Locale.ENGLISH, today)

        val html = render("fragments/home-dashboard", "dashboard", mapOf("home" to home))

        assertEquals(
            listOf(
                "row-trending-movies", "row-trending-tv", "row-in-theaters", "row-coming-soon",
                "row-continue-watching", "row-up-next", "row-recommended", "row-recently-rated", "row-your-lists",
            ),
            ids(html, "row-"),
        )
        assertFalse(html.contains("Classics"))
        assertFalse(html.contains("Popular TV"))
        // The recommended row is a placeholder the page fills from /for-you/row.
        assertTrue(Regex("""<section class="home-row discover-row for-you-row is-pending" id="row-recommended"[^>]*data-src="/for-you/row"""").containsMatchIn(html), html)
        assertTrue(html.contains(""">Recommended for you</h2>"""))
        assertEquals(8, Regex("""class="discover-skeleton"""").findAll(html).count(), "skeleton posters keep the row's height")
        assertTrue(html.contains("""<script src="/for-you.js" defer>"""))
        assertTrue(html.contains("""href="/discover.css""""), "the skeleton's styles")
    }

    @Test
    fun `Home without the recommended row has no for-you script`() {
        val home = HomeDashboard(listOf(HomeRow(HomeRowKind.TRENDING_MOVIES, titles = listOf(title(1)))), DiscoverState.OK).toView(Locale.ENGLISH, today)

        val html = render("fragments/home-dashboard", "dashboard", mapOf("home" to home))

        assertFalse(html.contains("for-you"))
    }

    // --- Discover ---

    private fun discoverRow(id: String, more: String? = "/discover") =
        DiscoverRowView(id = id, title = id, moreHref = more, srcHref = "/discover/row?row=$id", tiles = emptyList())

    private fun <T> lazy(value: T) = object : LazyContextVariable<T>() {
        override fun loadValue(): T = value
    }

    @Test
    fun `Discover shows Popular now, Recommended for you, Official lists, then Classics and the genres`() {
        val html = render(
            "fragments/discover-rows", "rows",
            mapOf(
                "rows" to listOf(discoverRow("discover-row-popular"), discoverRow("discover-row-classics"), discoverRow("discover-row-horror")),
                "forYouRow" to lazy(ForYouViews.placeholder(MediaType.movie)),
                "officialListsRow" to lazy(null),
            ),
        )

        assertEquals(
            listOf("discover-row-popular", "discover-row-recommended", "discover-row-classics", "discover-row-horror"),
            ids(html, "discover-row-"),
        )
        // discover.js loads it like any pending row; Discover has its own loader, so no for-you.js here.
        assertTrue(html.contains("""data-src="/for-you/row?type=movie""""), html)
        assertFalse(html.contains("for-you.js"))
    }

    @Test
    fun `the recommended row sits before Official lists, and is absent without ratings`() {
        val card = org.ratelog.customlist.browse.ListCardView("/list/7", "AFI 100", 25, true, null, listOf(null, null, null, null), 3, isOfficial = true)
        val official = org.ratelog.customlist.official.web.OfficialListsRowView(listOf(card), total = 1, id = "discover-official-lists")
        val vars = mapOf(
            "rows" to listOf(discoverRow("discover-row-popular"), discoverRow("discover-row-drama")),
            "officialListsRow" to lazy(official),
        )

        val withRow = render("fragments/discover-rows", "rows", vars + ("forYouRow" to lazy(ForYouViews.placeholder(MediaType.tvshow))))
        val recommended = withRow.indexOf("""id="discover-row-recommended"""")
        val popular = withRow.indexOf("""id="discover-row-popular"""")
        val drama = withRow.indexOf("""id="discover-row-drama"""")
        assertTrue(popular in 0 until recommended && recommended < drama, "popular=$popular recommended=$recommended drama=$drama")
        assertTrue(withRow.contains("""data-src="/for-you/row?type=tv""""))
        val officialAt = withRow.indexOf("""id="discover-official-lists"""")
        assertTrue(recommended in 0 until officialAt, "before Official lists: recommended=$recommended official=$officialAt")

        val without = render("fragments/discover-rows", "rows", vars + ("forYouRow" to lazy(null)))
        assertFalse(without.contains("discover-row-recommended"))
    }

    // --- The loaded row ---

    @Test
    fun `the loaded row reuses Discover's tiles and quick actions, without a More link`() {
        val seed = ForYouSeed(603, MediaType.movie, "The Matrix", 9.0, 1)
        val picks = listOf(
            ForYouPick(RelatedTitle(604, MediaType.movie, "The Matrix Reloaded", LocalDate.parse("2003-05-15"), "/r.jpg", 7.0, 9000), seed),
            ForYouPick(RelatedTitle(1399, MediaType.tvshow, "Game of Thrones", LocalDate.parse("2011-04-17"), "/g.jpg", 8.4, 20000), seed),
        )

        val html = render("fragments/discover-rows", "lazyRow", mapOf("row" to views.row(null, picks, Locale.ENGLISH)))

        assertTrue(html.contains("""<section class="home-row discover-row" id="row-recommended""""), html)
        assertTrue(html.contains(""">Recommended for you</h2>"""))
        assertFalse(html.contains("home-row-more"), "no More link")
        assertFalse(html.contains("is-pending"))
        assertTrue(html.contains("""href="/movie/604""""))
        assertTrue(html.contains("""href="/tv/1399""""))
        assertTrue(html.contains("data-tile-actions"), "the same quick actions as every row")
        assertEquals(2, Regex("""class="home-tile-wrap"""").findAll(html).count())
    }
}
