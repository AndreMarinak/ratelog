package org.ratelog.watchlist.autowatched

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.Score
import org.ratelog.SeasonNumber
import org.ratelog.movie.Movie
import org.ratelog.movie.rating.add.AddRating
import org.ratelog.movie.rating.add.AddRatingHandler
import org.ratelog.movie.rating.delete.DeleteRating
import org.ratelog.movie.rating.delete.DeleteRatingHandler
import org.ratelog.movie.rating.update.UpdateRating
import org.ratelog.movie.rating.update.UpdateRatingHandler
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.TvShowFactory
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.addseason.AddSeasonRating
import org.ratelog.tvshow.rating.addseason.AddSeasonRatingHandler
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRating
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRatingHandler
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate

class AutoWatchedOnRatingTest {

    private val statuses = InMemoryMediaStatusRepository()
    private val shows = InMemoryTvShowRepository()
    private val clock = MutableClock() // today: 2026-11-22
    private val markWatched = MarkWatchedOnRatingHandler(statuses, shows, clock)

    // The real upstream rating handlers, wired to the decorated repositories the app uses.
    private val movies = InMemoryMovieRepository()
    private val ratings = AutoWatchedRatingRepository(InMemoryRatingRepository(), markWatched)
    private val addRating = AddRatingHandler(ratings)
    private val updateRating = UpdateRatingHandler(ratings)
    private val deleteRating = DeleteRatingHandler(movies, ratings)

    private val tvRatings = AutoWatchedTvRatingRepository(InMemoryTvRatingRepository(), markWatched)
    private val addSeasonRating = AddSeasonRatingHandler(tvRatings)
    private val deleteSeasonRating = DeleteSeasonRatingHandler(shows, tvRatings)

    private val user = User.Id(1)
    private val movie: Movie = movies.save(MovieFactory.aMovie(originalTitle = "Heat"))
    // Two seasons, both aired.
    private val show: TvShow = shows.save(
        TvShowFactory.aTvShow(originalName = "Severance", lastSeasonNumber = 2, lastSeasonAirDate = LocalDate.parse("2025-01-17"), nextSeasonAirDate = null)
    )

    private fun movieStatus() = statuses.effective(user, MediaType.movie, movie.id!!.value)
    private fun showStatus() = statuses.effective(user, MediaType.tvshow, show.id!!.value)

    private fun setStatus(type: MediaType, id: Long, status: WatchStatus) =
        statuses.save(MediaStatus(user, type, id, status, updatedAtEpochMs = 1))

    private fun rateMovie(score: Double = 8.0) =
        addRating.handle(AddRating(movie.id!!, user, score, score, score, score, score, null, null))

    private fun rateSeason(season: Int, of: TvShow = show) = addSeasonRating.handle(
        AddSeasonRating(of.id!!, SeasonNumber(season), user, Score(8.0), Score(8.0), Score(8.0), Score(8.0), Score(8.0), null, null)
    )

    private fun statusOf(of: TvShow) = statuses.effective(user, MediaType.tvshow, of.id!!.value)

    private fun aShow(last: Int?, lastDate: LocalDate?, next: LocalDate? = null) = shows.save(
        TvShowFactory.aTvShow(originalName = "Show $last", lastSeasonNumber = last, lastSeasonAirDate = lastDate, nextSeasonAirDate = next)
    )

    @Test
    fun `rating a movie with no status marks it watched`() {
        assertTrue(rateMovie().isRight())
        assertEquals(WatchStatus.WATCHED, movieStatus())
    }

    @Test
    fun `a movie's want to watch and watching become watched`() {
        setStatus(MediaType.movie, movie.id!!.value, WatchStatus.WANT_TO_WATCH)
        rateMovie()
        assertEquals(WatchStatus.WATCHED, movieStatus())

        setStatus(MediaType.movie, movie.id!!.value, WatchStatus.WATCHING)
        updateRating.handle(UpdateRating(movie.id!!, user, 7.0, 7.0, 7.0, 7.0, 7.0, null, null))
        assertEquals(WatchStatus.WATCHED, movieStatus())
    }

    @Test
    fun `dropped stays dropped`() {
        setStatus(MediaType.movie, movie.id!!.value, WatchStatus.DROPPED)
        rateMovie()
        assertEquals(WatchStatus.DROPPED, movieStatus())
    }

    @Test
    fun `a failed rating does not change the status`() {
        setStatus(MediaType.movie, movie.id!!.value, WatchStatus.WANT_TO_WATCH)
        assertTrue(rateMovie(score = 11.0).isLeft())
        assertEquals(WatchStatus.WANT_TO_WATCH, movieStatus())
    }

    @Test
    fun `editing a rating also marks it watched`() {
        rateMovie()
        setStatus(MediaType.movie, movie.id!!.value, WatchStatus.WANT_TO_WATCH)

        updateRating.handle(UpdateRating(movie.id!!, user, 7.0, 7.0, 7.0, 7.0, 7.0, null, null))

        assertEquals(WatchStatus.WATCHED, movieStatus())
    }

    @Test
    fun `deleting a movie rating leaves the status alone`() {
        rateMovie()
        setStatus(MediaType.movie, movie.id!!.value, WatchStatus.WATCHING)

        assertTrue(deleteRating.handle(DeleteRating(movie.id!!, user)).isRight())

        assertEquals(WatchStatus.WATCHING, movieStatus())
    }

    @Test
    fun `rating one season of two makes the show watching, not watched`() {
        assertTrue(rateSeason(1).isRight())
        assertEquals(WatchStatus.WATCHING, showStatus())
    }

    @Test
    fun `want to watch becomes watching on the first season rating`() {
        setStatus(MediaType.tvshow, show.id!!.value, WatchStatus.WANT_TO_WATCH)
        rateSeason(1)
        assertEquals(WatchStatus.WATCHING, showStatus())
    }

    @Test
    fun `the show becomes watched once every aired season is rated`() {
        rateSeason(2)
        assertEquals(WatchStatus.WATCHING, showStatus(), "seasons can be rated in any order")
        rateSeason(1)
        assertEquals(WatchStatus.WATCHED, showStatus())
    }

    @Test
    fun `a one-season show is watched as soon as that season is rated`() {
        val miniSeries = aShow(last = 1, lastDate = LocalDate.parse("2024-03-01"))
        rateSeason(1, miniSeries)
        assertEquals(WatchStatus.WATCHED, statusOf(miniSeries))
    }

    @Test
    fun `specials never count, rated or not`() {
        assertTrue(markWatched.handle(MarkWatchedOnRating(user, MediaType.tvshow, show.id!!.value, ratedSeasons = setOf(0, 1))))
        assertEquals(WatchStatus.WATCHING, showStatus(), "season 0 doesn't stand in for season 2")

        markWatched.handle(MarkWatchedOnRating(user, MediaType.tvshow, show.id!!.value, ratedSeasons = setOf(1, 2)))
        assertEquals(WatchStatus.WATCHED, showStatus(), "unrated specials don't hold it back")
    }

    @Test
    fun `seasons that haven't aired, or have no date, don't need a rating`() {
        val futureLast = aShow(last = 3, lastDate = LocalDate.parse("2027-02-01"), next = LocalDate.parse("2027-02-01"))
        rateSeason(1, futureLast)
        rateSeason(2, futureLast)
        assertEquals(WatchStatus.WATCHED, statusOf(futureLast), "season 3 airs in 2027")

        val undated = aShow(last = 4, lastDate = null)
        listOf(1, 2, 3).forEach { rateSeason(it, undated) }
        assertEquals(WatchStatus.WATCHED, statusOf(undated), "season 4 has no air date yet")

        val twoAhead = aShow(last = 5, lastDate = null, next = LocalDate.parse("2027-01-10"))
        listOf(1, 2).forEach { rateSeason(it, twoAhead) }
        assertEquals(WatchStatus.WATCHING, statusOf(twoAhead))
        rateSeason(3, twoAhead)
        assertEquals(WatchStatus.WATCHED, statusOf(twoAhead), "season 4 (Jan 2027) and season 5 (no date) are not out")
    }

    @Test
    fun `a show with nothing aired stays watching`() {
        val notOut = aShow(last = 1, lastDate = LocalDate.parse("2027-03-01"), next = LocalDate.parse("2027-03-01"))
        rateSeason(1, notOut)
        assertEquals(WatchStatus.WATCHING, statusOf(notOut))

        val noSeasons = aShow(last = null, lastDate = null)
        markWatched.handle(MarkWatchedOnRating(user, MediaType.tvshow, noSeasons.id!!.value, ratedSeasons = setOf(1)))
        assertEquals(WatchStatus.WATCHING, statusOf(noSeasons))
    }

    @Test
    fun `a watched show is never downgraded, a dropped one never touched`() {
        setStatus(MediaType.tvshow, show.id!!.value, WatchStatus.WATCHED)
        rateSeason(1)
        assertEquals(WatchStatus.WATCHED, showStatus(), "one rated season doesn't undo Watched")

        val dropped = aShow(last = 1, lastDate = LocalDate.parse("2020-01-01"))
        setStatus(MediaType.tvshow, dropped.id!!.value, WatchStatus.DROPPED)
        rateSeason(1, dropped)
        assertEquals(WatchStatus.DROPPED, statusOf(dropped), "even with every season rated")
    }

    @Test
    fun `aired seasons come from the stored show`() {
        val today = LocalDate.parse("2026-11-22")
        fun aired(last: Int?, lastDate: LocalDate?, next: LocalDate? = null) =
            AiredSeasons.of(TvShowFactory.aTvShow(originalName = "x", lastSeasonNumber = last, lastSeasonAirDate = lastDate, nextSeasonAirDate = next), today)

        assertEquals(setOf(1, 2, 3), aired(3, today), "a season that airs today has aired")
        assertEquals(setOf(1, 2), aired(3, today.plusDays(1)))
        assertEquals(setOf(1, 2), aired(3, null))
        assertEquals(setOf(1, 2, 3), aired(3, today.minusDays(5), next = today.minusDays(1)), "a 'next' date that has passed is aired")
        assertEquals(setOf(1), aired(3, null, next = today.plusDays(30)))
        assertEquals(emptySet<Int>(), aired(1, null))
        assertEquals(emptySet<Int>(), aired(0, today.minusYears(1)), "only specials")
        assertEquals(emptySet<Int>(), aired(null, null))
    }

    @Test
    fun `deleting a season rating changes nothing`() {
        rateSeason(1)
        rateSeason(2)
        assertEquals(WatchStatus.WATCHED, showStatus())

        assertTrue(deleteSeasonRating.handle(DeleteSeasonRating(show.id!!, SeasonNumber(2), user)).isRight())
        assertEquals(WatchStatus.WATCHED, showStatus(), "not downgraded to Watching")

        // Only on the watchlist (no status): still nothing changes.
        statuses.delete(user, MediaType.tvshow, show.id!!.value)
        setStatus(MediaType.tvshow, show.id!!.value, WatchStatus.WANT_TO_WATCH)
        assertTrue(deleteSeasonRating.handle(DeleteSeasonRating(show.id!!, SeasonNumber(1), user)).isRight())
        assertEquals(WatchStatus.WANT_TO_WATCH, showStatus())
    }

    @Test
    fun `only the rating user's status changes`() {
        val other = User.Id(2)
        statuses.save(MediaStatus(other, MediaType.movie, movie.id!!.value, WatchStatus.WANT_TO_WATCH, 1))

        rateMovie()

        assertEquals(WatchStatus.WANT_TO_WATCH, statuses.effective(other, MediaType.movie, movie.id!!.value))
    }

    @Test
    fun `handler reports whether it changed anything`() {
        val command = MarkWatchedOnRating(user, MediaType.movie, 42)

        assertTrue(markWatched.handle(command))
        assertFalse(markWatched.handle(command))
        assertNull(statuses.find(user, MediaType.tvshow, 42))
    }
}
