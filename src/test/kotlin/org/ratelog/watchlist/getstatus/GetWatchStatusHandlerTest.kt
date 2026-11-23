package org.ratelog.watchlist.getstatus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus

class GetWatchStatusHandlerTest {

    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val handler = GetWatchStatusHandler(statuses, movies, shows)
    private val user = User.Id(1)

    @Test
    fun `finds the status by tmdb id and type`() {
        val movie = movies.save(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat"))
        val show = shows.save(TvShowFactory.aTvShow(tmdbId = 949, originalName = "Same id, other namespace"))
        statuses.save(MediaStatus(user, MediaType.movie, movie.id!!.value, WatchStatus.WATCHING, 1))
        statuses.save(MediaStatus(user, MediaType.tvshow, show.id!!.value, WatchStatus.DROPPED, 1))

        assertEquals(WatchStatus.WATCHING, handler.handle(GetWatchStatus(user, MediaType.movie, TmdbId(949))))
        assertEquals(WatchStatus.DROPPED, handler.handle(GetWatchStatus(user, MediaType.tvshow, TmdbId(949))))
        assertNull(handler.handle(GetWatchStatus(User.Id(2), MediaType.movie, TmdbId(949))))
    }

    @Test
    fun `title not saved locally yet has no status`() {
        assertNull(handler.handle(GetWatchStatus(user, MediaType.movie, TmdbId(12345))))
    }
}
