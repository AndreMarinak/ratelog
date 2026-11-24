package org.ratelog.tvshow.rating.show

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.RatingInput
import org.ratelog.Score
import org.ratelog.SeasonNumber
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MutableClock
import org.ratelog.test.TvShowFactory
import org.ratelog.test.UserFactory
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.TvRating
import org.ratelog.tvshow.rating.addseason.AddSeasonRating
import org.ratelog.tvshow.rating.addseason.AddSeasonRatingHandler
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRating
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRatingHandler
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.autowatched.AutoWatchedTvRatingRepository
import org.ratelog.watchlist.autowatched.MarkWatchedOnRatingHandler
import org.ratelog.watchlist.seen.SeenStateReader
import java.time.Instant

/**
 * A show's score (user, 2026-11-23: "I should be able to rate show as a whole and then if u rate any season at all
 * then the overall rating becomes the average"): no season rated -> the whole-show score; any season rated -> their
 * average; every season deleted -> the whole-show score again.
 */
class ShowRatingTest {

    private val clock = MutableClock()
    private val shows = InMemoryTvShowRepository()
    private val statuses = InMemoryMediaStatusRepository()
    private val stored = InMemoryTvRatingRepository()
    private val tvRatings = AutoWatchedTvRatingRepository(stored, MarkWatchedOnRatingHandler(statuses, shows, clock))
    private val rate = RateShowHandler(shows, tvRatings, clock)
    private val delete = DeleteShowRatingHandler(tvRatings)
    private val addSeason = AddSeasonRatingHandler(tvRatings)
    private val deleteSeason = DeleteSeasonRatingHandler(shows, tvRatings)
    private val reader = SeenStateReader(statuses, InMemoryRatingRepository(), tvRatings)

    private val user = UserFactory.aUser(id = 1).id!!
    private val show: TvShow = shows.save(TvShowFactory.aTvShow(tmdbId = 1396, originalName = "Breaking Bad", lastSeasonNumber = 5))
    private val showId = show.id!!

    private fun rating() = tvRatings.findByTvShowIdAndUserId(showId, user)
    private fun season(n: Int, score: Double) =
        addSeason.handle(AddSeasonRating(showId, SeasonNumber(n), user, null, null, null, null, null, null, null, Score(score)))

    // --- The rule, on the domain object ---

    @Test
    fun `no season rated - the score is the whole-show score`() {
        val r = TvRating.create(showId, user, Instant.EPOCH).withShowScore(Score(8.0), Instant.EPOCH)

        assertEquals(8.0, r.score?.value)
        assertTrue(r.isRated)
        assertFalse(r.ratedBySeason)
    }

    @Test
    fun `any season rated - the score is the season average, the whole-show score is kept`() {
        val r = TvRating.create(showId, user, Instant.EPOCH).withShowScore(Score(8.0), Instant.EPOCH)
            .addSeasonRating(SeasonNumber(1), RatingInput.Quick(Score(6.0)), Instant.EPOCH, null)
            .addSeasonRating(SeasonNumber(2), RatingInput.Quick(Score(7.0)), Instant.EPOCH, null)

        assertEquals(6.5, r.score?.value)
        assertEquals(8.0, r.showScore?.value)
        assertTrue(r.ratedBySeason)
        // A new whole-show score doesn't change the average.
        assertEquals(6.5, r.withShowScore(Score(2.0), Instant.EPOCH).score?.value)
    }

    @Test
    fun `every season deleted - the whole-show score is the score again, without one nothing is left`() {
        val r = TvRating.create(showId, user, Instant.EPOCH).withShowScore(Score(8.0), Instant.EPOCH)
            .addSeasonRating(SeasonNumber(1), RatingInput.Quick(Score(6.0)), Instant.EPOCH, null)
            .deleteSeasonRating(SeasonNumber(1))

        assertEquals(8.0, r.score?.value)
        assertTrue(r.withoutShowScore().isEmpty)
        assertNull(r.withoutShowScore().score)
    }

    // --- Handlers and storage ---

    @Test
    fun `rating the whole show stores it, changing it says so`() {
        assertEquals(RateShowOutcome.RATED, rate.handle(RateShow(showId, user, 8.0)).getOrNull()!!.outcome)
        assertEquals(8.0, rating()!!.score?.value)
        assertEquals(clock.instant(), rating()!!.showRatedAt)

        assertEquals(RateShowOutcome.CHANGED, rate.handle(RateShow(showId, user, 6.5)).getOrNull()!!.outcome)
        assertEquals(6.5, rating()!!.score?.value)
    }

    @Test
    fun `invalid scores and unknown shows are refused`() {
        assertEquals(RateShowHandlerError.InvalidScore, rate.handle(RateShow(showId, user, null)).leftOrNull())
        assertEquals(RateShowHandlerError.InvalidScore, rate.handle(RateShow(showId, user, 10.5)).leftOrNull())
        assertEquals(RateShowHandlerError.TvShowNotFound, rate.handle(RateShow(TvShow.Id(999), user, 7.0)).leftOrNull())
        assertNull(rating())
    }

    @Test
    fun `seasons rated after the whole show take over, and deleting the last one brings the whole-show score back`() {
        rate.handle(RateShow(showId, user, 8.0))
        season(1, 6.0)
        season(2, 9.0)
        assertEquals(7.5, rating()!!.score?.value)

        deleteSeason.handle(DeleteSeasonRating(showId, SeasonNumber(1), user))
        deleteSeason.handle(DeleteSeasonRating(showId, SeasonNumber(2), user))

        val left = rating()!!
        assertEquals(8.0, left.score?.value, "the row stays: the whole-show score is the score again")
        assertFalse(left.ratedBySeason)
    }

    @Test
    fun `deleting the last season of a show without a whole-show score still removes the rating`() {
        season(1, 6.0)
        deleteSeason.handle(DeleteSeasonRating(showId, SeasonNumber(1), user))

        assertNull(rating())
    }

    @Test
    fun `deleting the whole-show score keeps the seasons, and removes the row when nothing is left`() {
        rate.handle(RateShow(showId, user, 8.0))
        season(1, 6.0)

        assertTrue(delete.handle(DeleteShowRating(showId, user)).isRight())
        assertEquals(6.0, rating()!!.score?.value)
        assertNull(rating()!!.showScore)
        assertEquals(DeleteShowRatingHandlerError.RatingNotFound, delete.handle(DeleteShowRating(showId, user)).leftOrNull())

        deleteSeason.handle(DeleteSeasonRating(showId, SeasonNumber(1), user))
        rate.handle(RateShow(showId, user, 5.0))
        assertTrue(delete.handle(DeleteShowRating(showId, user)).isRight())
        assertNull(rating())
    }

    // --- Seen: rated means seen, like a movie ---

    @Test
    fun `a whole-show rating marks the show Watched and counts as rated`() {
        statuses.save(MediaStatus(user, MediaType.tvshow, showId.value, WatchStatus.WANT_TO_WATCH, 1L))

        rate.handle(RateShow(showId, user, 8.0))

        assertEquals(WatchStatus.WATCHED, statuses.find(user, MediaType.tvshow, showId.value)?.status)
        val state = reader.read(user, MediaType.tvshow, showId.value)
        assertTrue(state.rated)
        assertTrue(state.seen)
    }

    @Test
    fun `a whole-show rating leaves a Dropped show alone, and deleting it never touches the status`() {
        statuses.save(MediaStatus(user, MediaType.tvshow, showId.value, WatchStatus.DROPPED, 1L))
        rate.handle(RateShow(showId, user, 4.0))
        assertEquals(WatchStatus.DROPPED, statuses.find(user, MediaType.tvshow, showId.value)?.status)

        statuses.save(MediaStatus(user, MediaType.tvshow, showId.value, WatchStatus.WATCHED, 2L))
        delete.handle(DeleteShowRating(showId, user))
        assertEquals(WatchStatus.WATCHED, statuses.find(user, MediaType.tvshow, showId.value)?.status)
    }

    @Test
    fun `rating one season still only makes the show Watching`() {
        season(1, 7.0)

        assertEquals(WatchStatus.WATCHING, statuses.find(user, MediaType.tvshow, showId.value)?.status)
    }
}
