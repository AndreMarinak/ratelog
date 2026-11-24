package org.ratelog.stats.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.stats.FakeStatsRepository
import org.ratelog.stats.GetStats
import org.ratelog.stats.GetStatsHandler
import org.ratelog.stats.RecordingRuntimeFiller
import org.ratelog.stats.StatsFixtures
import org.ratelog.stats.StatsPeriod
import org.ratelog.stats.StatsSource
import org.ratelog.test.UserFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** The page's view model and the real template with the real messages (MessageFormat quoting included). */
class StatsPageTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), StatsFixtures.NY)
    private val today = LocalDate.of(2026, 11, 23)
    private val user = UserFactory.aUser(id = 1)
    private val drama = 18
    private val animation = 16
    private val horror = 27

    private val source = StatsSource(
        movies = listOf(
            StatsFixtures.movie(278, 10.0, "2026-04-22T20:00", genres = listOf(drama), year = 1994, runtime = 142),
            StatsFixtures.movie(129, 9.0, "2026-07-04T20:00", genres = listOf(animation), year = 2001, runtime = 125),
            StatsFixtures.movie(862, 8.0, "2026-07-15T20:00", genres = listOf(animation), year = 1995, runtime = null, pending = true),
            StatsFixtures.movie(12, 9.0, "2026-07-28T20:00", genres = listOf(animation), year = 2003, runtime = 100),
            StatsFixtures.movie(694, 6.0, "2026-09-12T20:00", genres = listOf(horror, drama), year = 1980, runtime = 146),
            StatsFixtures.movie(539, 5.0, "2026-09-26T20:00", genres = listOf(horror), year = 1960, runtime = 109),
            StatsFixtures.movie(348, 7.0, "2026-10-10T20:00", genres = listOf(horror), year = 1979, runtime = 117),
            StatsFixtures.movie(101, null, "2026-06-30T19:00", genres = listOf(drama), year = 1994, runtime = 110),
            StatsFixtures.movie(19995, 6.0, "2025-01-15T12:00", genres = listOf(drama), year = 2009, runtime = 162),
        ),
        shows = listOf(StatsFixtures.show(1396, genres = listOf(drama), year = 2008), StatsFixtures.show(67070, watchedAt = "2026-10-01T19:00")),
        seasons = listOf(StatsFixtures.season(1396, 1, 9.0, "2026-03-05T21:00"), StatsFixtures.season(1396, 2, 10.0, "2026-04-10T21:00")),
    )
    private val repo = FakeStatsRepository(source)
    private val handler = GetStatsHandler(repo, RecordingRuntimeFiller(), clock)

    private fun view(period: StatsPeriod): StatsView =
        StatsController.view(handler.handle(GetStats(user.id!!, period, Lang.en)).getOrNull()!!, today, Locale.ENGLISH)

    @Test
    fun `the year switcher - this year, earlier years with data, All time - this year links to plain stats`() {
        val v = view(StatsPeriod.Year(2025))

        assertEquals(listOf("2026", "2025", "all"), v.switcher.map { it.period })
        assertEquals(listOf("/stats", "/stats?year=2025", "/stats?year=all"), v.switcher.map { it.href })
        assertEquals(listOf(false, true, false), v.switcher.map { it.active })
        assertEquals("stats.year.all", v.switcher.last().labelKey)
    }

    @Test
    fun `an empty year is in the switcher too and points to the newest year with data`() {
        val v = view(StatsPeriod.Year(2019))

        assertTrue(v.empty)
        assertEquals(listOf("2026", "2025", "2019", "all"), v.switcher.map { it.period })
        assertEquals("/stats", v.emptyLink?.href)
        assertEquals("2026", v.emptyLink?.label)
        assertFalse(view(StatsPeriod.Year(2026)).empty)
    }

    @Test
    fun `headline, bars and rows for a year`() {
        val v = view(StatsPeriod.Year(2026))
        val h = v.headline

        assertEquals(8, h.moviesSeen)
        assertEquals(2, h.showsSeen)
        assertEquals(2, h.seasonsRated)
        assertEquals(9, h.ratings)
        // (10 + 9 + 8 + 9 + 6 + 5 + 7 + 9.5) / 8
        assertEquals("7.9", h.average)
        // 142 + 125 + 100 + 146 + 109 + 117 + 110 minutes
        assertEquals("14", h.hours)
        assertEquals(7, h.runtimeKnown)
        assertTrue(h.runtimePartial)
        assertTrue(h.runtimePending)

        assertEquals((1..10).map { it.toString() }, v.distribution.map { it.label })
        assertEquals(100, v.distribution.single { it.label == "9" }.percent)
        assertEquals(0, v.distribution.single { it.label == "1" }.percent)

        assertEquals(12, v.timeline.size)
        assertEquals("Jan", v.timeline[0].label)
        assertEquals("J", v.timeline[0].shortLabel)
        assertEquals("July 2026", v.timeline[6].fullLabel)
        assertEquals(3, v.timeline[6].movies)
        assertEquals(100, v.timeline[6].moviesPercent)
        assertFalse(v.timelineByYear)

        assertEquals(listOf("genre.18", "genre.16", "genre.27"), v.genres.map { it.labelKey })
        assertEquals("genre.16", v.generous?.labelKey)
        assertEquals("genre.27", v.harshest?.labelKey)
        assertEquals("1960s", v.decades.first().label)

        assertEquals(listOf(278, 1396, 12, 129, 862), v.top.take(5).map { it.actions.tmdbId })
        assertEquals("/tv/1396", v.top[1].href)
        assertEquals("9.5", v.top[1].score)
        assertEquals(2, v.top[1].rank)
        assertTrue(v.top[0].actions.seen)
        assertEquals(10.0, v.top[0].actions.score)
    }

    @Test
    fun `all time is year by year`() {
        val v = view(StatsPeriod.AllTime)
        assertTrue(v.timelineByYear)
        assertEquals(listOf("2025", "2026"), v.timeline.map { it.label })
        assertEquals("’25", v.timeline[0].shortLabel)
        assertNull(v.year)
        assertEquals("all", v.param)
    }

    @Test
    fun `parsePeriod and the hours format`() {
        assertEquals(StatsPeriod.AllTime, StatsController.parsePeriod("all", 2026))
        assertEquals(StatsPeriod.Year(2025), StatsController.parsePeriod(" 2025 ", 2026))
        assertNull(StatsController.parsePeriod("2027", 2026))
        assertNull(StatsController.parsePeriod("abc", 2026))
        assertNull(StatsController.parsePeriod(null, 2026))
        assertEquals("3.5", StatsController.hours(210, Locale.ENGLISH))
        assertEquals("0", StatsController.hours(0, Locale.ENGLISH))
        assertEquals("1,234", StatsController.hours(1234 * 60L, Locale.ENGLISH))
    }

    @Test
    fun `the controller renders stats, junk years show this year`() {
        val controller = StatsController(handler, clock)
        val model = ExtendedModelMap()

        assertEquals("stats", controller.stats(user, "abc", model))
        assertEquals("2026", (model["stats"] as StatsView).param)
        controller.stats(user, "all", model)
        assertEquals("all", (model["stats"] as StatsView).param)
        controller.stats(user, "2031", model)
        assertEquals("2026", (model["stats"] as StatsView).param)
    }

    // ---------- Template ----------

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

    private fun render(v: StatsView): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("stats", v)
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
        }
        return engine.process("stats", setOf("view"), context)
    }

    @Test
    fun `the page - swappable region, year links, numbers, accessible tables, tiles`() {
        val html = render(view(StatsPeriod.Year(2026)))

        assertTrue(html.contains("""id="statsView""""))
        assertTrue(html.contains("data-view"))
        assertTrue(html.contains("""data-view-links="year""""))
        assertTrue(html.contains("Your 2026 in movies and TV"), "the year is not formatted as 2,026")
        assertTrue(html.contains("""href="/stats?year=2025""""))
        assertTrue(html.contains("""aria-current="page""""))
        assertTrue(html.contains("2 seasons rated"))
        assertTrue(html.contains("Movies only, based on 7 of 8 movies so far: the rest are being looked up"))
        assertTrue(html.contains("Most generous with Animation (★ 8.7), harshest on Horror (★ 6.0)."))
        // Fork (2026-11-24, rewatch log): the timeline counts watches (first watches plus rewatches).
        assertTrue(html.contains("<caption>Watches per month</caption>"))
        assertTrue(html.contains("<th scope=\"row\">July 2026</th>"))
        assertTrue(html.contains("""aria-hidden="true""""))
        assertTrue(html.contains("Highest rated in 2026"))
        assertTrue(html.contains("""data-stats-top="tv:1396""""))
        assertTrue(html.contains("#1"))
        assertTrue(html.contains("""href="/movie/278""""))
        assertFalse(html.contains("??"), "a message key is missing")
    }

    @Test
    fun `the page - All time wording and an empty year`() {
        val all = render(view(StatsPeriod.AllTime))
        assertTrue(all.contains("Everything you&#39;ve rated or marked seen"), "one apostrophe, HTML-escaped")
        assertTrue(all.contains("Year by year"))
        assertTrue(all.contains("Your highest rated"))

        val empty = render(view(StatsPeriod.Year(2019)))
        assertTrue(empty.contains("Nothing rated or marked seen in 2019 yet."))
        assertTrue(empty.contains("See 2026"))
        assertTrue(empty.contains("""href="/stats?year=all""""))
        assertFalse(empty.contains("stats-headline"))
        assertFalse(empty.contains("??"))
    }
}
