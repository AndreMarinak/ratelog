package org.ratelog.watchlist.setstatus

import org.ratelog.test.WatchlistHandlers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus

class SetWatchStatusHandlerTest {

    private lateinit var statuses: InMemoryMediaStatusRepository
    private lateinit var movies: InMemoryMovieRepository
    private lateinit var shows: InMemoryTvShowRepository
    private lateinit var handler: SetWatchStatusHandler

    private val user = User.Id(1)
    private val otherUser = User.Id(2)
    private var movieId = 0L
    private var showId = 0L

    @BeforeEach
    fun setUp() {
        statuses = InMemoryMediaStatusRepository()
        movies = InMemoryMovieRepository()
        shows = InMemoryTvShowRepository()
        handler = WatchlistHandlers.setStatus(statuses, movies, shows)
        movieId = movies.save(MovieFactory.aMovie(originalTitle = "Heat")).id!!.value
        showId = shows.save(TvShowFactory.aTvShow(originalName = "Severance")).id!!.value
    }

    @Test
    fun `sets a status on a movie that had none`() {
        val result = handler.handle(SetWatchStatus(user, MediaType.movie, movieId, WatchStatus.WANT_TO_WATCH))

        assertEquals(SetWatchStatusResult(previous = null, current = WatchStatus.WANT_TO_WATCH), result.getOrNull())
        assertEquals(WatchStatus.WANT_TO_WATCH, statuses.effective(user, MediaType.movie, movieId))
    }

    @Test
    fun `changes an existing status on a tv show`() {
        handler.handle(SetWatchStatus(user, MediaType.tvshow, showId, WatchStatus.WANT_TO_WATCH))

        val result = handler.handle(SetWatchStatus(user, MediaType.tvshow, showId, WatchStatus.WATCHING)).getOrNull()!!

        assertTrue(result.changed)
        assertEquals(WatchStatus.WANT_TO_WATCH, result.previous)
        assertEquals(WatchStatus.WATCHING, statuses.effective(user, MediaType.tvshow, showId))
        assertEquals(1, statuses.findByUser(user, null, null).size)
    }

    @Test
    fun `null status clears it`() {
        handler.handle(SetWatchStatus(user, MediaType.movie, movieId, WatchStatus.DROPPED))

        val result = handler.handle(SetWatchStatus(user, MediaType.movie, movieId, null)).getOrNull()!!

        assertEquals(WatchStatus.DROPPED, result.previous)
        assertNull(result.current)
        assertNull(statuses.find(user, MediaType.movie, movieId))
    }

    @Test
    fun `clearing when there is no status is a no-op`() {
        val result = handler.handle(SetWatchStatus(user, MediaType.movie, movieId, null)).getOrNull()!!

        assertFalse(result.changed)
        assertTrue(statuses.findByUser(user, null, null).isEmpty())
    }

    @Test
    fun `setting the same status again keeps the original timestamp`() {
        statuses.save(MediaStatus(user, MediaType.movie, movieId, WatchStatus.WATCHING, updatedAtEpochMs = 1000))

        val result = handler.handle(SetWatchStatus(user, MediaType.movie, movieId, WatchStatus.WATCHING)).getOrNull()!!

        assertFalse(result.changed)
        assertEquals(1000, statuses.find(user, MediaType.movie, movieId)?.updatedAtEpochMs)
    }

    @Test
    fun `statuses are per user`() {
        handler.handle(SetWatchStatus(user, MediaType.movie, movieId, WatchStatus.WATCHED))
        handler.handle(SetWatchStatus(otherUser, MediaType.movie, movieId, WatchStatus.DROPPED))

        assertEquals(WatchStatus.WATCHED, statuses.effective(user, MediaType.movie, movieId))
        assertEquals(WatchStatus.DROPPED, statuses.effective(otherUser, MediaType.movie, movieId))
    }

    @Test
    fun `movie and tv ids are separate`() {
        handler.handle(SetWatchStatus(user, MediaType.movie, movieId, WatchStatus.WATCHED))

        assertNull(statuses.find(user, MediaType.tvshow, movieId))
    }

    @Test
    fun `unknown title is MediaNotFound and saves nothing`() {
        val result = handler.handle(SetWatchStatus(user, MediaType.movie, 999, WatchStatus.WATCHED))

        assertEquals(SetWatchStatusError.MediaNotFound, result.leftOrNull())
        assertTrue(statuses.findByUser(user, null, null).isEmpty())
    }
}
