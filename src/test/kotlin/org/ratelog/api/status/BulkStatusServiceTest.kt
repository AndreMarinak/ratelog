package org.ratelog.api.status

import org.ratelog.test.WatchlistHandlers
import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.api.ApiException
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.media.TitleResolver
import org.ratelog.movie.MovieDescription
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tmdb.TmdbError
import org.ratelog.tmdb.TmdbMovieResponse
import org.ratelog.tmdb.TmdbTvShowResponse
import org.ratelog.tvshow.TvDescription
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.springframework.http.HttpStatus

class BulkStatusServiceTest {

    private val tmdbClient: TmdbClient = mock()
    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val service = BulkStatusService(
        TitleResolver(tmdbClient),
        MediaImporter(tmdbClient, movies, InMemoryMovieDescriptionRepository(), shows, InMemoryTvDescriptionRepository()),
        WatchlistHandlers.setStatus(statuses, movies, shows),
    )
    private val user = User.Id(1)

    @BeforeEach
    fun setUp() {
        // Defaults: empty searches, no translations, unknown ids. Specific stubs below override these.
        whenever(tmdbClient.searchMovies(any(), any(), any())).thenReturn((emptyList<TmdbMovieResponse>() to 1).right())
        whenever(tmdbClient.searchTvShows(any(), any(), any())).thenReturn((emptyList<TmdbTvShowResponse>() to 1).right())
        whenever(tmdbClient.movieTranslations(any(), any())).thenReturn(emptyList<MovieDescription>().right())
        whenever(tmdbClient.tvTranslations(any(), any())).thenReturn(emptyList<TvDescription>().right())
        whenever(tmdbClient.movieDetails(any())).thenReturn(TmdbError.MovieNotFound.left())

        whenever(tmdbClient.searchMovies("Heat", Lang.en, 1)).thenReturn(
            (listOf(
                TmdbMovieResponse(949, "Heat", "Heat", releaseDate = "1995-12-15"),
                TmdbMovieResponse(11152, "Heat", "Heat", releaseDate = "1986-03-14"),
            ) to 1).right()
        )
        whenever(tmdbClient.movieDetails(TmdbId(949))).thenReturn(
            MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseYear = 1995).right()
        )
        whenever(tmdbClient.tvShowDetails(TmdbId(1396))).thenReturn(
            TvShowFactory.aTvShow(tmdbId = 1396, originalName = "Breaking Bad", firstAirYear = 2008).right()
        )
    }

    private fun statusOf(type: MediaType, tmdbId: Int): WatchStatus? {
        val localId = when (type) {
            MediaType.movie -> movies.findByTmdbId(TmdbId(tmdbId))?.id?.value
            MediaType.tvshow -> shows.findByTmdbId(TmdbId(tmdbId))?.id?.value
        } ?: return null
        return statuses.effective(user, type, localId)
    }

    @Test
    fun `sets statuses by title + year and by tmdb id, importing titles`() {
        val result = service.apply(
            user, Lang.en, null,
            listOf(
                StatusItemInput(title = "Heat", year = 1995, status = "want_to_watch"),
                StatusItemInput(tmdbId = 1396, type = "tv", status = "watching"),
            ),
        )

        assertEquals(listOf(949, 1396), result.updated.map { it.tmdbId })
        assertEquals(listOf("want_to_watch", "watching"), result.updated.map { it.status })
        assertEquals(listOf(null, null), result.updated.map { it.previousStatus })
        assertEquals(WatchStatus.WANT_TO_WATCH, statusOf(MediaType.movie, 949))
        assertEquals(WatchStatus.WATCHING, statusOf(MediaType.tvshow, 1396))
        assertEquals("2 updated, 0 unchanged, 0 unresolved", result.summary)
    }

    @Test
    fun `top-level status is the default and item status overrides it`() {
        val result = service.apply(
            user, Lang.en, WatchStatus.WANT_TO_WATCH,
            listOf(StatusItemInput(title = "Heat", year = 1995), StatusItemInput(tmdbId = 1396, type = "tv", status = "dropped")),
        )

        assertEquals(listOf("want_to_watch", "dropped"), result.updated.map { it.status })
    }

    @Test
    fun `same status again is reported as unchanged, a different one as updated with the previous one`() {
        service.apply(user, Lang.en, WatchStatus.WATCHING, listOf(StatusItemInput(tmdbId = 949, type = "movie")))

        val again = service.apply(user, Lang.en, WatchStatus.WATCHING, listOf(StatusItemInput(tmdbId = 949, type = "movie")))
        assertEquals(listOf(949), again.unchanged.map { it.tmdbId })
        assertTrue(again.updated.isEmpty())

        val changed = service.apply(user, Lang.en, WatchStatus.WATCHED, listOf(StatusItemInput(tmdbId = 949, type = "movie")))
        assertEquals("watching", changed.updated.single().previousStatus)
    }

    @Test
    fun `unresolved items are reported and do not stop the batch`() {
        val result = service.apply(
            user, Lang.en, null,
            listOf(
                StatusItemInput(title = "Heat", status = "watched"),                            // ambiguous: two "Heat"s
                StatusItemInput(title = "Definitely Not A Film", year = 2001, status = "watched"), // not_found
                StatusItemInput(tmdbId = 949, status = "watched"),                              // invalid: no type
                StatusItemInput(tmdbId = 949, type = "movie", status = "loved"),                // invalid: unknown status
                StatusItemInput(tmdbId = 949, type = "movie"),                                  // invalid: no status
                StatusItemInput(tmdbId = 777, type = "movie", status = "watched"),              // not_found on TMDB
                StatusItemInput(title = "Heat", year = 1995, type = "film", status = "seen"),   // ok
            ),
        )

        assertEquals(listOf("ambiguous", "not_found", "invalid", "invalid", "invalid", "not_found"), result.unresolved.map { it.reason })
        assertEquals(setOf(949, 11152), result.unresolved.first().candidates.map { it.tmdbId }.toSet())
        assertEquals("loved", result.unresolved[3].input.status)
        assertEquals(listOf(949), result.updated.map { it.tmdbId })
        assertEquals(WatchStatus.WATCHED, statusOf(MediaType.movie, 949))
        assertNull(movies.findByTmdbId(TmdbId(777)))
    }

    @Test
    fun `missing TMDB key is reported per item`() {
        whenever(tmdbClient.searchMovies("Heat", Lang.en, 1)).thenReturn(TmdbError.ApiKeyMissing.left())

        val result = service.apply(user, Lang.en, WatchStatus.WATCHED, listOf(StatusItemInput(title = "Heat", year = 1995, type = "movie")))

        assertEquals("tmdb_error", result.unresolved.single().reason)
    }

    @Test
    fun `existing local titles are reused without calling TMDB details`() {
        movies.save(MovieFactory.aMovie(tmdbId = 603, originalTitle = "The Matrix", releaseYear = 1999))

        val result = service.apply(user, Lang.en, WatchStatus.WATCHED, listOf(StatusItemInput(tmdbId = 603, type = "movie")))

        assertEquals("The Matrix", result.updated.single().title)
    }

    @Test
    fun `setOne imports and sets, and maps TMDB failures to API errors`() {
        val dto = service.setOne(user, Lang.en, 1396, MediaType.tvshow, WatchStatus.WATCHING)
        assertEquals("tv", dto.type)
        assertEquals("watching", dto.status)
        assertEquals(WatchStatus.WATCHING, statusOf(MediaType.tvshow, 1396))

        val notFound = assertThrows(ApiException::class.java) { service.setOne(user, Lang.en, 777, MediaType.movie, WatchStatus.WATCHED) }
        assertEquals(HttpStatus.NOT_FOUND, notFound.status)

        whenever(tmdbClient.movieDetails(TmdbId(778))).thenReturn(TmdbError.ApiKeyMissing.left())
        val notConfigured = assertThrows(ApiException::class.java) { service.setOne(user, Lang.en, 778, MediaType.movie, WatchStatus.WATCHED) }
        assertEquals("tmdb_not_configured", notConfigured.code)
    }
}
