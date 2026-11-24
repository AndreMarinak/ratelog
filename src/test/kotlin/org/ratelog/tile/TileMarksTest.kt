package org.ratelog.tile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.test.UserFactory
import org.ratelog.user.User
import org.ratelog.watchlist.seen.TileActionsView
import java.time.LocalDate
import java.util.Locale

/** Canned answers, counting the queries. */
class FakeTileMarksRepository(
    var listed: Set<TileKey> = emptySet(),
    var detailed: Set<Int> = emptySet(),
    var failure: RuntimeException? = null,
    var seasonRated: Set<Int> = emptySet(),
) : TileMarksRepository {
    var listQueries = 0
    var detailedQueries = 0
    var lastUser: User.Id? = null

    override fun findListedTitles(userId: User.Id): Set<TileKey> {
        listQueries++
        lastUser = userId
        failure?.let { throw it }
        return listed
    }

    override fun findDetailedMovieRatings(userId: User.Id): Set<Int> {
        detailedQueries++
        lastUser = userId
        failure?.let { throw it }
        return detailed
    }

    override fun findSeasonRatedShows(userId: User.Id): Set<Int> {
        failure?.let { throw it }
        return seasonRated
    }
}

class TileMarksTest {

    private val today = LocalDate.parse("2026-11-23")
    private val user = UserFactory.aUser(id = 7)

    private fun tile(tmdbId: Int, type: MediaType = MediaType.movie, score: Double? = null, detailed: Boolean = false) =
        TileActionsView.of(tmdbId, type, null, score != null, LocalDate.parse("2001-01-01"), today, Locale.ENGLISH, score = score, detailed = detailed, onWatchlist = false)

    @Test
    fun `nothing is queried until a tile asks, then each set is read once for the whole page`() {
        val repo = FakeTileMarksRepository(listed = setOf(TileKey(MediaType.movie, 603), TileKey(MediaType.tvshow, 1396)), detailed = setOf(603))
        val marks = TileMarks(repo) { user }
        assertEquals(0, repo.listQueries + repo.detailedQueries)

        assertTrue(marks.inList(tile(603)))
        assertTrue(marks.inList(tile(1396, MediaType.tvshow)))
        assertFalse(marks.inList(tile(1396)), "movie and TV ids are separate namespaces")
        assertFalse(marks.inList(tile(550)))
        assertEquals(1, repo.listQueries)
        assertEquals(0, repo.detailedQueries)

        assertTrue(marks.detailed(tile(603, score = 8.2)))
        assertFalse(marks.detailed(tile(550, score = 7.0)))
        assertEquals(1, repo.detailedQueries)
        assertEquals(user.id, repo.lastUser)
    }

    @Test
    fun `only rated movies can be detailed, and a view that already knows says so without a query`() {
        val repo = FakeTileMarksRepository(detailed = setOf(603, 1396))
        val marks = TileMarks(repo) { user }

        assertFalse(marks.detailed(tile(603)), "unrated")
        assertFalse(marks.detailed(tile(1396, MediaType.tvshow, score = 8.0)), "shows are rated per season")
        assertTrue(marks.detailed(tile(550, score = 9.0, detailed = true)))
        assertEquals(0, repo.detailedQueries)
    }

    @Test
    fun `a failing query or no user only loses the marks`() {
        val failing = TileMarks(FakeTileMarksRepository(failure = IllegalStateException("db down"))) { user }
        assertFalse(failing.inList(tile(603)))
        assertFalse(failing.detailed(tile(603, score = 8.0)))

        val anonymous = FakeTileMarksRepository(listed = setOf(TileKey(MediaType.movie, 603)))
        assertFalse(TileMarks(anonymous) { null }.inList(tile(603)))
        assertEquals(0, anonymous.listQueries)
    }

    @Test
    fun `the advice hands every page a fresh holder`() {
        val advice = TileMarksAdvice(FakeTileMarksRepository())
        assertFalse(advice.tileMarks() === advice.tileMarks())
    }
}
