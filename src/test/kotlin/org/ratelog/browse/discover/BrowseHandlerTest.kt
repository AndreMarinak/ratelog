package org.ratelog.browse.discover

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Genre
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowsePopular
import org.ratelog.browse.BrowseTitle
import org.ratelog.browse.BrowseTitlePage
import org.ratelog.browse.LibraryMark
import org.ratelog.browse.TmdbBrowseClient
import org.ratelog.browse.TmdbBrowseError
import org.ratelog.browse.TmdbListRequest
import org.ratelog.test.InMemoryBrowseLibraryRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate

class BrowseHandlerTest {

    private val client: TmdbBrowseClient = mock()
    private val library = InMemoryBrowseLibraryRepository()
    private val handler = BrowseHandler(client, library, MutableClock())
    private val user = UserFactory.aUser(id = 7)
    private val requests = mutableListOf<TmdbListRequest>()

    private fun title(id: Int, type: MediaType = MediaType.movie, genres: List<Int> = emptyList()) = BrowseTitle(
        tmdbId = id, type = type, title = "Title $id", releaseDate = LocalDate.parse("2020-05-01"), posterPath = "/$id.jpg",
        voteAverage = 7.0, voteCount = 500, genreIds = genres, originalLanguage = "en",
    )

    /** TMDB pages by number; ids [start, start + size). */
    private fun serve(totalPages: Int, perPage: Int = 20, failOn: Set<Int> = emptySet(), items: (Int) -> List<BrowseTitle> = { p ->
        ((p - 1) * perPage + 1..p * perPage).map { title(it) }
    }) {
        whenever(client.page(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<TmdbListRequest>(0)
            requests += request
            val page = request.params.getValue("page").toInt()
            if (page in failOn) TmdbBrowseError.Failed("boom").left()
            else BrowseTitlePage(items(page), page, totalPages, totalPages * perPage).right()
        }
    }

    private fun query(filters: BrowseFilters = BrowseFilters(), page: Int = 1) =
        BrowseQuery(user.id!!, Lang.en, WatchRegion("US"), filters, page)

    private fun Either<BrowseError, BrowseResult>.ok() = getOrNull()!!

    private fun rateMovie(tmdbId: Int, score: Double) {
        val movie = library.movies.save(MovieFactory.aMovie(tmdbId = tmdbId, originalTitle = "Movie $tmdbId"))
        library.ratings.save(RatingFactory.aQuickRating(movieId = movie.id!!, userId = user.id!!, score = score))
    }

    private fun setStatus(tmdbId: Int, status: WatchStatus) {
        val movie = library.movies.findByTmdbId(org.ratelog.TmdbId(tmdbId))
            ?: library.movies.save(MovieFactory.aMovie(tmdbId = tmdbId, originalTitle = "Movie $tmdbId"))
        library.statuses.save(MediaStatus(user.id!!, MediaType.movie, movie.id!!.value, status, 1L))
    }

    @Test
    fun `given a page then attaches the user's scores and statuses with one lookup per TMDB page`() {
        // Given
        serve(totalPages = 5)
        rateMovie(2, 8.5)
        setStatus(3, WatchStatus.WANT_TO_WATCH)
        setStatus(2, WatchStatus.WATCHED)
        // Someone else's rating doesn't count.
        val other = library.movies.save(MovieFactory.aMovie(tmdbId = 4, originalTitle = "Other"))
        library.ratings.save(RatingFactory.aQuickRating(movieId = other.id!!, userId = UserFactory.aUser(id = 99).id!!, score = 3.0))

        // When
        val result = handler.handle(query()).ok()

        // Then
        assertEquals(20, result.tiles.size)
        assertEquals(LibraryMark(score = 8.5, rated = true, status = WatchStatus.WATCHED), result.tiles[1].mark)
        assertEquals(LibraryMark(score = null, rated = false, status = WatchStatus.WANT_TO_WATCH), result.tiles[2].mark)
        assertNull(result.tiles[0].mark)
        assertNull(result.tiles[3].mark)
        assertEquals(1, library.calls.size)
        assertEquals(20, library.calls.single().size)
        assertEquals(1, requests.size)
        assertEquals(2, result.nextPage)
        assertEquals(5, result.totalPages)
    }

    @Test
    fun `given hide rated then drops rated titles and keeps watchlist ones`() {
        serve(totalPages = 5, perPage = 20)
        (1..5).forEach { rateMovie(it, 7.0) }
        setStatus(6, WatchStatus.WANT_TO_WATCH)

        val result = handler.handle(query(BrowseFilters(hideRated = true))).ok()

        assertEquals((6..20).toList(), result.tiles.map { it.title.tmdbId })
        assertEquals(1, requests.size, "15 visible titles are enough, no top-up")
    }

    @Test
    fun `given hide watchlist then drops only Want to watch`() {
        serve(totalPages = 1)
        setStatus(1, WatchStatus.WANT_TO_WATCH)
        setStatus(2, WatchStatus.WATCHING)
        rateMovie(3, 9.0)

        val ids = handler.handle(query(BrowseFilters(hideWatchlist = true))).ok().tiles.map { it.title.tmdbId }

        assertTrue(1 !in ids)
        assertTrue(2 in ids)
        assertTrue(3 in ids)
        assertNull(handler.handle(query(BrowseFilters(hideWatchlist = true))).ok().nextPage)
    }

    @Test
    fun `given most of a page is hidden then tops up from the next TMDB page and skips ahead`() {
        // Given: 18 of the 20 titles on page 1 are rated.
        serve(totalPages = 10)
        (1..18).forEach { rateMovie(it, 6.0) }

        // When
        val result = handler.handle(query(BrowseFilters(hideRated = true))).ok()

        // Then
        assertEquals(listOf(19, 20) + (21..40).toList(), result.tiles.map { it.title.tmdbId })
        assertEquals(listOf("1", "2"), requests.map { it.params["page"] })
        assertEquals(1, result.page)
        assertEquals(3, result.nextPage)
        assertEquals(2, library.calls.size)
    }

    @Test
    fun `given everything is hidden then stops after three TMDB pages`() {
        serve(totalPages = 50, perPage = 2)
        (1..10).forEach { rateMovie(it, 5.0) }

        val result = handler.handle(query(BrowseFilters(hideRated = true), page = 2)).ok()

        assertEquals(listOf("2", "3", "4"), requests.map { it.params["page"] })
        assertTrue(result.tiles.isEmpty())
        assertEquals(2, result.page)
        assertEquals(5, result.nextPage)
    }

    @Test
    fun `given nothing is hidden then a short page is not topped up`() {
        serve(totalPages = 9, perPage = 3)

        val result = handler.handle(query()).ok()

        assertEquals(3, result.tiles.size)
        assertEquals(1, requests.size)
        assertEquals(2, result.nextPage)
    }

    @Test
    fun `given the last page then there is no next page`() {
        serve(totalPages = 3)

        val result = handler.handle(query(page = 3)).ok()

        assertNull(result.nextPage)
        assertEquals(listOf("3"), requests.map { it.params["page"] })
    }

    @Test
    fun `given TMDB fails on the first page then maps the error`() {
        whenever(client.page(any())).thenReturn(TmdbBrowseError.ApiKeyMissing.left())
        assertEquals(BrowseError.TmdbNotConfigured, handler.handle(query()).leftOrNull())

        whenever(client.page(any())).thenReturn(TmdbBrowseError.ApiKeyRejected.left())
        assertEquals(BrowseError.TmdbKeyRejected, handler.handle(query()).leftOrNull())

        whenever(client.page(any())).thenReturn(TmdbBrowseError.Failed("IOException").left())
        assertEquals(BrowseError.TmdbUnavailable, handler.handle(query()).leftOrNull())
    }

    @Test
    fun `given a top-up page fails then keeps what it has and resumes from the failed page`() {
        serve(totalPages = 10, failOn = setOf(2))
        (1..15).forEach { rateMovie(it, 6.0) }

        val result = handler.handle(query(BrowseFilters(hideRated = true))).ok()

        assertEquals((16..20).toList(), result.tiles.map { it.title.tmdbId })
        assertEquals(2, result.nextPage)
    }

    @Test
    fun `given a title repeats on the next page then it is shown once`() {
        serve(totalPages = 4, perPage = 4) { p -> if (p == 1) listOf(title(1), title(2), title(3), title(4)) else listOf(title(4), title(5), title(6), title(7)) }
        rateMovie(1, 5.0)
        rateMovie(2, 5.0)

        val ids = handler.handle(query(BrowseFilters(hideRated = true))).ok().tiles.map { it.title.tmdbId }

        assertEquals(listOf(3, 4, 5, 6, 7), ids)
    }

    @Test
    fun `given this week with a genre then filters trending locally and reports ignored filters`() {
        serve(totalPages = 2, perPage = 4) { p ->
            listOf(title(p * 10 + 1, genres = listOf(27)), title(p * 10 + 2, genres = listOf(35)), title(p * 10 + 3, genres = listOf(27, 53)), title(p * 10 + 4))
        }

        val result = handler.handle(query(BrowseFilters(popular = BrowsePopular.WEEK, genre = Genre.HORROR, provider = 8))).ok()

        assertEquals(listOf(11, 13, 21, 23), result.tiles.map { it.title.tmdbId })
        assertTrue(requests.all { it.path == "/trending/movie/week" })
        assertEquals(listOf("provider"), result.ignored)
        assertNull(result.nextPage)
    }

    @Test
    fun `given TV then marks come from season ratings and TV statuses`() {
        serve(totalPages = 1) { listOf(title(1399, MediaType.tvshow), title(1400, MediaType.tvshow)) }
        val show = library.shows.save(TvShowFactory.aTvShow(tmdbId = 1399, originalName = "Game of Thrones"))
        library.tvRatings.save(
            TvRatingFactory.aTvRating(
                tvShowId = show.id!!, userId = user.id!!,
                seasonRatings = listOf(TvRatingFactory.aQuickSeasonRating(tvShowId = show.id!!, seasonNumber = 1, userId = user.id!!, score = 9.0)),
            )
        )
        library.statuses.save(MediaStatus(user.id!!, MediaType.tvshow, show.id!!.value, WatchStatus.WATCHING, 1L))

        val result = handler.handle(query(BrowseFilters(type = MediaType.tvshow, hideRated = true))).ok()

        assertTrue(result.tiles.none { it.title.tmdbId == 1399 })
        val all = handler.handle(query(BrowseFilters(type = MediaType.tvshow))).ok()
        assertEquals(LibraryMark(9.0, true, WatchStatus.WATCHING), all.tiles.first { it.title.tmdbId == 1399 }.mark)
        assertTrue(requests.all { it.path == "/discover/tv" })
    }
}
