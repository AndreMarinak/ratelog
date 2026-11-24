package org.ratelog.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import java.time.Clock
import java.time.Instant

/** Canned rows, plus a record of the title lookups. */
class FakeStatsRepository(var source: StatsSource) : StatsRepository {
    val titleLookups = mutableListOf<Pair<MediaType, Collection<Int>>>()
    var loads = 0

    override fun load(userId: User.Id, nowMs: Long): StatsSource {
        loads++
        return source
    }

    override fun titles(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>, lang: Lang): List<StatsTitle> {
        if (tmdbIds.isEmpty()) return emptyList()
        titleLookups += type to tmdbIds.toList()
        return tmdbIds.map { StatsTitle(it, type, "Title $it", 2000, null, "/p$it.jpg", if (type == MediaType.tvshow) WatchStatus.WATCHING else WatchStatus.WATCHED) }
    }
}

class RecordingRuntimeFiller : RuntimeFiller {
    val calls = mutableListOf<List<Int>>()
    override fun fill(tmdbIds: List<Int>): Int {
        calls += tmdbIds
        return tmdbIds.size
    }
}

class GetStatsHandlerTest {
    private val user = User.Id(1)
    // 2026-11-23 22:00 in New York.
    private val clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), StatsFixtures.NY)

    private val repo = FakeStatsRepository(
        StatsSource(
            movies = listOf(
                StatsFixtures.movie(603, 9.0, "2026-02-01T12:00", runtime = 136),
                StatsFixtures.movie(550, 8.0, "2025-02-01T12:00", runtime = null, pending = true),
                StatsFixtures.movie(680, 7.0, "2026-03-01T12:00", runtime = null, pending = false),
            ),
            shows = listOf(StatsFixtures.show(1396)),
            seasons = listOf(StatsFixtures.season(1396, 1, 9.5, "2026-04-01T12:00")),
        ),
    )
    private val filler = RecordingRuntimeFiller()
    private val handler = GetStatsHandler(repo, filler, clock)

    @Test
    fun `this year by the app clock and zone, with the top titles looked up once per type`() {
        val result = handler.handle(GetStats(user, StatsPeriod.Year(2026), Lang.en)).getOrNull()!!

        assertEquals(2026, result.currentYear)
        assertEquals(2026, handler.currentYear())
        assertEquals(2, result.stats.totals.moviesSeen)
        assertEquals(listOf("tv:1396" , "movie:603", "movie:680"), result.top.map { "${if (it.title.type == MediaType.movie) "movie" else "tv"}:${it.title.tmdbId}" })
        assertEquals(listOf(MediaType.movie to listOf(603, 680), MediaType.tvshow to listOf(1396)), repo.titleLookups)
        assertEquals(StatsFixtures.NY, result.zone)
    }

    @Test
    fun `missing runtimes of every seen movie are queued, whatever the period`() {
        handler.handle(GetStats(user, StatsPeriod.Year(2026), Lang.en))
        assertEquals(listOf(listOf(550)), filler.calls)
    }

    @Test
    fun `nothing queued when every runtime is known or not due`() {
        repo.source = repo.source.copy(movies = repo.source.movies.filter { it.tmdbId != 550 })
        handler.handle(GetStats(user, StatsPeriod.AllTime, Lang.en))
        assertTrue(filler.calls.isEmpty())
    }

    @Test
    fun `a failing runtime queue never fails the page`() {
        val failing = GetStatsHandler(repo, { throw IllegalStateException("pool gone") }, clock)
        assertTrue(failing.handle(GetStats(user, StatsPeriod.AllTime, Lang.en)).isRight())
    }

    @Test
    fun `future years and years before 1900 are refused`() {
        assertEquals(GetStatsError.InvalidYear(2027), handler.handle(GetStats(user, StatsPeriod.Year(2027), Lang.en)).leftOrNull())
        assertEquals(GetStatsError.InvalidYear(1899), handler.handle(GetStats(user, StatsPeriod.Year(1899), Lang.en)).leftOrNull())
        assertEquals(0, repo.loads)
        assertTrue(handler.handle(GetStats(user, StatsPeriod.Year(1900), Lang.en)).isRight())
    }

    @Test
    fun `no rated titles, no title lookups`() {
        repo.source = StatsSource(listOf(StatsFixtures.movie(1, null, "2026-01-02T12:00")), emptyList(), emptyList())
        val result = handler.handle(GetStats(user, StatsPeriod.Year(2026), Lang.en)).getOrNull()!!
        assertTrue(result.top.isEmpty())
        assertTrue(repo.titleLookups.isEmpty())
    }
}
