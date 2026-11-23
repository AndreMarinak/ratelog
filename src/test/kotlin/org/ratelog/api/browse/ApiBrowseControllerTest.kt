package org.ratelog.api.browse

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.api.ApiException
import org.ratelog.browse.BrowseParams
import org.ratelog.browse.BrowseTitle
import org.ratelog.browse.BrowseTitlePage
import org.ratelog.browse.TmdbBrowseClient
import org.ratelog.browse.TmdbBrowseError
import org.ratelog.browse.TmdbListRequest
import org.ratelog.browse.discover.BrowseHandler
import org.ratelog.test.InMemoryBrowseLibraryRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.springframework.http.HttpStatus
import java.time.LocalDate

class ApiBrowseControllerTest {

    private val client: TmdbBrowseClient = mock()
    private val library = InMemoryBrowseLibraryRepository()
    private val clock = MutableClock()
    private val controller = ApiBrowseController(BrowseHandler(client, library, clock), clock)
    private val user = UserFactory.aUser(id = 1)
    private val requests = mutableListOf<TmdbListRequest>()

    private fun serve(totalPages: Int = 3) {
        whenever(client.page(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<TmdbListRequest>(0)
            requests += request
            val page = request.params.getValue("page").toInt()
            val items = listOf(
                BrowseTitle(603, request.type, "The Matrix", LocalDate.parse("1999-03-31"), "/m.jpg", 8.2, 26000, listOf(28, 878), "en"),
                BrowseTitle(604, request.type, "Reloaded", null, null, null, 0, emptyList(), "en"),
            )
            BrowseTitlePage(items, page, totalPages, 60).right()
        }
    }

    private fun assertBadRequest(code: String, params: BrowseParams, messageContains: String) {
        val e = assertThrows<ApiException> { controller.browse(user, params) }
        assertEquals(HttpStatus.BAD_REQUEST, e.status)
        assertEquals(code, e.code)
        assertTrue(e.message.contains(messageContains), e.message)
    }

    @Test
    fun `given filters then returns titles, normalized filters and paging`() {
        // Given
        serve()
        val movie = library.movies.save(MovieFactory.aMovie(tmdbId = 603, originalTitle = "The Matrix"))
        library.ratings.save(RatingFactory.aQuickRating(movieId = movie.id!!, userId = user.id!!, score = 9.0))
        library.statuses.save(MediaStatus(user.id!!, MediaType.movie, movie.id!!.value, WatchStatus.WATCHED, 1L))

        // When
        val response = controller.browse(user, BrowseParams(type = "movie", decade = "1990s", minRating = "7", genre = "878", sort = "rating"))

        // Then
        assertEquals("movie", response.type)
        assertEquals("1990s", response.filters.decade)
        assertEquals(7.0, response.filters.minRating)
        assertEquals("science-fiction", response.filters.genre)
        assertEquals(878, response.filters.genreId)
        assertEquals("rating", response.filters.sort)
        assertEquals(1, response.page)
        assertEquals(2, response.nextPage)
        assertEquals(3, response.totalPages)
        assertEquals(60, response.totalResults)
        assertTrue(response.ignored.isEmpty())

        val matrix = response.results[0]
        assertEquals(
            BrowseTitleDto(603, "movie", "The Matrix", 1999, "https://image.tmdb.org/t/p/w342/m.jpg", 8.2, "/movie/603", 9.0, "watched"),
            matrix,
        )
        assertEquals(BrowseTitleDto(604, "movie", "Reloaded", null, null, null, "/movie/604", null, null), response.results[1])
        assertEquals("vote_average.desc", requests.single().params["sort_by"])
    }

    @Test
    fun `given TV on the last page then nextPage is null and urls point at the TV pages`() {
        serve(totalPages = 2)

        val response = controller.browse(user, BrowseParams(type = "tv", page = "2"))

        assertEquals("tv", response.type)
        assertNull(response.nextPage)
        assertEquals("/tv/603", response.results[0].url)
        assertEquals("tv", response.results[0].type)
    }

    @Test
    fun `given bad values then answers 400 without calling TMDB`() {
        assertBadRequest("invalid_type", BrowseParams(type = "anime"), "type")
        assertBadRequest("invalid_parameter", BrowseParams(decade = "2015s"), "decade")
        assertBadRequest("invalid_parameter", BrowseParams(year = "1700"), "year")
        assertBadRequest("invalid_parameter", BrowseParams(type = "tv", year = "upcoming"), "only available for movies")
        assertBadRequest("invalid_parameter", BrowseParams(minRating = "11"), "minRating")
        assertBadRequest("invalid_parameter", BrowseParams(type = "tv", genre = "horror"), "unknown tv genre")
        assertBadRequest("invalid_parameter", BrowseParams(provider = "netflix"), "provider")
        assertBadRequest("invalid_parameter", BrowseParams(language = "english"), "language")
        assertBadRequest("invalid_parameter", BrowseParams(type = "tv", runtime = "short"), "runtime")
        assertBadRequest("invalid_parameter", BrowseParams(popular = "today"), "popular")
        assertBadRequest("invalid_parameter", BrowseParams(sort = "random"), "sort")
        assertBadRequest("invalid_parameter", BrowseParams(hideRated = "perhaps"), "hideRated")
        assertBadRequest("invalid_parameter", BrowseParams(page = "501"), "page")
        verify(client, never()).page(any())
    }

    @Test
    fun `given combinations then reports what was ignored`() {
        serve()

        val response = controller.browse(user, BrowseParams(popular = "week", sort = "title", provider = "8", runtime = "long"))

        assertEquals("week", response.filters.popular)
        assertNull(response.filters.sort)
        assertEquals(listOf("sort", "provider", "runtime"), response.ignored)
        assertEquals("/trending/movie/week", requests.single().path)
    }

    @Test
    fun `given hide rated then rated titles are left out`() {
        serve(totalPages = 1)
        val movie = library.movies.save(MovieFactory.aMovie(tmdbId = 603, originalTitle = "The Matrix"))
        library.ratings.save(RatingFactory.aQuickRating(movieId = movie.id!!, userId = user.id!!, score = 9.0))

        val response = controller.browse(user, BrowseParams(hideRated = "true"))

        assertEquals(listOf(604), response.results.map { it.tmdbId })
        assertEquals(true, response.filters.hideRated)
    }

    @Test
    fun `given TMDB failures then answers 503 or 502`() {
        whenever(client.page(any())).thenReturn(TmdbBrowseError.ApiKeyMissing.left())
        assertThrows<ApiException> { controller.browse(user, BrowseParams()) }.let {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, it.status)
            assertEquals("tmdb_not_configured", it.code)
        }

        whenever(client.page(any())).thenReturn(TmdbBrowseError.ApiKeyRejected.left())
        assertEquals("tmdb_not_configured", assertThrows<ApiException> { controller.browse(user, BrowseParams()) }.code)

        whenever(client.page(any())).thenReturn(TmdbBrowseError.Failed("IOException").left())
        assertThrows<ApiException> { controller.browse(user, BrowseParams()) }.let {
            assertEquals(HttpStatus.BAD_GATEWAY, it.status)
            assertEquals("tmdb_error", it.code)
        }
    }
}
