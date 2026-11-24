package org.ratelog.rewatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.Status
import org.ratelog.rewatch.delete.DeleteRewatch
import org.ratelog.rewatch.delete.DeleteRewatchError
import org.ratelog.rewatch.delete.DeleteRewatchHandler
import org.ratelog.rewatch.log.LogRewatch
import org.ratelog.rewatch.log.LogRewatchError
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryRewatchRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.test.WatchlistHandlers
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.SeenStateReader
import org.ratelog.watchlist.seen.ToggleSeen
import org.ratelog.watchlist.seen.ToggleSeenHandler
import java.time.Duration
import java.time.LocalDate

/** "Watched again": LogRewatchHandler and DeleteRewatchHandler over the in-memory fakes (fork, 2026-11-24). */
class RewatchHandlersTest {
    private val clock = MutableClock() // 2026-11-22T12:00Z
    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val ratings = InMemoryRatingRepository()
    private val tvRatings = InMemoryTvRatingRepository()
    private val rewatches = InMemoryRewatchRepository()
    private val log = WatchlistHandlers.logRewatch(rewatches, statuses, movies, shows, ratings, tvRatings, clock)
    private val delete = DeleteRewatchHandler(rewatches)
    private val toggleSeen = ToggleSeenHandler(statuses, movies, shows, SeenStateReader(statuses, ratings, tvRatings), clock)

    private val user = User.Id(1)
    private val other = User.Id(2)
    private val dune = movies.save(MovieFactory.aMovie(tmdbId = 438631, originalTitle = "Dune", releaseDate = LocalDate.parse("2021-09-15")))
    private val heat = movies.save(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseDate = LocalDate.parse("1995-12-15")))
    private val doomsday = movies.save(MovieFactory.aMovie(tmdbId = 1003596, originalTitle = "Avengers: Doomsday", releaseDate = LocalDate.parse("2026-12-18"), status = Status.POST_PRODUCTION))
    private val bear = shows.save(TvShowFactory.aTvShow(tmdbId = 136315, originalName = "The Bear", firstAirDate = LocalDate.parse("2022-06-23")))

    private fun watched(type: MediaType, id: Long, userId: User.Id = user) =
        statuses.save(MediaStatus(userId, type, id, WatchStatus.WATCHED, clock.millis()))

    @Test
    fun `a seen movie gets a row at now, and the summary counts it`() {
        watched(MediaType.movie, dune.id!!.value)

        val first = log.handle(LogRewatch(user, MediaType.movie, dune.id!!.value)).getOrNull()!!
        assertEquals(clock.millis(), first.rewatch.watchedAtEpochMs)
        assertEquals(RewatchSummary(1, clock.millis()), first.summary)
        assertEquals(2, first.summary.watchCount, "the first watch plus one rewatch")
        assertFalse(first.rated)

        clock.advance(Duration.ofDays(2))
        val second = log.handle(LogRewatch(user, MediaType.movie, dune.id!!.value)).getOrNull()!!
        assertEquals(3, second.summary.watchCount)
        assertEquals(clock.millis(), second.summary.lastAtEpochMs)
        assertEquals(WatchStatus.WATCHED, statuses.find(user, MediaType.movie, dune.id!!.value)?.status, "the status is left alone")
    }

    @Test
    fun `a rated movie with no status counts as seen, and says it is rated`() {
        ratings.save(RatingFactory.aQuickRating(movieId = heat.id!!, userId = user, score = 9.0))
        val result = log.handle(LogRewatch(user, MediaType.movie, heat.id!!.value)).getOrNull()!!
        assertTrue(result.rated)
        assertNull(statuses.find(user, MediaType.movie, heat.id!!.value), "no status is added")
    }

    @Test
    fun `a show is seen when Watched or rated with no status, never while Watching`() {
        statuses.save(MediaStatus(user, MediaType.tvshow, bear.id!!.value, WatchStatus.WATCHING, clock.millis()))
        assertEquals(LogRewatchError.NotSeen, log.handle(LogRewatch(user, MediaType.tvshow, bear.id!!.value)).leftOrNull())

        watched(MediaType.tvshow, bear.id!!.value)
        assertEquals(2, log.handle(LogRewatch(user, MediaType.tvshow, bear.id!!.value)).getOrNull()!!.summary.watchCount)

        // Rated seasons and no status at all: seen by the old rule (ratings from before statuses).
        statuses.delete(user, MediaType.tvshow, bear.id!!.value)
        tvRatings.save(TvRatingFactory.aTvRating(tvShowId = bear.id!!, userId = user, seasonRatings = listOf(TvRatingFactory.aSeasonRating(tvShowId = bear.id!!, seasonNumber = 1, userId = user))))
        val rated = log.handle(LogRewatch(user, MediaType.tvshow, bear.id!!.value)).getOrNull()!!
        assertTrue(rated.rated)
        assertEquals(3, rated.summary.watchCount)
    }

    @Test
    fun `unseen, upcoming, unknown and future-dated logs are refused and store nothing`() {
        assertEquals(LogRewatchError.NotSeen, log.handle(LogRewatch(user, MediaType.movie, dune.id!!.value)).leftOrNull())
        statuses.addToWatchlist(org.ratelog.watchlist.WatchlistEntry(user, MediaType.movie, dune.id!!.value, clock.millis()))
        assertEquals(LogRewatchError.NotSeen, log.handle(LogRewatch(user, MediaType.movie, dune.id!!.value)).leftOrNull(), "on the watchlist is not seen")

        watched(MediaType.movie, doomsday.id!!.value) // set through the API before release
        assertEquals(LogRewatchError.NotReleased, log.handle(LogRewatch(user, MediaType.movie, doomsday.id!!.value)).leftOrNull())

        assertEquals(LogRewatchError.MediaNotFound, log.handle(LogRewatch(user, MediaType.movie, 9999)).leftOrNull())

        watched(MediaType.movie, heat.id!!.value)
        val tomorrow = clock.millis() + Duration.ofDays(1).toMillis()
        assertEquals(LogRewatchError.InvalidDate, log.handle(LogRewatch(user, MediaType.movie, heat.id!!.value, tomorrow)).leftOrNull())
        assertTrue(rewatches.all().isEmpty())
    }

    @Test
    fun `an earlier date can be given (the API)`() {
        watched(MediaType.movie, heat.id!!.value)
        val lastWeek = clock.millis() - Duration.ofDays(7).toMillis()
        val result = log.handle(LogRewatch(user, MediaType.movie, heat.id!!.value, lastWeek)).getOrNull()!!
        assertEquals(lastWeek, result.rewatch.watchedAtEpochMs)
    }

    @Test
    fun `delete removes only your own row and reports what is left`() {
        watched(MediaType.movie, dune.id!!.value)
        val a = log.handle(LogRewatch(user, MediaType.movie, dune.id!!.value)).getOrNull()!!.rewatch
        clock.advance(Duration.ofHours(1))
        val b = log.handle(LogRewatch(user, MediaType.movie, dune.id!!.value)).getOrNull()!!.rewatch

        assertEquals(DeleteRewatchError.NotFound, delete.handle(DeleteRewatch(other, b.id!!)).leftOrNull(), "another user's row")
        val result = delete.handle(DeleteRewatch(user, b.id!!)).getOrNull()!!
        assertEquals(b, result.deleted)
        assertEquals(RewatchSummary(1, a.watchedAtEpochMs), result.summary)
        assertEquals(DeleteRewatchError.NotFound, delete.handle(DeleteRewatch(user, b.id!!)).leftOrNull(), "already gone")
    }

    @Test
    fun `un-seeing keeps the rows, and seeing it again brings the count back`() {
        watched(MediaType.movie, dune.id!!.value)
        log.handle(LogRewatch(user, MediaType.movie, dune.id!!.value))
        log.handle(LogRewatch(user, MediaType.movie, dune.id!!.value))

        toggleSeen.handle(ToggleSeen(user, MediaType.movie, dune.id!!.value))
        assertFalse(SeenStateReader(statuses, ratings, tvRatings).read(user, MediaType.movie, dune.id!!.value).seen)
        assertEquals(2, rewatches.summary(user, MediaType.movie, dune.id!!.value).rewatches, "kept, not counted while unseen")
        assertEquals(LogRewatchError.NotSeen, log.handle(LogRewatch(user, MediaType.movie, dune.id!!.value)).leftOrNull())

        toggleSeen.handle(ToggleSeen(user, MediaType.movie, dune.id!!.value))
        assertEquals(3, rewatches.summary(user, MediaType.movie, dune.id!!.value).watchCount)
    }
}
