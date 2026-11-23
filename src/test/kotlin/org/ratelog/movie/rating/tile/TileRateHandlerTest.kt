package org.ratelog.movie.rating.tile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.Status
import org.ratelog.movie.rating.add.AddRatingHandler
import org.ratelog.movie.rating.delete.DeleteRatingHandler
import org.ratelog.movie.rating.update.UpdateRatingHandler
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.autowatched.AutoWatchedRatingRepository
import org.ratelog.watchlist.autowatched.MarkWatchedOnRatingHandler
import org.ratelog.watchlist.seen.SeenStateReader
import java.time.LocalDate

class TileRateHandlerTest {

    private val clock = MutableClock() // 2026-11-22
    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val stored = InMemoryRatingRepository()
    // The real wiring: every rating save goes through the auto-Watched decorator.
    private val ratings = AutoWatchedRatingRepository(stored, MarkWatchedOnRatingHandler(statuses, InMemoryTvShowRepository(), clock))
    private val reader = SeenStateReader(statuses, ratings, InMemoryTvRatingRepository())
    private val rate = TileRateHandler(movies, ratings, AddRatingHandler(ratings), UpdateRatingHandler(ratings), reader, clock)
    private val delete = TileDeleteRatingHandler(ratings, DeleteRatingHandler(movies, ratings), reader)

    private val user = UserFactory.aUser(id = 1)
    private val userId = user.id!!
    private val heat = movies.save(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseDate = LocalDate.parse("1995-12-15")))
    private val doomsday = movies.save(MovieFactory.aMovie(tmdbId = 1003596, originalTitle = "Avengers: Doomsday", releaseDate = LocalDate.parse("2026-12-18")))

    private fun rating() = ratings.findByMovieIdAndUserId(heat.id!!, userId)
    private fun status() = statuses.find(userId, MediaType.movie, heat.id!!.value)?.status

    @Test
    fun `rating an unrated movie saves a quick rating and marks it seen`() {
        val result = rate.handle(TileRate(userId, heat.id!!, 8.0)).getOrNull()!!

        assertEquals(TileRateOutcome.RATED, result.outcome)
        assertTrue(rating()!!.isQuick)
        assertEquals(8.0, rating()!!.score!!.value)
        assertEquals(WatchStatus.WATCHED, status(), "rating a movie marks it Watched")
        assertFalse(result.before.seen)
        assertTrue(result.after.seen)
        assertTrue(result.after.seenLocked)
        assertEquals(1, result.seenDelta)
        assertEquals(8.0, result.score!!.value)
    }

    @Test
    fun `a movie already seen gains a rating without changing its seen count`() {
        statuses.save(MediaStatus(userId, MediaType.movie, heat.id!!.value, WatchStatus.WATCHED, 1L))

        val result = rate.handle(TileRate(userId, heat.id!!, 6.0)).getOrNull()!!

        assertEquals(0, result.seenDelta)
        assertTrue(result.after.seenLocked)
    }

    @Test
    fun `rating from the watchlist moves Want to watch to Watched`() {
        statuses.save(MediaStatus(userId, MediaType.movie, heat.id!!.value, WatchStatus.WANT_TO_WATCH, 1L))

        val result = rate.handle(TileRate(userId, heat.id!!, 7.0)).getOrNull()!!

        assertEquals(WatchStatus.WANT_TO_WATCH, result.before.status)
        assertEquals(WatchStatus.WATCHED, result.after.status)
        assertFalse(result.after.onWatchlist)
    }

    @Test
    fun `a quick rating gets the new score and keeps its review`() {
        ratings.save(RatingFactory.aQuickRating(movieId = heat.id!!, userId = userId, score = 6.5, reviewTitle = "Tense", reviewText = "The diner scene."))

        val result = rate.handle(TileRate(userId, heat.id!!, 9.0)).getOrNull()!!

        assertEquals(TileRateOutcome.CHANGED, result.outcome)
        assertEquals(9.0, rating()!!.score!!.value)
        assertTrue(rating()!!.isQuick)
        assertEquals("Tense", rating()!!.review?.title?.value)
        assertEquals("The diner scene.", rating()!!.review?.text?.value)
        assertEquals(1, stored.findRankedByUserIdWithFilters(userId, null, 10, null, null).size, "still one rating")
    }

    @Test
    fun `a detailed rating is never flattened into one score`() {
        ratings.save(RatingFactory.aRating(movieId = heat.id!!, userId = userId, directing = 9.0, cinematography = 8.0, acting = 9.0, soundtrack = 7.0, screenplay = 8.0))

        val error = rate.handle(TileRate(userId, heat.id!!, 3.0)).leftOrNull()

        assertTrue(error is TileRateHandlerError.DetailedRating, "$error")
        assertEquals(8.2, (error as TileRateHandlerError.DetailedRating).score!!.value, 0.001)
        assertFalse(rating()!!.isQuick)
        assertEquals(9.0, rating()!!.directing!!.value)
    }

    @Test
    fun `scores outside 0 to 10, or none, are refused and nothing is saved`() {
        listOf(null, -1.0, 10.5, Double.NaN).forEach { score ->
            assertEquals(TileRateHandlerError.InvalidScore, rate.handle(TileRate(userId, heat.id!!, score)).leftOrNull(), "$score")
        }
        assertNull(rating())
        assertNull(status())
    }

    @Test
    fun `an upcoming movie can't be rated from a tile`() {
        assertEquals(TileRateHandlerError.NotReleased, rate.handle(TileRate(userId, doomsday.id!!, 8.0)).leftOrNull())
        assertNull(ratings.findByMovieIdAndUserId(doomsday.id!!, userId))
    }

    @Test
    fun `a dateless movie TMDB lists as in production is upcoming too`() {
        val planned = movies.save(MovieFactory.aMovie(tmdbId = 77, originalTitle = "Planned", releaseDate = null, status = Status.IN_PRODUCTION))

        assertEquals(TileRateHandlerError.NotReleased, rate.handle(TileRate(userId, planned.id!!, 8.0)).leftOrNull())
    }

    @Test
    fun `an unknown movie is refused`() {
        assertEquals(TileRateHandlerError.MovieNotFound, rate.handle(TileRate(userId, org.ratelog.movie.Movie.Id(999), 8.0)).leftOrNull())
    }

    @Test
    fun `deleting a quick rating keeps the movie seen (Watched stays) but no longer locked`() {
        rate.handle(TileRate(userId, heat.id!!, 8.0))

        val result = delete.handle(TileDeleteRating(userId, heat.id!!)).getOrNull()!!

        assertEquals(TileRateOutcome.DELETED, result.outcome)
        assertNull(rating())
        assertEquals(WatchStatus.WATCHED, status(), "a delete never touches the status")
        assertTrue(result.after.seen)
        assertFalse(result.after.seenLocked)
        assertEquals(0, result.seenDelta)
        assertNull(result.score)
    }

    @Test
    fun `a detailed rating isn't deleted from a tile, and there must be a rating to delete`() {
        assertEquals(TileRateHandlerError.RatingNotFound, delete.handle(TileDeleteRating(userId, heat.id!!)).leftOrNull())

        ratings.save(RatingFactory.aRating(movieId = heat.id!!, userId = userId))
        assertTrue(delete.handle(TileDeleteRating(userId, heat.id!!)).leftOrNull() is TileRateHandlerError.DetailedRating)
        assertFalse(rating() == null)
    }

    @Test
    fun `ratings are per user`() {
        val other = UserFactory.aUser(id = 2).id!!
        ratings.save(RatingFactory.aRating(movieId = heat.id!!, userId = other))

        assertEquals(TileRateOutcome.RATED, rate.handle(TileRate(userId, heat.id!!, 5.0)).getOrNull()!!.outcome)
        assertTrue(rating()!!.isQuick)
        assertFalse(ratings.findByMovieIdAndUserId(heat.id!!, other)!!.isQuick)
    }
}
