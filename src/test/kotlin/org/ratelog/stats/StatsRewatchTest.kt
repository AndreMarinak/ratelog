package org.ratelog.stats

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.stats.web.StatsController
import org.ratelog.test.UserFactory
import org.ratelog.user.User
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * Fork (2026-11-24, rewatch log): a rewatch counts in the period it happened. Titles once per period (first watches plus
 * titles rewatched in it), each rewatch adds its runtime, the timeline counts watches, All time lists the most rewatched.
 */
class StatsRewatchTest {
    private val ny = StatsFixtures.NY

    private fun rewatch(type: MediaType, id: Int, at: String, runtime: Int? = null, pending: Boolean = false) =
        StatsRewatch(type, id, StatsFixtures.ms(at), runtimeMinutes = runtime, runtimePending = pending)

    // Heat first seen (rated) in 2024 and rewatched twice in 2026; Dune seen and rewatched in 2026; The Bear watched in 2025,
    // rewatched in 2026.
    private val source = StatsSource(
        movies = listOf(
            StatsFixtures.movie(949, 9.0, "2024-05-01T20:00", runtime = 170),
            StatsFixtures.movie(438631, 8.0, "2026-02-01T20:00", runtime = 155),
        ),
        shows = listOf(StatsFixtures.show(136315, watchedAt = "2025-06-01T20:00")),
        seasons = emptyList(),
        rewatches = listOf(
            rewatch(MediaType.movie, 949, "2026-03-10T20:00", runtime = 170),
            rewatch(MediaType.movie, 949, "2026-11-01T20:00", runtime = 170),
            rewatch(MediaType.movie, 438631, "2026-02-20T20:00", runtime = 155),
            rewatch(MediaType.tvshow, 136315, "2026-03-15T20:00"),
        ),
    )

    @Test
    fun `a year counts each title once, first watches plus titles rewatched in it, and says how many rewatches`() {
        val t = StatsCalculator.calculate(source, StatsPeriod.Year(2026), ny).totals
        assertEquals(2, t.moviesSeen, "Dune (first watch) and Heat (rewatched, first seen in 2024), each once")
        assertEquals(1, t.showsSeen, "The Bear, rewatched")
        assertEquals(3, t.titles)
        assertEquals(4, t.rewatches)
        assertEquals(1, t.moviesRated, "a rewatch is not a rating")
        assertEquals(1, t.ratings)
        assertEquals(8.0, t.averageScore)
        // Dune 155 + its rewatch 155 + Heat's two rewatches 170 + 170; TV is never in the hours.
        assertEquals(650L, t.runtimeMinutes)
        assertEquals(2, t.runtimeKnown)
    }

    @Test
    fun `earlier years keep their own counts, all time counts every rewatch`() {
        val y2024 = StatsCalculator.calculate(source, StatsPeriod.Year(2024), ny).totals
        assertEquals(1, y2024.titles)
        assertEquals(0, y2024.rewatches)
        assertEquals(170L, y2024.runtimeMinutes)

        val all = StatsCalculator.calculate(source, StatsPeriod.AllTime, ny)
        assertEquals(3, all.totals.titles, "each title once")
        assertEquals(4, all.totals.rewatches)
        assertEquals(170L + 155 + 170 + 170 + 155, all.totals.runtimeMinutes)
        assertEquals(listOf(2026, 2025, 2024), all.years)
    }

    @Test
    fun `a year with only a rewatch is not empty and is in the switcher`() {
        val onlyRewatch = StatsSource(
            movies = listOf(StatsFixtures.movie(949, 9.0, "2024-05-01T20:00", runtime = 170)),
            shows = emptyList(), seasons = emptyList(),
            rewatches = listOf(rewatch(MediaType.movie, 949, "2025-01-05T20:00", runtime = 170)),
        )
        val stats = StatsCalculator.calculate(onlyRewatch, StatsPeriod.Year(2025), ny)
        assertFalse(stats.isEmpty)
        assertEquals(1, stats.totals.moviesSeen)
        assertEquals(0, stats.totals.moviesRated)
        assertEquals(listOf(2025, 2024), stats.years)
        assertTrue(stats.distribution.all { it.count == 0 }, "no rating in 2025")
    }

    @Test
    fun `the timeline counts watches - each first watch and each rewatch in its month`() {
        val timeline = StatsCalculator.calculate(source, StatsPeriod.Year(2026), ny).timeline
        assertEquals(TimelineBucket(2, 2, 0), timeline[1], "Dune's first watch and its rewatch in February")
        assertEquals(TimelineBucket(3, 1, 1), timeline[2], "Heat and The Bear rewatched in March")
        assertEquals(TimelineBucket(11, 1, 0), timeline[10])
        assertEquals(5, timeline.sumOf { it.total }, "1 first watch + 4 rewatches")
    }

    @Test
    fun `most rewatched is for all time only, most rewatches first, then the latest`() {
        val all = StatsCalculator.calculate(source, StatsPeriod.AllTime, ny).mostRewatched
        assertEquals(listOf(949, 136315, 438631), all.map { it.tmdbId }, "Heat 2, then the Bear (Mar 15) before Dune (Feb 20)")
        assertEquals(3, all.first().watchCount)
        assertTrue(StatsCalculator.calculate(source, StatsPeriod.Year(2026), ny).mostRewatched.isEmpty())

        val many = StatsSource(emptyList(), emptyList(), emptyList(), (1..8).map { rewatch(MediaType.movie, it, "2026-01-0${it.coerceAtMost(9)}T10:00") })
        assertEquals(StatsCalculator.REWATCHED_LIMIT, StatsCalculator.calculate(many, StatsPeriod.AllTime, ny).mostRewatched.size)
    }

    @Test
    fun `a rewatched movie without a runtime is queued for a lookup, and the most rewatched get their titles`() {
        val clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), ny)
        val repo = FakeStatsRepository(
            StatsSource(
                movies = listOf(StatsFixtures.movie(949, 9.0, "2024-05-01T20:00", runtime = 170)),
                shows = emptyList(), seasons = emptyList(),
                rewatches = listOf(
                    rewatch(MediaType.movie, 949, "2026-03-10T20:00", runtime = 170),
                    rewatch(MediaType.movie, 680, "2026-04-10T20:00", runtime = null, pending = true),
                ),
            ),
        )
        val filler = RecordingRuntimeFiller()
        val result = GetStatsHandler(repo, filler, clock).handle(GetStats(User.Id(1), StatsPeriod.AllTime, Lang.en)).getOrNull()!!
        assertEquals(listOf(listOf(680)), filler.calls)
        assertEquals(listOf(680, 949), result.mostRewatched.map { it.title.tmdbId }, "one each: the latest first")
        assertEquals("Title 680", result.mostRewatched.first().title.title)
    }
}

/** The Stats page with rewatches: the headline line and All time's "Most rewatched", from the real template. */
class StatsRewatchPageTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), StatsFixtures.NY)
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
    private val repo = FakeStatsRepository(
        StatsSource(
            movies = listOf(StatsFixtures.movie(949, 9.0, "2024-05-01T20:00", runtime = 170)),
            shows = emptyList(), seasons = emptyList(),
            rewatches = listOf(
                StatsRewatch(MediaType.movie, 949, StatsFixtures.ms("2026-03-10T20:00"), runtimeMinutes = 170),
                StatsRewatch(MediaType.movie, 949, StatsFixtures.ms("2026-04-10T20:00"), runtimeMinutes = 170),
            ),
        ),
    )
    private val handler = GetStatsHandler(repo, RecordingRuntimeFiller(), clock)

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(period: StatsPeriod): String {
        val view = StatsController.view(handler.handle(GetStats(UserFactory.aUser(id = 1).id!!, period, Lang.en)).getOrNull()!!, LocalDate.of(2026, 11, 23), Locale.ENGLISH)
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            setVariable("stats", view)
        }
        return engine.process("stats", setOf("view"), context)
    }

    @Test
    fun `a year says how many titles and rewatches, and has no Most rewatched`() {
        val html = render(StatsPeriod.Year(2026))
        assertTrue(html.contains(">1 title<"), html)
        assertTrue(html.contains(" · 2 rewatches"))
        assertTrue(html.contains("Watches per month"))
        assertFalse(html.contains("Most rewatched"))
        assertFalse(html.contains("??"))
    }

    @Test
    fun `all time lists the most rewatched with the watch count`() {
        val html = render(StatsPeriod.AllTime)
        assertTrue(html.contains("Most rewatched"), html)
        assertTrue(html.contains("Seen 3×"))
        assertTrue(html.contains("""href="/movie/949""""))
        assertTrue(html.contains("https://image.tmdb.org/t/p/w92/p949.jpg"))
        assertTrue(html.contains("Watches per year"))
    }
}
