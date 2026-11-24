package org.ratelog.api.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.ratelog.api.ApiException
import org.ratelog.stats.FakeStatsRepository
import org.ratelog.stats.GetStatsHandler
import org.ratelog.stats.RecordingRuntimeFiller
import org.ratelog.stats.StatsFixtures
import org.ratelog.stats.StatsSource
import org.ratelog.test.UserFactory
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant

class ApiStatsControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), StatsFixtures.NY)
    private val user = UserFactory.aUser(id = 1)
    private val messages = ResourceBundleMessageSource().apply {
        setBasenames("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    private val repo = FakeStatsRepository(
        StatsSource(
            movies = listOf(
                StatsFixtures.movie(603, 9.0, "2026-01-01T00:15", genres = listOf(878, 28), year = 1999, runtime = 136),
                StatsFixtures.movie(438631, 7.5, "2025-12-31T23:30", genres = listOf(878), year = 2021, runtime = 155),
                StatsFixtures.movie(101, null, "2026-06-30T19:00", genres = listOf(80), year = 1994, runtime = null, pending = true),
            ),
            shows = listOf(StatsFixtures.show(2316, genres = listOf(35), year = 2005, showScore = 7.0, showAt = "2026-08-20T21:00")),
            seasons = emptyList(),
        ),
    )
    private val controller = ApiStatsController(GetStatsHandler(repo, RecordingRuntimeFiller(), clock), messages)

    @Test
    fun `no year is this year, with the page's numbers`() {
        val r = controller.stats(user, null)

        assertEquals("2026", r.year)
        assertEquals(listOf(2026, 2025), r.years)
        assertEquals("America/New_York", r.timeZone)
        assertEquals(StatsTotalsDto(moviesSeen = 2, moviesRated = 1, showsSeen = 1, showsRated = 1, seasonsRated = 0, ratings = 2, averageScore = 8.0), r.totals)
        assertEquals(StatsHoursDto(hours = 2.3, minutes = 136, moviesWithRuntime = 1, pending = 1), r.hours)
        assertFalse(r.hours.includesTv)
        assertEquals(10, r.distribution.size)
        assertEquals(1, r.distribution.single { it.score == 9 }.count)
        assertEquals(12, r.timeline.size)
        assertEquals(StatsTimelineDto(month = 1, year = null, movies = 1, shows = 0, total = 1), r.timeline[0])
        assertEquals(StatsGenreDto(878, "Science Fiction", 1, 1, 9.0), r.genres.first { it.id == 878 })
        assertEquals("Comedy", r.genres.first { it.id == 35 }.name)
        assertNull(r.generousGenre)
        assertEquals(listOf("movie:603", "tv:2316"), r.top.map { "${it.type}:${it.tmdbId}" })
        assertEquals("/tv/2316", r.top[1].url)
        assertEquals("https://image.tmdb.org/t/p/w342/p603.jpg", r.top[0].posterUrl)
        assertEquals("2026-01-01T05:15:00Z", r.top[0].ratedAt)
    }

    @Test
    fun `a year, or all, by year`() {
        assertEquals(1, controller.stats(user, "2025").totals.moviesSeen)
        val all = controller.stats(user, "all")
        assertEquals("all", all.year)
        assertEquals(listOf(2025, 2026), all.timeline.map { it.year })
        assertEquals(listOf(null, null), all.timeline.map { it.month })
        assertEquals(3, all.totals.moviesSeen)
        assertEquals(listOf(1990, 2000, 2020), all.decades.map { it.decade })
    }

    @Test
    fun `a bad year is 400 invalid_year`() {
        for (bad in listOf("2027", "1899", "abc", "20x6")) {
            val e = assertThrows(ApiException::class.java) { controller.stats(user, bad) }
            assertEquals(HttpStatus.BAD_REQUEST, e.status)
            assertEquals("invalid_year", e.code)
        }
    }
}
