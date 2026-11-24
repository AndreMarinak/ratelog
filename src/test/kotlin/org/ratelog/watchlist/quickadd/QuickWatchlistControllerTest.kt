package org.ratelog.watchlist.quickadd

import org.ratelog.test.WatchlistHandlers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.ratelog.MediaType
import org.ratelog.api.media.MediaImporter
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler

class QuickWatchlistControllerTest {

    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val controller = QuickWatchlistController(
        mock<MediaImporter>(),
        statuses,
        WatchlistHandlers.setWatchlist(statuses, movies, InMemoryTvShowRepository()),
    )
    private val user = User.Id(1)
    private val movieId = movies.save(MovieFactory.aMovie(tmdbId = 862, originalTitle = "Toy Story")).id!!.value

    private fun status() = statuses.effective(user, MediaType.movie, movieId)

    @Test
    fun `adds a title with no status to the watchlist`() {
        assertEquals(QuickWatchlistOutcome.ADDED, controller.toggle(user, MediaType.movie, movieId))
        assertEquals(WatchStatus.WANT_TO_WATCH, status())
    }

    @Test
    fun `second click removes it again`() {
        controller.toggle(user, MediaType.movie, movieId)
        assertEquals(QuickWatchlistOutcome.REMOVED, controller.toggle(user, MediaType.movie, movieId))
        assertNull(status())
    }

    @Test
    fun `leaves watching and dropped alone`() {
        listOf(WatchStatus.WATCHING, WatchStatus.DROPPED).forEach { existing ->
            statuses.save(MediaStatus(user, MediaType.movie, movieId, existing, 0L))
            assertEquals(QuickWatchlistOutcome.UNCHANGED, controller.toggle(user, MediaType.movie, movieId))
            assertEquals(existing, status())
        }
    }

    @Test
    fun `fork 2026-11-24 - a Watched title goes on the watchlist and stays Watched, and comes off again still Watched`() {
        statuses.save(MediaStatus(user, MediaType.movie, movieId, WatchStatus.WATCHED, 0L))

        assertEquals(QuickWatchlistOutcome.ADDED, controller.toggle(user, MediaType.movie, movieId))
        assertEquals(WatchStatus.WATCHED, statuses.find(user, MediaType.movie, movieId)?.status, "still seen")
        assertEquals(true, statuses.findWatchlistEntry(user, MediaType.movie, movieId)?.rewatch, "added while seen: a rewatch")

        assertEquals(QuickWatchlistOutcome.REMOVED, controller.toggle(user, MediaType.movie, movieId))
        assertEquals(WatchStatus.WATCHED, statuses.find(user, MediaType.movie, movieId)?.status, "removing keeps it seen")
        assertNull(statuses.findWatchlistEntry(user, MediaType.movie, movieId))
    }
}
