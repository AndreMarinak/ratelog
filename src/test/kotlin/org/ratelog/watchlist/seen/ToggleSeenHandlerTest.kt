package org.ratelog.watchlist.seen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.Status
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate

class ToggleSeenHandlerTest {

    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val ratings = InMemoryRatingRepository()
    private val tvRatings = InMemoryTvRatingRepository()
    private val clock = MutableClock() // 2026-11-22T12:00Z
    private val today = LocalDate.parse("2026-11-22")
    private val handler = ToggleSeenHandler(statuses, movies, shows, SeenStateReader(statuses, ratings, tvRatings), clock)

    private val user = User.Id(1)
    private val other = User.Id(2)
    private val heat = movies.save(MovieFactory.aMovie(originalTitle = "Heat", releaseDate = LocalDate.parse("1995-12-15"))).id!!
    private val severance = shows.save(TvShowFactory.aTvShow(originalName = "Severance", firstAirDate = LocalDate.parse("2022-02-18"))).id!!

    private fun toggleMovie(id: Long = heat.value, who: User.Id = user) = handler.handle(ToggleSeen(who, MediaType.movie, id))
    private fun toggleShow(id: Long = severance.value) = handler.handle(ToggleSeen(user, MediaType.tvshow, id))
    private fun movieStatus(id: Long = heat.value, who: User.Id = user) = statuses.effective(who, MediaType.movie, id)
    private fun showStatus() = statuses.effective(user, MediaType.tvshow, severance.value)
    private fun setMovie(status: WatchStatus, id: Long = heat.value) = statuses.save(MediaStatus(user, MediaType.movie, id, status, 1L))
    private fun rateHeat() = ratings.save(RatingFactory.aQuickRating(movieId = heat, userId = user, score = 8.0))

    @Test
    fun `one tap marks an unseen title seen, without a rating`() {
        val result = toggleMovie().getOrNull()!!

        assertEquals(ToggleSeenOutcome.MARKED_SEEN, result.outcome)
        assertNull(result.previous)
        assertEquals(WatchStatus.WATCHED, movieStatus())
        assertTrue(result.state.seen)
        assertFalse(result.state.seenLocked)
        assertEquals(1, result.seenDelta)
        assertEquals(clock.millis(), statuses.find(user, MediaType.movie, heat.value)!!.updatedAtEpochMs)
    }

    @Test
    fun `a second tap clears it again, to no status at all`() {
        toggleMovie()

        val result = toggleMovie().getOrNull()!!

        assertEquals(ToggleSeenOutcome.UNMARKED, result.outcome)
        assertEquals(WatchStatus.WATCHED, result.previous)
        assertNull(movieStatus())
        assertFalse(result.state.seen)
        assertEquals(-1, result.seenDelta)
    }

    @Test
    fun `seen replaces want to watch, watching and dropped`() {
        listOf(WatchStatus.WANT_TO_WATCH, WatchStatus.WATCHING, WatchStatus.DROPPED).forEach { before ->
            setMovie(before)

            val result = toggleMovie().getOrNull()!!

            assertEquals(ToggleSeenOutcome.MARKED_SEEN, result.outcome, "$before")
            assertEquals(before.takeIf { it != WatchStatus.WANT_TO_WATCH }, result.previous, "the progress status before")
            assertEquals(WatchStatus.WATCHED, movieStatus(), "$before")
            assertNull(statuses.findWatchlistEntry(user, MediaType.movie, heat.value), "seen takes it off the watchlist")
        }
    }

    @Test
    fun `un-seeing a title that was dropped before does not bring Dropped back`() {
        setMovie(WatchStatus.DROPPED)
        toggleMovie()

        toggleMovie()

        assertNull(movieStatus())
    }

    @Test
    fun `a rated movie stays seen whatever its status, and nothing changes`() {
        rateHeat()
        listOf(null, WatchStatus.WATCHED, WatchStatus.WANT_TO_WATCH, WatchStatus.DROPPED, WatchStatus.WATCHING).forEach { before ->
            statuses.delete(user, MediaType.movie, heat.value)
            statuses.removeFromWatchlist(user, MediaType.movie, heat.value)
            if (before != null) setMovie(before)

            val result = toggleMovie().getOrNull()!!

            assertEquals(ToggleSeenOutcome.LOCKED_BY_RATING, result.outcome, "$before")
            assertEquals(before, movieStatus(), "status untouched for $before")
            assertTrue(result.state.seen)
            assertTrue(result.state.seenLocked)
            assertEquals(0, result.seenDelta)
        }
        assertTrue(ratings.findByMovieIdAndUserId(heat, user) != null, "the rating is never deleted")
    }

    private fun rateSeason1() = tvRatings.save(
        TvRatingFactory.aTvRating(tvShowId = severance, userId = user, seasonRatings = listOf(TvRatingFactory.aQuickSeasonRating(tvShowId = severance, seasonNumber = 1, userId = user, score = 9.0))),
    )

    @Test
    fun `a show is never locked - its status says whether it is seen, not its rated seasons`() {
        rateSeason1()
        listOf(WatchStatus.DROPPED, WatchStatus.WATCHING).forEach { before ->
            statuses.save(MediaStatus(user, MediaType.tvshow, severance.value, before, 1L))

            val result = toggleShow().getOrNull()!!

            assertEquals(ToggleSeenOutcome.MARKED_SEEN, result.outcome, "a rated season + $before is not seen yet")
            assertEquals(WatchStatus.WATCHED, showStatus())
            assertFalse(result.state.seenLocked)
            assertEquals(1, result.seenDelta)
        }
    }

    @Test
    fun `un-seeing a show with rated seasons moves it back to watching`() {
        rateSeason1()
        statuses.save(MediaStatus(user, MediaType.tvshow, severance.value, WatchStatus.WATCHED, 1L))

        val result = toggleShow().getOrNull()!!

        assertEquals(ToggleSeenOutcome.UNMARKED_TO_WATCHING, result.outcome)
        assertEquals(WatchStatus.WATCHING, showStatus(), "you've rated seasons, so you've started it")
        assertFalse(result.state.seen)
        assertEquals(-1, result.seenDelta)
    }

    @Test
    fun `a show rated before statuses existed counts as seen and un-sees to watching`() {
        rateSeason1()

        val result = toggleShow().getOrNull()!!

        assertEquals(ToggleSeenOutcome.UNMARKED_TO_WATCHING, result.outcome)
        assertEquals(WatchStatus.WATCHING, showStatus())
    }

    @Test
    fun `a show rating with no seasons left does not lock it`() {
        tvRatings.save(TvRatingFactory.aTvRating(tvShowId = severance, userId = user, seasonRatings = emptyList()))

        val result = toggleShow().getOrNull()!!

        assertEquals(ToggleSeenOutcome.MARKED_SEEN, result.outcome)
        assertEquals(WatchStatus.WATCHED, showStatus())
    }

    @Test
    fun `marking a show seen from watching means finished`() {
        statuses.save(MediaStatus(user, MediaType.tvshow, severance.value, WatchStatus.WATCHING, 1L))

        assertEquals(ToggleSeenOutcome.MARKED_SEEN, toggleShow().getOrNull()!!.outcome)
        assertEquals(WatchStatus.WATCHED, showStatus())
    }

    @Test
    fun `an upcoming movie can't be marked seen`() {
        val doomsday = movies.save(MovieFactory.aMovie(originalTitle = "Avengers: Doomsday", releaseDate = today.plusDays(26))).id!!.value
        setMovie(WatchStatus.WANT_TO_WATCH, doomsday)

        assertEquals(ToggleSeenError.NotReleased, toggleMovie(doomsday).leftOrNull())
        assertEquals(WatchStatus.WANT_TO_WATCH, movieStatus(doomsday), "the watchlist entry stays")
    }

    @Test
    fun `released today counts as out, tomorrow does not`() {
        val todayId = movies.save(MovieFactory.aMovie(originalTitle = "Out today", releaseDate = today)).id!!.value
        val tomorrowId = movies.save(MovieFactory.aMovie(originalTitle = "Out tomorrow", releaseDate = today.plusDays(1))).id!!.value

        assertEquals(ToggleSeenOutcome.MARKED_SEEN, toggleMovie(todayId).getOrNull()!!.outcome)
        assertEquals(ToggleSeenError.NotReleased, toggleMovie(tomorrowId).leftOrNull())
    }

    @Test
    fun `a movie with no date is refused only while TMDB says it isn't out`() {
        val tba = movies.save(MovieFactory.aMovie(originalTitle = "TBA", releaseDate = null, status = Status.IN_PRODUCTION)).id!!.value
        val noDate = movies.save(MovieFactory.aMovie(originalTitle = "Old, undated", releaseDate = null, status = Status.RELEASED)).id!!.value
        val unknown = movies.save(MovieFactory.aMovie(originalTitle = "Nothing known", releaseDate = null, status = null)).id!!.value

        assertEquals(ToggleSeenError.NotReleased, toggleMovie(tba).leftOrNull())
        assertEquals(ToggleSeenOutcome.MARKED_SEEN, toggleMovie(noDate).getOrNull()!!.outcome)
        assertEquals(ToggleSeenOutcome.MARKED_SEEN, toggleMovie(unknown).getOrNull()!!.outcome)
    }

    @Test
    fun `a show whose first air date is tomorrow can't be marked seen`() {
        val newShow = shows.save(TvShowFactory.aTvShow(originalName = "New", firstAirDate = today.plusDays(1))).id!!.value

        assertEquals(ToggleSeenError.NotReleased, toggleShow(newShow).leftOrNull())
    }

    @Test
    fun `a Watched status on an upcoming title can still be cleared`() {
        val doomsday = movies.save(MovieFactory.aMovie(originalTitle = "Avengers: Doomsday", releaseDate = today.plusDays(26))).id!!.value
        setMovie(WatchStatus.WATCHED, doomsday)

        assertEquals(ToggleSeenOutcome.UNMARKED, toggleMovie(doomsday).getOrNull()!!.outcome)
        assertNull(movieStatus(doomsday))
    }

    @Test
    fun `unknown titles are an error, and other users are untouched`() {
        assertEquals(ToggleSeenError.MediaNotFound, toggleMovie(9999).leftOrNull())
        assertEquals(ToggleSeenError.MediaNotFound, toggleShow(9999).leftOrNull())

        toggleMovie()
        assertNull(movieStatus(who = other))
        assertEquals(ToggleSeenOutcome.MARKED_SEEN, toggleMovie(who = other).getOrNull()!!.outcome, "other user's own state")
    }
}
