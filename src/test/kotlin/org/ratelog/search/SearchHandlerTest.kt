package org.ratelog.search

import arrow.core.getOrElse
import arrow.core.right
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.Title
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tmdb.TmdbMovieResponse
import org.ratelog.tmdb.TmdbTvShowResponse
import org.ratelog.user.User

class SearchHandlerTest {

    private val tmdbClient: TmdbClient = mock()
    private val handler = SearchHandler(tmdbClient)

    @Test
    fun `should return empty list when query is blank`() {
        val query = SearchQuery("", User.Id(1), Lang.en, null)

        val result = handler.handle(query)

        assertTrue(result.isRight())
        assertEquals(0, result.getOrElse { SearchResult(emptyList(), false) }.items.size)
        assertFalse(result.getOrElse { SearchResult(emptyList(), false) }.hasMore)
    }

    @Test
    fun `should return interleaved movie and tv show results`() {
        val movies = listOf(
            TmdbMovieResponse(1, "Movie 1", overview = "Overview 1", releaseDate = "2023-01-01", posterPath = "/poster1.jpg", voteAverage = 7.5, originalTitle = "a-title"),
            TmdbMovieResponse(2, "Movie 2", overview = "Overview 2", releaseDate = "2023-02-01", posterPath = "/poster2.jpg", voteAverage = 8.0, originalTitle = "a-title")
        )
        val shows = listOf(
            TmdbTvShowResponse(3, "Show 1", overview = "Overview 3", firstAirDate = "2023-03-01", posterPath = "/poster3.jpg", voteAverage = 8.5, originalName = "a-title"),
            TmdbTvShowResponse(4, "Show 2", overview = "Overview 4", firstAirDate = "2023-04-01", posterPath = "/poster4.jpg", voteAverage = 9.0, originalName = "a-title")
        )

        whenever(tmdbClient.searchMovies("test", Lang.en, 1)).thenReturn((movies to 1).right())
        whenever(tmdbClient.searchTvShows("test", Lang.en, 1)).thenReturn((shows to 1).right())

        val query = SearchQuery("test", User.Id(1), Lang.en, null)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        val results = result.getOrElse { SearchResult(emptyList(), false) }
        assertEquals(4, results.items.size)
        assertEquals("Movie 1", results.items[0].title)
        assertEquals("Show 1", results.items[1].title)
        assertEquals("Movie 2", results.items[2].title)
        assertEquals("Show 2", results.items[3].title)
        assertFalse(results.hasMore)
    }

    @Test
    fun `should return only movies when no tv shows found`() {
        val movies = listOf(
            TmdbMovieResponse(1, "Movie 1", overview = "Overview 1", releaseDate = "2023-01-01", posterPath = "/poster1.jpg", voteAverage = 7.5, originalTitle = "a-title")
        )

        whenever(tmdbClient.searchMovies("test", Lang.en, 1)).thenReturn((movies to 1).right())
        whenever(tmdbClient.searchTvShows("test", Lang.en, 1)).thenReturn((emptyList<TmdbTvShowResponse>() to 1).right())

        val query = SearchQuery("test", User.Id(1), Lang.en, null)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        val results = result.getOrElse { SearchResult(emptyList(), false) }
        assertEquals(1, results.items.size)
        assertEquals("Movie 1", results.items[0].title)
    }

    @Test
    fun `should return only tv shows when no movies found`() {
        val shows = listOf(
            TmdbTvShowResponse(1, "Show 1", overview = "Overview 1", firstAirDate = "2023-01-01", posterPath = "/poster1.jpg", voteAverage = 8.0, originalName = "a-title")
        )

        whenever(tmdbClient.searchMovies("test", Lang.en, 1)).thenReturn((emptyList<TmdbMovieResponse>() to 1).right())
        whenever(tmdbClient.searchTvShows("test", Lang.en, 1)).thenReturn((shows to 1).right())

        val query = SearchQuery("test", User.Id(1), Lang.en, null)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        val results = result.getOrElse { SearchResult(emptyList(), false) }
        assertEquals(1, results.items.size)
        assertEquals("Show 1", results.items[0].title)
    }

    @Test
    fun `should indicate hasMore when there are additional pages`() {
        val movies = listOf(
            TmdbMovieResponse(1, "Movie 1", overview = "Overview 1", releaseDate = "2023-01-01", posterPath = "/poster1.jpg", voteAverage = 7.5, originalTitle = "a-title")
        )
        val shows = listOf(
            TmdbTvShowResponse(2, "Show 1", overview = "Overview 2", firstAirDate = "2023-02-01", posterPath = "/poster2.jpg", voteAverage = 8.0, originalName = "a-title")
        )

        whenever(tmdbClient.searchMovies("test", Lang.en, 1)).thenReturn((movies to 3).right())
        whenever(tmdbClient.searchTvShows("test", Lang.en, 1)).thenReturn((shows to 2).right())

        val query = SearchQuery("test", User.Id(1), Lang.en, null)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        val results = result.getOrElse { SearchResult(emptyList(), false) }
        assertEquals(2, results.items.size)
        assertTrue(results.hasMore)
    }

    @Test
    fun `should filter by movie type only`() {
        val movies = listOf(
            TmdbMovieResponse(1, "Movie 1", overview = "Overview 1", releaseDate = "2023-01-01", posterPath = "/poster1.jpg", voteAverage = 7.5, originalTitle = "a-title"),
            TmdbMovieResponse(2, "Movie 2", overview = "Overview 2", releaseDate = "2023-02-01", posterPath = "/poster2.jpg", voteAverage = 8.0, originalTitle = "a-title")
        )

        whenever(tmdbClient.searchMovies("test", Lang.en, 1)).thenReturn((movies to 1).right())

        val query = SearchQuery("test", User.Id(1), Lang.en, MediaType.movie)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        val results = result.getOrElse { SearchResult(emptyList(), false) }
        assertEquals(2, results.items.size)
        assertEquals("Movie 1", results.items[0].title)
        assertEquals("Movie 2", results.items[1].title)
        assertFalse(results.hasMore)
    }

    @Test
    fun `should filter by tv show type only`() {
        val shows = listOf(
            TmdbTvShowResponse(1, "Show 1", overview = "Overview 1", firstAirDate = "2023-01-01", posterPath = "/poster1.jpg", voteAverage = 8.0, originalName = "a-title"),
            TmdbTvShowResponse(2, "Show 2", overview = "Overview 2", firstAirDate = "2023-02-01", posterPath = "/poster2.jpg", voteAverage = 9.0, originalName = "a-title")
        )

        whenever(tmdbClient.searchTvShows("test", Lang.en, 1)).thenReturn((shows to 2).right())

        val query = SearchQuery("test", User.Id(1), Lang.en, MediaType.tvshow)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        val results = result.getOrElse { SearchResult(emptyList(), false) }
        assertEquals(2, results.items.size)
        assertEquals("Show 1", results.items[0].title)
        assertEquals("Show 2", results.items[1].title)
        assertTrue(results.hasMore)
    }

    @Test
    fun `a results page is the whole TMDB page, so scrolling on never skips titles`() {
        val movies = (1..10).map { TmdbMovieResponse(it, "Movie $it", overview = "Overview", releaseDate = "2023-01-01", posterPath = "/poster.jpg", voteAverage = 7.5, originalTitle = "a-title") }
        val shows = (1..10).map { TmdbTvShowResponse(it, "Show $it", overview = "Overview", firstAirDate = "2023-01-01", posterPath = "/poster.jpg", voteAverage = 8.0, originalName = "a-title") }

        whenever(tmdbClient.searchMovies("test", Lang.en, 1)).thenReturn((movies to 2).right())
        whenever(tmdbClient.searchTvShows("test", Lang.en, 1)).thenReturn((shows to 1).right())

        val query = SearchQuery("test", User.Id(1), Lang.en, null)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        val results = result.getOrElse { SearchResult(emptyList(), false) }
        assertEquals(20, results.items.size, "every title of both TMDB pages, interleaved")
        assertEquals(listOf("Movie 1", "Show 1", "Movie 2"), results.items.take(3).map { it.title })
        assertTrue(results.hasMore, "movies have a page 2")

        whenever(tmdbClient.searchMovies("test", Lang.en, 1)).thenReturn((movies to 1).right())
        assertFalse(handler.handle(query).getOrElse { SearchResult(emptyList(), false) }.hasMore, "the last page says so")
    }

    @Test
    fun `a year narrows both searches with TMDB's year parameters`() {
        val dune1984 = TmdbMovieResponse(841, "Dune", overview = null, releaseDate = "1984-12-14", posterPath = null, voteAverage = 6.5, originalTitle = "Dune")
        whenever(tmdbClient.searchMovies("dune", Lang.en, 1, 1984)).thenReturn((listOf(dune1984) to 1).right())
        whenever(tmdbClient.searchTvShows("dune", Lang.en, 1, 1984)).thenReturn((emptyList<TmdbTvShowResponse>() to 1).right())

        val results = handler.handle(SearchQuery("dune", User.Id(1), Lang.en, null, year = 1984)).getOrElse { SearchResult(emptyList(), false) }

        assertEquals(listOf(841), results.items.map { it.tmdbId })
        org.mockito.kotlin.verify(tmdbClient, org.mockito.kotlin.never()).searchMovies("dune", Lang.en, 1)
    }

    @Test
    fun `a year with the Movies filter only searches movies of that year`() {
        val dune2021 = TmdbMovieResponse(438631, "Dune", overview = null, releaseDate = "2021-09-15", posterPath = null, voteAverage = 7.8, originalTitle = "Dune")
        whenever(tmdbClient.searchMovies("dune", Lang.en, 1, 2021)).thenReturn((listOf(dune2021) to 1).right())

        val results = handler.handle(SearchQuery("dune", User.Id(1), Lang.en, MediaType.movie, year = 2021)).getOrElse { SearchResult(emptyList(), false) }

        assertEquals(listOf(438631), results.items.map { it.tmdbId })
        org.mockito.kotlin.verify(tmdbClient, org.mockito.kotlin.never()).searchTvShows(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull())
    }
    // --- Fork (2026-11-24): a decade is filtered here, reading TMDB pages until a results page is filled ---

    private var nextId = 1000

    /** One TMDB page of 20 movies, [inDecade] of them from the 1980s (the rest from 2015). */
    private fun moviePage(inDecade: Int): List<TmdbMovieResponse> = (1..20).map { i ->
        val id = nextId++
        TmdbMovieResponse(id, "Movie $id", overview = null, releaseDate = if (i <= inDecade) "198${i % 10}-05-01" else "2015-05-01",
            posterPath = null, voteAverage = 7.0, originalTitle = "Movie $id")
    }

    private fun showPage(inDecade: Int): List<TmdbTvShowResponse> = (1..20).map { i ->
        val id = nextId++
        TmdbTvShowResponse(id, "Show $id", overview = null, firstAirDate = if (i <= inDecade) "1985-01-01" else "",
            posterPath = null, voteAverage = 7.0, originalName = "Show $id")
    }

    private val eighties = org.ratelog.browse.BrowseDecade.Of(1980)

    private fun decadeQuery(type: MediaType?, page: Int = 1) =
        SearchQuery("star", User.Id(1), Lang.en, type, page = page, decade = eighties)

    @Test
    fun `a decade reads TMDB pages until a results page is filled and keeps every match`() {
        whenever(tmdbClient.searchMovies("star", Lang.en, 1)).thenReturn((moviePage(4) to 10).right())
        whenever(tmdbClient.searchMovies("star", Lang.en, 2)).thenReturn((moviePage(6) to 10).right())

        val result = handler.handle(decadeQuery(MediaType.movie)).getOrElse { SearchResult(emptyList(), false) }

        assertEquals(10, result.items.size)
        assertTrue(result.items.all { it.year in 1980..1989 })
        assertEquals(2, result.lastPage)
        assertTrue(result.hasMore)
        org.mockito.kotlin.verify(tmdbClient, org.mockito.kotlin.never()).searchMovies("star", Lang.en, 3)
        org.mockito.kotlin.verify(tmdbClient, org.mockito.kotlin.never()).searchTvShows(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `a decade reads at most five TMDB pages per results page, and the next one starts after them`() {
        (1..10).forEach { page -> whenever(tmdbClient.searchMovies("star", Lang.en, page)).thenReturn((moviePage(1) to 30).right()) }

        val first = handler.handle(decadeQuery(MediaType.movie)).getOrElse { SearchResult(emptyList(), false) }
        assertEquals(5, first.items.size)
        assertEquals(SearchHandler.DECADE_PAGES_PER_LOAD, first.lastPage)
        assertTrue(first.hasMore)

        val second = handler.handle(decadeQuery(MediaType.movie, page = first.lastPage!! + 1)).getOrElse { SearchResult(emptyList(), false) }
        assertEquals(10, second.lastPage)
        assertTrue(second.items.none { it.tmdbId in first.items.map { f -> f.tmdbId } })
        org.mockito.kotlin.verify(tmdbClient, org.mockito.kotlin.times(1)).searchMovies("star", Lang.en, 5)
        org.mockito.kotlin.verify(tmdbClient, org.mockito.kotlin.never()).searchMovies("star", Lang.en, 11)
    }

    @Test
    fun `a decade stops at TMDB's last page`() {
        whenever(tmdbClient.searchMovies("star", Lang.en, 1)).thenReturn((moviePage(2) to 2).right())
        whenever(tmdbClient.searchMovies("star", Lang.en, 2)).thenReturn((moviePage(1) to 2).right())

        val result = handler.handle(decadeQuery(MediaType.movie)).getOrElse { SearchResult(emptyList(), false) }

        assertEquals(3, result.items.size)
        assertEquals(2, result.lastPage)
        assertFalse(result.hasMore)
    }

    @Test
    fun `a decade for any type interleaves movies and shows and follows each until its last page`() {
        whenever(tmdbClient.searchMovies("star", Lang.en, 1)).thenReturn((moviePage(2) to 1).right())
        whenever(tmdbClient.searchTvShows("star", Lang.en, 1)).thenReturn((showPage(2) to 3).right())
        whenever(tmdbClient.searchTvShows("star", Lang.en, 2)).thenReturn((showPage(2) to 3).right())
        whenever(tmdbClient.searchTvShows("star", Lang.en, 3)).thenReturn((showPage(1) to 3).right())

        val result = handler.handle(decadeQuery(null)).getOrElse { SearchResult(emptyList(), false) }

        assertEquals(listOf("movie", "tvshow", "movie", "tvshow"), result.items.take(4).map { it.type })
        assertEquals(7, result.items.size)
        assertEquals(3, result.lastPage)
        assertFalse(result.hasMore)
        // Shows without a first air date are in no decade.
        assertTrue(result.items.all { it.year != null })
        org.mockito.kotlin.verify(tmdbClient, org.mockito.kotlin.never()).searchMovies("star", Lang.en, 2)
    }

    @Test
    fun `a decade never reads past TMDB page 20`() {
        whenever(tmdbClient.searchMovies("star", Lang.en, 19)).thenReturn((moviePage(0) to 80).right())
        whenever(tmdbClient.searchMovies("star", Lang.en, 20)).thenReturn((moviePage(1) to 80).right())

        val result = handler.handle(decadeQuery(MediaType.movie, page = 19)).getOrElse { SearchResult(emptyList(), false) }
        assertEquals(1, result.items.size)
        assertEquals(SearchHandler.DECADE_MAX_PAGE, result.lastPage)
        assertFalse(result.hasMore)

        val beyond = handler.handle(decadeQuery(MediaType.movie, page = 21)).getOrElse { SearchResult(emptyList(), true) }
        assertTrue(beyond.items.isEmpty())
        assertFalse(beyond.hasMore)
        org.mockito.kotlin.verify(tmdbClient, org.mockito.kotlin.never()).searchMovies("star", Lang.en, 21)
    }

    @Test
    fun `an exact year next to a decade uses TMDB's year filter, one page as before`() {
        whenever(tmdbClient.searchMovies("star", Lang.en, 1, 1984)).thenReturn((moviePage(3) to 1).right())

        val result = handler.handle(decadeQuery(MediaType.movie).copy(year = 1984)).getOrElse { SearchResult(emptyList(), false) }

        assertEquals(20, result.items.size, "the whole TMDB page")
        assertNull(result.lastPage)
        org.mockito.kotlin.verify(tmdbClient, org.mockito.kotlin.never()).searchMovies("star", Lang.en, 1)
    }
}
