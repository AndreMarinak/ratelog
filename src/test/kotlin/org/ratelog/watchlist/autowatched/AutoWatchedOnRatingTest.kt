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
import org.ratelog.test.TvShowFactory
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.addseason.AddSeasonRating
import org.ratelog.tvshow.rating.addseason.AddSeasonRatingHandler
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRating
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRatingHandler
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus

class AutoWatchedOnRatingTest {

    private val statuses = InMemoryMediaStatusRepository()
    private val markWatched = MarkWatchedOnRatingHandler(statuses)

    // The real upstream rating handlers, wired to the decorated repositories the app uses.
    private val movies = InMemoryMovieRepository()
    private val ratings = AutoWatchedRatingRepository(InMemoryRatingRepository(), markWatched)
    private val addRating = AddRatingHandler(ratings)
    private val updateRating = UpdateRatingHandler(ratings)
    private val deleteRating = DeleteRatingHandler(movies, ratings)

    private val shows = InMemoryTvShowRepository()
    private val tvRatings = AutoWatchedTvRatingRepository(InMemoryTvRatingRepository(), markWatched)
    private val addSeasonRating = AddSeasonRatingHandler(tvRatings)
    private val deleteSeasonRating = DeleteSeasonRatingHandler(shows, tvRatings)

    private val user = User.Id(1)
    private val movie: Movie = movies.save(MovieFactory.aMovie(originalTitle = "Heat"))
    private val show: TvShow = shows.save(TvShowFactory.aTvShow(originalName = "Severance"))

    private fun movieStatus() = statuses.find(user, MediaType.movie, movie.id!!.value)?.status
    private fun showStatus() = statuses.find(user, MediaType.tvshow, show.id!!.value)?.status

    private fun setStatus(type: MediaType, id: Long, status: WatchStatus) =
        statuses.save(MediaStatus(user, type, id, status, updatedAtEpochMs = 1))

    private fun rateMovie(score: Double = 8.0) =
        addRating.handle(AddRating(movie.id!!, user, score, score, score, score, score, null, null))

    private fun rateSeason(season: Int) = addSeasonRating.handle(
        AddSeasonRating(show.id!!, SeasonNumber(season), user, Score(8.0), Score(8.0), Score(8.0), Score(8.0), Score(8.0), null, null)
    )

    @Test
    fun `rating a movie with no status marks it watched`() {
        assertTrue(rateMovie().isRight())
        assertEquals(WatchStatus.WATCHED, movieStatus())
    }

    @Test
    fun `want to watch and watching become watched`() {
        setStatus(MediaType.movie, movie.id!!.value, WatchStatus.WANT_TO_WATCH)
        rateMovie()
        assertEquals(WatchStatus.WATCHED, movieStatus())

        setStatus(MediaType.tvshow, show.id!!.value, WatchStatus.WATCHING)
        rateSeason(1)
        assertEquals(WatchStatus.WATCHED, showStatus())
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
    fun `rating a season marks the show watched`() {
        assertTrue(rateSeason(1).isRight())
        assertEquals(WatchStatus.WATCHED, showStatus())
    }

    @Test
    fun `deleting one season of several does not count as a rating`() {
        rateSeason(1)
        rateSeason(2)
        setStatus(MediaType.tvshow, show.id!!.value, WatchStatus.WATCHING)

        assertTrue(deleteSeasonRating.handle(DeleteSeasonRating(show.id!!, SeasonNumber(2), user)).isRight())

        assertEquals(WatchStatus.WATCHING, showStatus())
    }

    @Test
    fun `only the rating user's status changes`() {
        val other = User.Id(2)
        statuses.save(MediaStatus(other, MediaType.movie, movie.id!!.value, WatchStatus.WANT_TO_WATCH, 1))

        rateMovie()

        assertEquals(WatchStatus.WANT_TO_WATCH, statuses.find(other, MediaType.movie, movie.id!!.value)?.status)
    }

    @Test
    fun `handler reports whether it changed anything`() {
        val command = MarkWatchedOnRating(user, MediaType.movie, 42)

        assertTrue(markWatched.handle(command))
        assertFalse(markWatched.handle(command))
        assertNull(statuses.find(user, MediaType.tvshow, 42))
    }
}
