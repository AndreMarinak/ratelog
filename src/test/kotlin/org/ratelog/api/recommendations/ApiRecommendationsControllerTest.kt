package org.ratelog.api.recommendations

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.api.ApiException
import org.ratelog.recommendations.CollectionRef
import org.ratelog.recommendations.RecommendationsPage
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TitleCollection
import org.ratelog.recommendations.TmdbRecommendationsClient
import org.ratelog.recommendations.TmdbRecommendationsError
import org.ratelog.recommendations.related.GetRelatedTitlesHandler
import org.ratelog.test.InMemoryBrowseLibraryRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.RatingFactory
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ApiRecommendationsControllerTest {

    private val client: TmdbRecommendationsClient = mock()
    private val library = InMemoryBrowseLibraryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC)
    private val controller = ApiRecommendationsController(GetRelatedTitlesHandler(client, library, clock), clock)
    private val user = UserFactory.aUser(id = 1)

    private fun title(id: Int, name: String, date: String?) =
        RelatedTitle(id, MediaType.movie, name, date?.let(LocalDate::parse), "/p$id.jpg", 7.4, 900)

    private fun serveToyStory() {
        whenever(client.recommendations(eq(MediaType.movie), eq(863), any(), eq(1))).thenReturn(
            RecommendationsPage(
                CollectionRef(10194, "Toy Story Collection"),
                listOf(title(10193, "Toy Story 3", "2010-06-16"), title(585, "Monsters, Inc.", "2001-11-01"), title(2062, "Ratatouille", "2007-06-28")),
                page = 1,
                totalPages = 1,
            ).right()
        )
        whenever(client.collection(eq(10194), any())).thenReturn(
            TitleCollection(
                10194, "Toy Story Collection",
                listOf(title(863, "Toy Story 2", "1999-10-30"), title(862, "Toy Story", "1995-11-22"), title(10193, "Toy Story 3", "2010-06-16"), title(1300000, "Toy Story 6", "2027-06-18")),
            ).right()
        )
    }

    /** Rates TMDB [tmdbId] (movie) for the user. */
    private fun rate(tmdbId: Int, score: Double = 8.0) {
        val movie = library.movies.save(MovieFactory.aMovie(tmdbId = tmdbId, originalTitle = "Movie $tmdbId"))
        library.ratings.save(RatingFactory.aRating(movieId = movie.id!!, userId = user.id!!, directing = score, cinematography = score, acting = score, soundtrack = score, screenplay = score))
    }

    @Test
    fun `returns the series in release order with progress and the recommendations with your marks`() {
        serveToyStory()
        rate(862)
        val ratatouille = library.movies.save(MovieFactory.aMovie(tmdbId = 2062, originalTitle = "Ratatouille"))
        library.statuses.save(MediaStatus(user.id!!, MediaType.movie, ratatouille.id!!.value, WatchStatus.WANT_TO_WATCH, 0))

        val response = controller.recommendations(user, "movie", 863, hideSeen = null)

        assertEquals(863, response.tmdbId)
        assertEquals("movie", response.type)
        val series = response.series!!
        assertEquals(10194, series.collectionId)
        assertEquals("Toy Story Collection", series.name)
        assertEquals(listOf(862, 863, 10193, 1300000), series.titles.map { it.tmdbId })
        assertEquals(listOf(false, true, false, false), series.titles.map { it.current })
        assertEquals(1, series.seen)
        assertEquals(3, series.released)
        assertTrue(series.titles[0].seen)
        assertEquals(8.0, series.titles[0].userScore)
        assertTrue(series.titles[3].upcoming)
        assertEquals("2027-06-18", series.titles[3].releaseDate)
        assertEquals("/movie/862", series.titles[0].url)
        assertEquals("https://image.tmdb.org/t/p/w342/p862.jpg", series.titles[0].posterUrl)

        assertEquals(listOf(585, 2062), response.moreLikeThis.map { it.tmdbId }, "series entries are left out")
        assertEquals("want_to_watch", response.moreLikeThis[1].status)
        assertNull(response.moreLikeThis[0].current)
        assertEquals(7.4, response.moreLikeThis[0].tmdbVoteAverage)
    }

    @Test
    fun `seen recommendations are only hidden with hideSeen=true`() {
        serveToyStory()
        rate(585)

        val all = controller.recommendations(user, "film", 863, hideSeen = null).moreLikeThis
        assertEquals(listOf(585, 2062), all.map { it.tmdbId })
        assertTrue(all[0].seen)
        assertFalse(all[1].seen)

        assertEquals(listOf(2062), controller.recommendations(user, "movie", 863, hideSeen = "true").moreLikeThis.map { it.tmdbId })
        assertEquals(2, controller.recommendations(user, "movie", 863, hideSeen = "0").moreLikeThis.size)
    }

    @Test
    fun `shows have no series`() {
        whenever(client.recommendations(eq(MediaType.tvshow), eq(1396), any(), eq(1))).thenReturn(
            RecommendationsPage(null, listOf(RelatedTitle(60059, MediaType.tvshow, "Better Call Saul", LocalDate.parse("2015-02-08"), "/b.jpg", 8.7, 7000)), 1, 1).right()
        )

        val response = controller.recommendations(user, "tv", 1396, hideSeen = null)

        assertEquals("tv", response.type)
        assertNull(response.series)
        assertEquals("tv", response.moreLikeThis.single().type)
        assertEquals("/tv/60059", response.moreLikeThis.single().url)
    }

    @Test
    fun `bad input is a 400, TMDB trouble a 502 or 503, an unknown title a 404`() {
        assertEquals("invalid_type", assertThrows<ApiException> { controller.recommendations(user, "book", 1, null) }.code)
        assertEquals("invalid_parameter", assertThrows<ApiException> { controller.recommendations(user, "movie", 0, null) }.code)
        whenever(client.recommendations(any(), eq(5), any(), any())).thenReturn(RecommendationsPage(null, emptyList(), 1, 1).right())
        assertEquals("invalid_parameter", assertThrows<ApiException> { controller.recommendations(user, "movie", 5, "maybe") }.code)

        whenever(client.recommendations(any(), eq(1), any(), any())).thenReturn(TmdbRecommendationsError.Unavailable.left())
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows<ApiException> { controller.recommendations(user, "movie", 1, null) }.status)
        whenever(client.recommendations(any(), eq(2), any(), any())).thenReturn(TmdbRecommendationsError.ApiKeyMissing.left())
        val notConfigured = assertThrows<ApiException> { controller.recommendations(user, "movie", 2, null) }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, notConfigured.status)
        assertEquals("tmdb_not_configured", notConfigured.code)
        whenever(client.recommendations(any(), eq(3), any(), any())).thenReturn(TmdbRecommendationsError.NotFound.left())
        assertEquals(HttpStatus.NOT_FOUND, assertThrows<ApiException> { controller.recommendations(user, "tv", 3, null) }.status)
    }

    @Test
    fun `unknown local titles simply have no marks`() {
        serveToyStory()
        val response = controller.recommendations(user, "movie", 863, null)
        assertTrue(response.series!!.titles.none { it.seen || it.status != null || it.userScore != null })
        assertEquals(0, response.series!!.seen)
        assertTrue(library.movies.findByTmdbId(TmdbId(863)) == null, "the API never saves titles just to show them")
    }
}
