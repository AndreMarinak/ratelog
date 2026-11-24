package org.ratelog.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import java.time.LocalDateTime
import java.time.ZoneId

/** Builders for the stats source rows, times in New York unless a zone is given. */
object StatsFixtures {
    val NY: ZoneId = ZoneId.of("America/New_York")

    fun ms(local: String, zone: ZoneId = NY): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    fun movie(
        id: Int,
        score: Double?,
        at: String,
        genres: List<Int> = emptyList(),
        year: Int? = 2000,
        runtime: Int? = null,
        pending: Boolean = false,
    ) = StatsMovie(id, score, rated = score != null, atMs = ms(at), genreIds = genres, releaseYear = year, runtimeMinutes = runtime, runtimePending = pending)

    fun show(id: Int, genres: List<Int> = emptyList(), year: Int? = 2010, showScore: Double? = null, showAt: String? = null, watchedAt: String? = null) =
        StatsShow(id, genres, year, showScore, showAt?.let(::ms), watchedAt?.let(::ms))

    fun season(id: Int, n: Int, score: Double, at: String) = StatsSeason(id, n, score, ms(at))
}

class StatsCalculatorTest {
    private val ny = StatsFixtures.NY

    private fun calc(source: StatsSource, period: StatsPeriod, zone: ZoneId = ny) = StatsCalculator.calculate(source, period, zone)
    private fun source(movies: List<StatsMovie> = emptyList(), shows: List<StatsShow> = emptyList(), seasons: List<StatsSeason> = emptyList()) =
        StatsSource(movies, shows, seasons)

    @Test
    fun `a year is the calendar year in the app's time zone, both edges included or excluded correctly`() {
        val s = source(
            movies = listOf(
                StatsFixtures.movie(1, 8.0, "2025-12-31T23:30"), // 04:30 UTC on Jan 1: still 2025 in New York
                StatsFixtures.movie(2, 7.0, "2026-01-01T00:00"), // the first instant of 2026
                StatsFixtures.movie(3, 9.0, "2026-12-31T23:59"),
                StatsFixtures.movie(4, 6.0, "2027-01-01T00:00"),
            ),
        )

        assertEquals(listOf(2, 3), StatsCalculator.entries(s, StatsPeriod.Year(2026), ny).map { it.tmdbId })
        assertEquals(listOf(1), StatsCalculator.entries(s, StatsPeriod.Year(2025), ny).map { it.tmdbId })
        // The same instants counted in UTC would move movie 1 into 2026 and movie 3 out of it.
        assertEquals(listOf(1, 2), StatsCalculator.entries(s, StatsPeriod.Year(2026), ZoneId.of("UTC")).map { it.tmdbId })
        assertEquals(listOf(2027, 2026, 2025), StatsCalculator.yearsWithData(s, ny))
    }

    @Test
    fun `all time counts everything, watched-unrated movies are seen but not rated`() {
        val s = source(
            movies = listOf(
                StatsFixtures.movie(1, 8.0, "2024-03-01T12:00"),
                StatsFixtures.movie(2, 6.0, "2026-05-01T12:00"),
                StatsFixtures.movie(3, null, "2026-06-01T12:00"),
            ),
        )
        val all = calc(s, StatsPeriod.AllTime)

        assertEquals(3, all.totals.moviesSeen)
        assertEquals(2, all.totals.moviesRated)
        assertEquals(2, all.totals.ratings)
        assertEquals(7.0, all.totals.averageScore)
        assertEquals(2, all.distribution.sumOf { it.count })
        // All time is year by year, every year from the first to the last (2025 is an empty bar).
        assertEquals(listOf(2024 to 1, 2025 to 0, 2026 to 2), all.timeline.map { it.key to it.total })
        assertEquals(StatsPeriod.AllTime, all.period)
    }

    @Test
    fun `TV - a show's score is the average of the seasons rated in the period, counted once`() {
        val s = source(
            shows = listOf(StatsFixtures.show(1396, showScore = 6.0, showAt = "2024-01-01T12:00")),
            seasons = listOf(
                StatsFixtures.season(1396, 1, 9.0, "2026-03-05T21:00"),
                StatsFixtures.season(1396, 2, 10.0, "2026-04-10T21:00"),
                StatsFixtures.season(1396, 3, 8.0, "2025-06-01T21:00"),
            ),
        )

        val y2026 = calc(s, StatsPeriod.Year(2026))
        assertEquals(1, y2026.totals.showsSeen)
        assertEquals(2, y2026.totals.seasonsRated)
        assertEquals(2, y2026.totals.ratings)
        assertEquals(9.5, y2026.top.single().score)
        // It counts in the month of its last rated season.
        assertEquals(1, y2026.timeline.single { it.key == 4 }.shows)
        assertEquals(0, y2026.timeline.single { it.key == 3 }.shows)

        assertEquals(8.0, calc(s, StatsPeriod.Year(2025)).top.single().score)
        // All time: every rated season, like tv_ratings.score.
        assertEquals(9.0, calc(s, StatsPeriod.AllTime).top.single().score)
        // Rated seasons win: the whole-show score (2024) is unused, so 2024 has nothing.
        assertTrue(calc(s, StatsPeriod.Year(2024)).isEmpty)
        assertEquals(listOf(2026, 2025), StatsCalculator.yearsWithData(s, ny))
    }

    @Test
    fun `TV - a whole-show score counts when no season is rated, a Watched show without a score counts as seen`() {
        val s = source(
            shows = listOf(
                StatsFixtures.show(2316, showScore = 7.0, showAt = "2026-08-20T21:00", watchedAt = "2026-08-20T21:00"),
                StatsFixtures.show(67070, watchedAt = "2026-10-01T19:00"),
                StatsFixtures.show(87108, showScore = 10.0, showAt = "2025-09-09T21:00"),
            ),
        )

        val y = calc(s, StatsPeriod.Year(2026))
        assertEquals(2, y.totals.showsSeen)
        assertEquals(1, y.totals.showsRated)
        assertEquals(0, y.totals.seasonsRated)
        assertEquals(1, y.totals.ratings)
        assertEquals(7.0, y.totals.averageScore)
        assertEquals(listOf(2316), y.top.map { it.tmdbId })
        assertTrue(y.top.single().wholeShow)
        assertEquals(1, y.timeline.single { it.key == 10 }.shows)

        assertEquals(listOf(87108), calc(s, StatsPeriod.Year(2025)).top.map { it.tmdbId })
        assertEquals(listOf(2026, 2025), StatsCalculator.yearsWithData(s, ny))
    }

    @Test
    fun `scores are bucketed to the nearest whole point`() {
        assertEquals(8, StatsCalculator.bucket(7.5))
        assertEquals(7, StatsCalculator.bucket(7.25))
        assertEquals(8, StatsCalculator.bucket(7.75))
        assertEquals(10, StatsCalculator.bucket(10.0))
        assertEquals(1, StatsCalculator.bucket(0.0))
        assertEquals(1, StatsCalculator.bucket(1.4))
        assertEquals(10, StatsCalculator.bucket(9.5))

        val s = source(movies = listOf(StatsFixtures.movie(1, 7.5, "2026-01-02T12:00"), StatsFixtures.movie(2, 7.75, "2026-01-02T12:00"), StatsFixtures.movie(3, 3.0, "2026-01-02T12:00")))
        val d = calc(s, StatsPeriod.Year(2026)).distribution
        assertEquals((1..10).toList(), d.map { it.score })
        assertEquals(2, d.single { it.score == 8 }.count)
        assertEquals(1, d.single { it.score == 3 }.count)
    }

    @Test
    fun `months - twelve buckets split into movies and TV`() {
        val s = source(
            movies = listOf(StatsFixtures.movie(1, 8.0, "2026-01-31T23:59"), StatsFixtures.movie(2, null, "2026-02-01T00:01"), StatsFixtures.movie(3, 9.0, "2026-02-14T12:00")),
            shows = listOf(StatsFixtures.show(10, watchedAt = "2026-02-20T12:00")),
        )
        val t = calc(s, StatsPeriod.Year(2026)).timeline

        assertEquals((1..12).toList(), t.map { it.key })
        assertEquals(TimelineBucket(1, 1, 0), t[0])
        assertEquals(TimelineBucket(2, 2, 1), t[1])
        assertEquals(0, t.drop(2).sumOf { it.total })
    }

    @Test
    fun `genres - count, average of the rated ones, a title counts in each genre, generous and harshest need 3 rated`() {
        val drama = 18
        val horror = 27
        val comedy = 35
        val s = source(
            movies = listOf(
                StatsFixtures.movie(1, 9.0, "2026-01-02T12:00", genres = listOf(drama, horror)),
                StatsFixtures.movie(2, 8.0, "2026-01-02T12:00", genres = listOf(drama)),
                StatsFixtures.movie(3, 7.0, "2026-01-02T12:00", genres = listOf(drama)),
                StatsFixtures.movie(4, 4.0, "2026-01-02T12:00", genres = listOf(horror)),
                StatsFixtures.movie(5, 5.0, "2026-01-02T12:00", genres = listOf(horror)),
                StatsFixtures.movie(6, null, "2026-01-02T12:00", genres = listOf(horror)),
                StatsFixtures.movie(7, 10.0, "2026-01-02T12:00", genres = listOf(comedy)),
            ),
        )
        val stats = calc(s, StatsPeriod.Year(2026))

        assertEquals(listOf(horror, drama, comedy), stats.genres.map { it.genreId })
        val h = stats.genres.first()
        assertEquals(4, h.count)
        assertEquals(3, h.rated)
        assertEquals(6.0, h.averageScore)
        assertEquals(8.0, stats.genres[1].averageScore)
        // Comedy has a 10 but only one rated title: not enough for a verdict.
        assertEquals(drama, stats.generousGenre?.genreId)
        assertEquals(horror, stats.harshestGenre?.genreId)
    }

    @Test
    fun `no verdict with a single qualifying genre`() {
        val s = source(movies = (1..3).map { StatsFixtures.movie(it, 8.0, "2026-01-02T12:00", genres = listOf(18)) })
        val stats = calc(s, StatsPeriod.Year(2026))
        assertNull(stats.generousGenre)
        assertNull(stats.harshestGenre)
    }

    @Test
    fun `decades by release year, oldest first, unknown years left out`() {
        val s = source(
            movies = listOf(
                StatsFixtures.movie(1, 8.0, "2026-01-02T12:00", year = 1999),
                StatsFixtures.movie(2, 6.0, "2026-01-02T12:00", year = 1990),
                StatsFixtures.movie(3, 9.0, "2026-01-02T12:00", year = 1972),
                StatsFixtures.movie(4, 9.0, "2026-01-02T12:00", year = null),
            ),
            shows = listOf(StatsFixtures.show(10, year = 2008, watchedAt = "2026-03-01T12:00")),
        )
        val d = calc(s, StatsPeriod.Year(2026)).decades

        assertEquals(listOf(1970 to 1, 1990 to 2, 2000 to 1), d.map { it.decade to it.count })
        assertEquals(7.0, d[1].averageScore)
        assertNull(d[2].averageScore)
    }

    @Test
    fun `highest rated - score, then newest, at most ten, movies and shows together`() {
        val movies = (1..12).map { StatsFixtures.movie(it, if (it <= 2) 9.5 else 5.0 + it * 0.1, "2026-01-%02dT12:00".format(it)) }
        val s = source(movies = movies, shows = listOf(StatsFixtures.show(99, showScore = 10.0, showAt = "2026-02-01T12:00")))
        val top = calc(s, StatsPeriod.Year(2026)).top

        assertEquals(10, top.size)
        assertEquals(MediaType.tvshow to 99, top[0].type to top[0].tmdbId)
        // Movies 1 and 2 tie at 9.5: the newer rating first.
        assertEquals(listOf(2, 1, 12, 11), top.drop(1).take(4).map { it.tmdbId })
    }

    @Test
    fun `hours - only known movie runtimes, with how many are known and pending`() {
        val s = source(
            movies = listOf(
                StatsFixtures.movie(1, 8.0, "2026-01-02T12:00", runtime = 136),
                StatsFixtures.movie(2, null, "2026-01-02T12:00", runtime = 100),
                StatsFixtures.movie(3, 7.0, "2026-01-02T12:00", runtime = null, pending = true),
                StatsFixtures.movie(4, 7.0, "2026-01-02T12:00", runtime = null, pending = false),
            ),
        )
        val t = calc(s, StatsPeriod.Year(2026)).totals

        assertEquals(236, t.runtimeMinutes)
        assertEquals(2, t.runtimeKnown)
        assertEquals(1, t.runtimePending)
        assertEquals(4, t.moviesSeen)
    }

    @Test
    fun `an empty year is empty but still has twelve zero months and ten zero buckets`() {
        val s = source(movies = listOf(StatsFixtures.movie(1, 8.0, "2026-01-02T12:00")))
        val empty = calc(s, StatsPeriod.Year(2024))

        assertTrue(empty.isEmpty)
        assertNull(empty.totals.averageScore)
        assertEquals(12, empty.timeline.size)
        assertEquals(0, empty.timeline.sumOf { it.total })
        assertEquals(0, empty.distribution.sumOf { it.count })
        assertTrue(empty.top.isEmpty())
        assertEquals(listOf(2026), empty.years)
        assertFalse(calc(s, StatsPeriod.AllTime).isEmpty)
        assertTrue(calc(source(), StatsPeriod.AllTime).timeline.isEmpty())
    }

    @Test
    fun `genre strings are parsed leniently`() {
        assertEquals(listOf(28, 12), StatsRepositoryImpl.parseGenres("28, 12,28,Action,"))
        assertEquals(emptyList<Int>(), StatsRepositoryImpl.parseGenres(null))
    }
}
