package org.ratelog.api.watchlist

import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.api.ApiException
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.media.TitleResolver
import org.ratelog.api.status.ApiStatusController
import org.ratelog.api.status.BulkStatusService
import org.ratelog.api.status.SetStatusRequest
import org.ratelog.api.status.StatusItemInput
import org.ratelog.movie.MovieDescription
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.RatingFactory
import org.ratelog.test.UserFactory
import org.ratelog.test.WatchlistHandlers
import org.ratelog.tmdb.TmdbClient
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.getwatchlist.GetWatchlistHandler
import org.springframework.http.HttpStatus

/** Fork (2026-11-24): PUT / DELETE /api/v1/watchlist, and /api/v1/statuses staying backward compatible. */
class ApiWatchlistControllerTest {
    private val tmdbClient: TmdbClient = mock()
    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val ratings = InMemoryRatingRepository()
    private val tvRatings = InMemoryTvRatingRepository()
    private val importer = MediaImporter(tmdbClient, movies, InMemoryMovieDescriptionRepository(), shows, InMemoryTvDescriptionRepository())
    private val controller = ApiWatchlistController(WatchlistHandlers.setWatchlist(statuses, movies, shows, ratings, tvRatings), importer)
    private val setStatus = WatchlistHandlers.setStatus(statuses, movies, shows, ratings, tvRatings)
    private val statusApi = ApiStatusController(
        GetWatchlistHandler(statuses, movies, InMemoryMovieDescriptionRepository(), ratings, shows, InMemoryTvDescriptionRepository(), tvRatings),
        setStatus,
        BulkStatusService(TitleResolver(tmdbClient), importer, setStatus),
        importer,
    )
    private val user = UserFactory.aUser(id = 1)
    private val heat = movies.save(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseYear = 1995))

    init {
        whenever(tmdbClient.movieTranslations(any(), any())).thenReturn(emptyList<MovieDescription>().right())
    }

    private fun watched() = statuses.save(MediaStatus(user.id!!, MediaType.movie, heat.id!!.value, WatchStatus.WATCHED, 1L))

    @Test
    fun `PUT adds a Watched title and keeps it seen, DELETE takes it off and it stays seen`() {
        watched()

        val added = controller.add(user, "movie", 949)
        assertTrue(added.onWatchlist)
        assertTrue(added.changed)
        assertTrue(added.seen)
        assertEquals("watched", added.status)
        assertEquals("Heat", added.title)

        assertFalse(controller.add(user, "movie", 949).changed, "already on it")

        val removed = controller.remove(user, "movie", 949).body!!
        assertFalse(removed.onWatchlist)
        assertTrue(removed.seen)
        assertEquals("watched", removed.status)

        val again = assertThrows(ApiException::class.java) { controller.remove(user, "tv", 949) }
        assertEquals(HttpStatus.NOT_FOUND, again.status)
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException::class.java) { controller.remove(user, "movie", 949) }.status)
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException::class.java) { controller.add(user, "movie", 0) }.status)
    }

    @Test
    fun `a rated movie goes on the watchlist with status null and seen true`() {
        ratings.save(RatingFactory.aQuickRating(movieId = heat.id!!, userId = user.id!!, score = 8.0))

        val added = controller.add(user, "movie", 949)

        assertTrue(added.onWatchlist)
        assertTrue(added.seen)
        assertEquals(null, added.status)
    }

    @Test
    fun `statuses - want_to_watch on a Watched title keeps it watched and reports onWatchlist, GET lists it in both`() {
        watched()

        val change = statusApi.set(user, "movie", 949, SetStatusRequest("want_to_watch"))
        assertEquals("watched", change.status, "never unseen")
        assertEquals("watched", change.previousStatus)
        assertTrue(change.onWatchlist)

        val toWatch = statusApi.list(user, "want_to_watch", "movie")
        val item = toWatch.items.single()
        assertEquals("watched", item.status)
        assertTrue(item.onWatchlist)
        assertTrue(item.seen)
        assertEquals(1, toWatch.counts["want_to_watch"])
        assertEquals(1, toWatch.counts["watched"])

        assertTrue(statusApi.list(user, "watched", "movie").items.single().onWatchlist)
        assertEquals(1, statusApi.list(user, null, null).items.size, "once per title")

        // DELETE /statuses clears both, like the one status it used to be.
        assertEquals(HttpStatus.NO_CONTENT, statusApi.clear(user, "movie", 949).statusCode)
        assertEquals(0, statusApi.list(user, null, null).items.size)
    }

    @Test
    fun `statuses - an unseen title on the watchlist still reads want_to_watch, and bulk imports keep working`() {
        val result = statusApi.bulk(
            user,
            org.ratelog.api.status.BulkStatusRequest(status = "want_to_watch", items = listOf(StatusItemInput(tmdbId = 949, type = "movie"))),
        )
        assertEquals(listOf("want_to_watch"), result.updated.map { it.status })
        assertTrue(result.updated.single().onWatchlist)

        val item = statusApi.list(user, "want_to_watch", null).items.single()
        assertEquals("want_to_watch", item.status)
        assertTrue(item.onWatchlist)
        assertFalse(item.seen)

        val seen = statusApi.bulk(
            user,
            org.ratelog.api.status.BulkStatusRequest(items = listOf(StatusItemInput(tmdbId = 949, type = "movie", status = "watched"))),
        )
        assertEquals("watched", seen.updated.single().status)
        assertFalse(seen.updated.single().onWatchlist, "marked seen while unseen on the watchlist: it leaves the watchlist")
    }
}
