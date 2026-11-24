package org.ratelog.recommendations.foryou

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.recommendations.RelatedTitle
import java.time.LocalDate

class ForYouRuleTest {

    private val today = LocalDate.of(2026, 11, 23)

    private fun seed(id: Int, score: Double, ratedAt: Long, type: MediaType = MediaType.movie) =
        ForYouSeed(id, type, "Seed $id", score, ratedAt)

    private fun rec(id: Int, type: MediaType = MediaType.movie, poster: String? = "/p$id.jpg", votes: Int = 500, date: String? = "2015-01-01") =
        RelatedTitle(id, type, "Title $id", date?.let(LocalDate::parse), poster, 7.0, votes)

    private fun neverCalled(): Map<MediaType, List<ForYouSeed>> = throw AssertionError("top-rated fallback must not be read")

    // --- Seeds ---

    @Test
    fun `three or more titles rated 8+ are the seeds, newest rating first, at most five`() {
        val high = listOf(seed(1, 8.0, 100), seed(2, 9.5, 700), seed(3, 8.5, 300), seed(4, 10.0, 200), seed(5, 8.0, 600), seed(6, 9.0, 500))

        val seeds = ForYouRule.pickSeeds(mapOf(MediaType.movie to high), ::neverCalled)

        assertEquals(listOf(2, 5, 6, 3, 4), seeds.map { it.tmdbId })
    }

    @Test
    fun `with fewer than three titles rated 8+ the best-rated titles are the seeds instead`() {
        val high = listOf(seed(1, 8.5, 100), seed(2, 9.0, 50))
        val top = listOf(seed(2, 9.0, 50), seed(1, 8.5, 100), seed(7, 7.0, 900), seed(8, 7.0, 950), seed(9, 6.0, 999), seed(10, 5.0, 10))
        var read = false

        val seeds = ForYouRule.pickSeeds(mapOf(MediaType.movie to high)) { read = true; mapOf(MediaType.movie to top) }

        assertTrue(read)
        // Best score first; equal scores newest first.
        assertEquals(listOf(2, 1, 8, 7, 9), seeds.map { it.tmdbId })
    }

    @Test
    fun `no ratings means no seeds`() {
        assertEquals(emptyList<ForYouSeed>(), ForYouRule.pickSeeds(mapOf(MediaType.movie to emptyList())) { mapOf(MediaType.movie to emptyList()) })
    }

    @Test
    fun `a mixed row keeps a seed of each type even when one type has the five newest`() {
        val movies = (1..5).map { seed(it, 9.0, 1_000L + it) }
        val shows = listOf(seed(50, 8.0, 10, MediaType.tvshow), seed(51, 9.0, 5, MediaType.tvshow))

        val seeds = ForYouRule.pickSeeds(mapOf(MediaType.movie to movies, MediaType.tvshow to shows), ::neverCalled)

        assertEquals(5, seeds.size)
        assertEquals(listOf(5, 4, 3, 2), seeds.filter { it.type == MediaType.movie }.map { it.tmdbId }, "the oldest movie seed makes room")
        assertEquals(listOf(50), seeds.filter { it.type == MediaType.tvshow }.map { it.tmdbId }, "the newest show")
    }

    @Test
    fun `a show and a movie with the same TMDB id are different seeds`() {
        val seeds = ForYouRule.pickSeeds(
            mapOf(MediaType.movie to listOf(seed(1, 9.0, 3), seed(2, 9.0, 2)), MediaType.tvshow to listOf(seed(1, 9.0, 1, MediaType.tvshow))),
            ::neverCalled,
        )
        assertEquals(listOf(MediaType.movie to 1, MediaType.movie to 2, MediaType.tvshow to 1), seeds.map { it.type to it.tmdbId })
    }

    // --- Merge ---

    @Test
    fun `recommendations are merged round-robin in seed order, each title once, for the first seed`() {
        val a = seed(1, 9.0, 3)
        val b = seed(2, 9.0, 2)
        val c = seed(3, 9.0, 1)

        val merged = ForYouRule.merge(
            listOf(
                a to listOf(rec(10), rec(11), rec(12)),
                b to listOf(rec(20), rec(10), rec(21)),
                c to listOf(rec(30)),
            ),
            today,
        )

        assertEquals(listOf(10, 20, 30, 11, 12, 21), merged.map { it.title.tmdbId })
        assertEquals(1, merged.first { it.title.tmdbId == 10 }.because.tmdbId)
        assertEquals(2, merged.first { it.title.tmdbId == 21 }.because.tmdbId)
    }

    @Test
    fun `the seeds themselves, posterless and little-known titles are left out, upcoming ones stay`() {
        val a = seed(1, 9.0, 3)
        val b = seed(2, 9.0, 2)

        val merged = ForYouRule.merge(
            listOf(
                a to listOf(rec(2), rec(40, poster = null), rec(41, votes = 3), rec(42, votes = 0, date = "2027-03-01"), rec(43)),
                b to listOf(rec(1), rec(44)),
            ),
            today,
        )

        assertEquals(listOf(42, 44, 43), merged.map { it.title.tmdbId })
    }

    @Test
    fun `a movie and a show sharing a TMDB id are both kept`() {
        val merged = ForYouRule.merge(
            listOf(seed(1, 9.0, 2) to listOf(rec(10)), seed(5, 9.0, 1, MediaType.tvshow) to listOf(rec(10, MediaType.tvshow))),
            today,
        )
        assertEquals(listOf(MediaType.movie to 10, MediaType.tvshow to 10), merged.map { it.title.type to it.title.tmdbId })
    }

    @Test
    fun `the merged list is capped`() {
        val merged = ForYouRule.merge((1..5).map { s -> seed(s, 9.0, s.toLong()) to (1..40).map { rec(s * 1000 + it) } }, today)
        assertEquals(ForYouRule.MAX_CANDIDATES, merged.size)
        assertFalse(merged.isEmpty())
    }
}
