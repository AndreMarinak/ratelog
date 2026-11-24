package org.ratelog.api.rewatch

import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.Status
import org.ratelog.api.ApiException
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.media.TitleResolver
import org.ratelog.api.status.ApiStatusController
import org.ratelog.api.status.BulkStatusService
import org.ratelog.movie.MovieDescription
import org.ratelog.rewatch.delete.DeleteRewatchHandler
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryRewatchRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.UserFactory
import org.ratelog.test.WatchlistHandlers
import org.ratelog.tmdb.TmdbClient
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.getwatchlist.GetWatchlistHandler
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate

/** Fork (2026-11-24, rewatch log): POST / DELETE /api/v1/rewatches, and `watchCount` / `lastWatchedAt` on statuses. */
class ApiRewatchControllerTest {
    private val clock = MutableClock() // 2026-11-22T12:00Z
    private val tmdbClient: TmdbClient = mock()
    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val ratings = InMemoryRatingRepository()
    private val tvRatings = InMemoryTvRatingRepository()
    private val rewatches = InMemoryRewatchRepository()
    private val importer = MediaImporter(tmdbClient, movies, InMemoryMovieDescriptionRepository(), shows, InMemoryTvDescriptionRepository())
    private val controller = ApiRewatchController(
        WatchlistHandlers.logRewatch(rewatches, statuses, movies, shows, ratings, tvRatings, clock),
        DeleteRewatchHandler(rewatches),
        importer,
        clock,
    )
    private val setStatus = WatchlistHandlers.setStatus(statuses, movies, shows, ratings, tvRatings)
    private val statusApi = ApiStatusController(
        GetWatchlistHandler(statuses, movies, InMemoryMovieDescriptionRepository(), ratings, shows, InMemoryTvDescriptionRepository(), tvRatings),
        setStatus,
        BulkStatusService(TitleResolver(tmdbClient), importer, setStatus),
        importer,
        rewatches,
    )
    private val user = UserFactory.aUser(id = 1)
    private val other = UserFactory.aUser(id = 2)
    private val heat = movies.save(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseYear = 1995, releaseDate = LocalDate.parse("1995-12-15")))
    private val doomsday = movies.save(MovieFactory.aMovie(tmdbId = 1003596, originalTitle = "Avengers: Doomsday", releaseDate = LocalDate.parse("2026-12-18"), status = Status.POST_PRODUCTION))

    init {
        whenever(tmdbClient.movieTranslations(any(), any())).thenReturn(emptyList<MovieDescription>().right())
    }

    private fun watched(id: Long = heat.id!!.value) = statuses.save(MediaStatus(user.id!!, MediaType.movie, id, WatchStatus.WATCHED, 1L))

    private fun error(block: () -> Unit): ApiException = assertThrows<ApiException> { block() }

    @Test
    fun `POST logs a rewatch now and answers 201 with the count, DELETE removes it`() {
        watched()
        val first = controller.log(user, "movie", 949, null)
        assertEquals(HttpStatus.CREATED, first.statusCode)
        with(first.body!!) {
            assertEquals("Heat", title)
            assertEquals("movie", type)
            assertEquals(2, watchCount)
            assertEquals(Instant.parse("2026-11-22T12:00:00Z").toString(), watchedAt)
            assertEquals(watchedAt, lastWatchedAt)
        }
        val second = controller.log(user, "film", 949, LogRewatchRequest(watchedAt = "2026-11-20")).body!!
        assertEquals(3, second.watchCount)
        assertEquals("2026-11-20T12:00:00Z", second.watchedAt, "a date is noon that day in the server's zone")
        assertEquals("2026-11-22T12:00:00Z", second.lastWatchedAt, "the latest stays the latest")

        assertEquals(HttpStatus.NO_CONTENT, controller.delete(user, first.body!!.id).statusCode)
        assertEquals(1, rewatches.all().size)
        assertEquals(HttpStatus.NOT_FOUND, error { controller.delete(user, first.body!!.id) }.status, "already gone")
        assertEquals(HttpStatus.NOT_FOUND, error { controller.delete(other, second.id) }.status, "not yours")
    }

    @Test
    fun `errors - not seen, not out, bad date, bad type or id`() {
        with(error { controller.log(user, "movie", 949, null) }) {
            assertEquals(HttpStatus.CONFLICT, status)
            assertEquals("not_seen", code)
        }
        assertEquals("not_seen", error { controller.log(user, "movie", 4242, null) }.code, "never stored, so never seen")

        watched(doomsday.id!!.value)
        assertEquals("not_released", error { controller.log(user, "movie", 1003596, null) }.code)

        watched()
        assertEquals("invalid_date", error { controller.log(user, "movie", 949, LogRewatchRequest("2026-12-01")) }.code, "future")
        assertEquals("invalid_date", error { controller.log(user, "movie", 949, LogRewatchRequest("last tuesday")) }.code)
        assertEquals("invalid_type", error { controller.log(user, "book", 949, null) }.code)
        assertEquals("invalid_parameter", error { controller.log(user, "movie", 0, null) }.code)
        assertTrue(rewatches.all().isEmpty())
    }

    @Test
    fun `statuses carry watchCount and lastWatchedAt - 1 for seen, 0 for unseen, rows of an un-seen title not counted`() {
        // Rating sets Watched in the app (the auto-Watched wrapper); the fakes here need both.
        watched()
        ratings.save(RatingFactory.aQuickRating(movieId = heat.id!!, userId = user.id!!, score = 9.0))
        controller.log(user, "movie", 949, null)
        controller.log(user, "movie", 949, null)
        val item = statusApi.list(user, null, null).items.single { it.tmdbId == 949 }
        assertEquals(3, item.watchCount)
        assertEquals("2026-11-22T12:00:00Z", item.lastWatchedAt)

        // Only on the watchlist: not seen.
        statuses.addToWatchlist(org.ratelog.watchlist.WatchlistEntry(user.id!!, MediaType.movie, doomsday.id!!.value, 5))
        val unseen = statusApi.list(user, "want_to_watch", "movie").items.single { it.tmdbId == 1003596 }
        assertEquals(0, unseen.watchCount)
        assertNull(unseen.lastWatchedAt)

        // A Watched title with no rewatch: seen once.
        val dune = movies.save(MovieFactory.aMovie(tmdbId = 438631, originalTitle = "Dune", releaseDate = LocalDate.parse("2021-09-15")))
        watched(dune.id!!.value)
        val once = statusApi.list(user, "watched", "movie").items.single { it.tmdbId == 438631 }
        assertEquals(1, once.watchCount)
        assertNull(once.lastWatchedAt)
    }
}
