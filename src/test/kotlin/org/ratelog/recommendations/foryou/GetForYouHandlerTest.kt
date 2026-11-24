package org.ratelog.recommendations.foryou

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.browse.BrowseLibraryRepository
import org.ratelog.browse.LibraryMark
import org.ratelog.recommendations.RecommendationsPage
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TmdbRecommendationsClient
import org.ratelog.recommendations.TmdbRecommendationsError
import org.ratelog.test.MutableClock
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import java.time.Duration
import java.time.LocalDate

class GetForYouHandlerTest {

    /** The viewer's ratings, by type; the queries' orderings and limits as in ForYouSeedDAO. */
    private class FakeSeeds : ForYouSeedRepository {
        val rated = mutableListOf<ForYouSeed>()
        var reads = 0

        override fun findRecentRatedAtLeast(userId: User.Id, type: MediaType, minScore: Double, lang: Lang, limit: Int): List<ForYouSeed> {
            reads++
            return rated.filter { it.type == type && it.score >= minScore }.sortedByDescending { it.ratedAtEpochMs }.take(limit)
        }

        override fun findTopRated(userId: User.Id, type: MediaType, lang: Lang, limit: Int): List<ForYouSeed> =
            rated.filter { it.type == type }.sortedWith(compareByDescending<ForYouSeed> { it.score }.thenByDescending { it.ratedAtEpochMs }).take(limit)

        override fun hasRatings(userId: User.Id, type: MediaType): Boolean = rated.any { it.type == type }
    }

    /** Marks by (type, TMDB id). */
    private class FakeLibrary : BrowseLibraryRepository {
        val marks = mutableMapOf<Pair<MediaType, Int>, LibraryMark>()

        fun mark(type: MediaType, tmdbId: Int, status: WatchStatus? = null, score: Double? = null) {
            marks[type to tmdbId] = LibraryMark(score, score != null, status)
        }

        override fun findMarks(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>): Map<Int, LibraryMark> =
            tmdbIds.mapNotNull { id -> marks[type to id]?.let { id to it } }.toMap()
    }

    private val seeds = FakeSeeds()
    private val library = FakeLibrary()
    private val client: TmdbRecommendationsClient = mock()
    private val clock = MutableClock()
    private val handlers = mutableListOf<GetForYouHandler>()
    private val me = User.Id(1)

    private fun handler(parallel: Int = 1) = GetForYouHandler(seeds, client, library, cacheMinutes = 5, parallel = parallel, clock = clock).also { handlers += it }

    @AfterEach
    fun tearDown() = handlers.forEach { it.shutdown() }

    private fun rate(id: Int, score: Double, ratedAt: Long, type: MediaType = MediaType.movie) {
        seeds.rated += ForYouSeed(id, type, "Rated $id", score, ratedAt)
    }

    private fun rec(id: Int, type: MediaType = MediaType.movie) =
        RelatedTitle(id, type, "Title $id", LocalDate.parse("2015-01-01"), "/p$id.jpg", 7.5, 800)

    private fun serve(type: MediaType, seedId: Int, vararg ids: Int) {
        whenever(client.recommendations(eq(type), eq(seedId), any(), eq(1)))
            .thenReturn(RecommendationsPage(null, ids.map { rec(it, type) }, 1, 1).right())
    }

    private fun fail(type: MediaType, seedId: Int, error: TmdbRecommendationsError = TmdbRecommendationsError.Unavailable) {
        whenever(client.recommendations(eq(type), eq(seedId), any(), eq(1))).thenReturn(error.left())
    }

    private fun GetForYouHandler.ids(type: MediaType? = null, limit: Int = 20) =
        handle(GetForYou(me, Lang.en, type, limit)).getOrNull()!!.picks.map { it.title.tmdbId }

    @Test
    fun `no ratings means no row and no TMDB calls`() {
        val result = handler().handle(GetForYou(me, Lang.en, type = null)).getOrNull()!!

        assertEquals(emptyList<ForYouSeed>(), result.seeds)
        assertEquals(emptyList<ForYouPick>(), result.picks)
        verify(client, never()).recommendations(any(), any(), any(), any())
    }

    @Test
    fun `titles you rated 8+ are the seeds and their recommendations are merged round-robin`() {
        rate(1, 9.0, 300)
        rate(2, 8.0, 200)
        rate(3, 8.5, 100)
        rate(4, 6.0, 999) // newest, but below 8: not a seed
        serve(MediaType.movie, 1, 10, 11)
        serve(MediaType.movie, 2, 20, 10)
        serve(MediaType.movie, 3, 30)

        val result = handler().handle(GetForYou(me, Lang.en, MediaType.movie)).getOrNull()!!

        assertEquals(listOf(1, 2, 3), result.seeds.map { it.tmdbId })
        assertEquals(listOf(10, 20, 30, 11), result.picks.map { it.title.tmdbId })
        verify(client, never()).recommendations(eq(MediaType.movie), eq(4), any(), any())
    }

    @Test
    fun `with fewer than three 8+ ratings your top-rated titles are used`() {
        rate(1, 9.0, 100)
        rate(2, 7.0, 300)
        rate(3, 5.0, 200)
        serve(MediaType.movie, 1, 10)
        serve(MediaType.movie, 2, 20)
        serve(MediaType.movie, 3, 30)

        val result = handler().handle(GetForYou(me, Lang.en, MediaType.movie)).getOrNull()!!

        assertEquals(listOf(1, 2, 3), result.seeds.map { it.tmdbId })
        assertEquals(listOf(10, 20, 30), result.picks.map { it.title.tmdbId })
    }

    @Test
    fun `anything you rated, watched or gave any status is left out, watchlist included`() {
        rate(1, 9.0, 300)
        rate(2, 9.0, 200)
        rate(3, 9.0, 100)
        serve(MediaType.movie, 1, 10, 11, 12, 13, 14, 15)
        serve(MediaType.movie, 2)
        serve(MediaType.movie, 3)
        library.mark(MediaType.movie, 10, score = 6.0)
        library.mark(MediaType.movie, 11, status = WatchStatus.WATCHED)
        library.mark(MediaType.movie, 12, status = WatchStatus.WANT_TO_WATCH)
        library.mark(MediaType.movie, 13, status = WatchStatus.DROPPED)
        library.mark(MediaType.tvshow, 14, status = WatchStatus.WATCHED) // a show with the same id doesn't count

        assertEquals(listOf(14, 15), handler().ids(MediaType.movie))
    }

    @Test
    fun `Home mixes movies and TV, Discover keeps to its mode`() {
        rate(1, 9.0, 300)
        rate(2, 9.0, 200)
        rate(5, 9.0, 100, MediaType.tvshow)
        serve(MediaType.movie, 1, 10)
        serve(MediaType.movie, 2, 20)
        serve(MediaType.tvshow, 5, 50)
        val handler = handler()

        assertEquals(listOf(10, 20, 50), handler.ids(type = null))
        assertEquals(listOf(10, 20), handler.ids(MediaType.movie), "two 8+ movies: the fallback (top-rated movies) is the same two")
        assertEquals(listOf(50), handler.ids(MediaType.tvshow))
    }

    @Test
    fun `the merged list is cached per viewer, but your marks are read fresh`() {
        rate(1, 9.0, 300)
        rate(2, 9.0, 200)
        rate(3, 9.0, 100)
        serve(MediaType.movie, 1, 10, 11)
        serve(MediaType.movie, 2, 20)
        serve(MediaType.movie, 3, 30)
        val handler = handler()

        assertEquals(listOf(10, 20, 30, 11), handler.ids(MediaType.movie))
        library.mark(MediaType.movie, 20, score = 9.0)
        assertEquals(listOf(10, 30, 11), handler.ids(MediaType.movie))
        verify(client, times(1)).recommendations(eq(MediaType.movie), eq(1), any(), any())

        clock.advance(Duration.ofMinutes(6))
        handler.ids(MediaType.movie)
        verify(client, times(2)).recommendations(eq(MediaType.movie), eq(1), any(), any())
    }

    @Test
    fun `limit caps the picks`() {
        rate(1, 9.0, 300)
        rate(2, 9.0, 200)
        rate(3, 9.0, 100)
        serve(MediaType.movie, 1, 10, 11, 12)
        serve(MediaType.movie, 2, 20, 21, 22)
        serve(MediaType.movie, 3, 30, 31, 32)

        assertEquals(listOf(10, 20, 30, 11), handler().ids(MediaType.movie, limit = 4))
    }

    @Test
    fun `a failing seed is skipped and not cached, a seed TMDB doesn't know is just empty`() {
        rate(1, 9.0, 300)
        rate(2, 9.0, 200)
        rate(3, 9.0, 100)
        serve(MediaType.movie, 1, 10)
        fail(MediaType.movie, 2)
        fail(MediaType.movie, 3, TmdbRecommendationsError.NotFound)
        val handler = handler()

        assertEquals(listOf(10), handler.ids(MediaType.movie))
        serve(MediaType.movie, 2, 20)
        assertEquals(listOf(10, 20), handler.ids(MediaType.movie), "the partial result wasn't cached")
    }

    @Test
    fun `every seed failing is an error, a missing key says so`() {
        rate(1, 9.0, 300)
        rate(2, 9.0, 200)
        rate(3, 9.0, 100)
        listOf(1, 2, 3).forEach { fail(MediaType.movie, it) }
        assertEquals(GetForYouError.TmdbUnavailable, handler().handle(GetForYou(me, Lang.en, MediaType.movie)).leftOrNull())

        listOf(1, 2, 3).forEach { fail(MediaType.movie, it, TmdbRecommendationsError.ApiKeyMissing) }
        assertEquals(GetForYouError.TmdbNotConfigured, handler().handle(GetForYou(me, Lang.en, MediaType.movie)).leftOrNull())
    }

    @Test
    fun `seeds are fetched in parallel on the pool with the same result`() {
        (1..5).forEach { rate(it, 9.0, 100L - it) }
        (1..5).forEach { serve(MediaType.movie, it, it * 10, it * 10 + 1) }

        val ids = handler(parallel = 5).ids(MediaType.movie)

        assertEquals(listOf(10, 20, 30, 40, 50, 11, 21, 31, 41, 51), ids)
        assertTrue(seeds.reads > 0)
    }
}
