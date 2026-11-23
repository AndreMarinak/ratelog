package org.ratelog.api.lists

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.api.ApiException
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.suggest.GetListSuggestionsHandler
import org.ratelog.recommendations.RecommendationsPage
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TmdbRecommendationsClient
import org.ratelog.recommendations.TmdbRecommendationsError
import org.ratelog.test.InMemoryBrowseLibraryRepository
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryListTitlesRepository
import org.ratelog.test.InMemoryOfficialListRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ApiListSuggestionsControllerTest {

    private val lists = InMemoryCustomListRepository()
    private val official = InMemoryOfficialListRepository(lists)
    private val client: TmdbRecommendationsClient = mock()
    private val library = InMemoryBrowseLibraryRepository()
    private val clock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC)
    private val controller = ApiListSuggestionsController(
        GetListSuggestionsHandler(lists, InMemoryListTitlesRepository(lists), client, library, parallel = 1, clock = clock),
        official,
        clock,
    )
    private val user = UserFactory.aUser(id = 1)
    private val other = UserFactory.aUser(id = 2)

    private fun list(owner: org.ratelog.user.User, vararg tmdbIds: Int): CustomList {
        val list = lists.save(CustomList(null, owner.id!!, ListName("Pixar"), isPublic = true, createdAtEpochMs = 1))
        tmdbIds.forEachIndexed { i, id -> lists.addItem(CustomListItem(null, list.id!!, id.toLong(), MediaType.movie, i + 1, 1)) }
        return list
    }

    private fun title(id: Int, name: String, date: String) = RelatedTitle(id, MediaType.movie, name, LocalDate.parse(date), "/p$id.jpg", 7.6, 9_000)

    private fun serve() {
        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(
            RecommendationsPage(
                null,
                listOf(title(863, "Toy Story 2", "1999-10-30"), title(585, "Monsters, Inc.", "2001-11-01"), title(1084244, "Toy Story 5", "2027-06-18")),
                1, 1,
            ).right(),
        )
    }

    @Test
    fun `your list's suggestions, with your marks, best first`() {
        serve()
        val movie = library.movies.save(MovieFactory.aMovie(tmdbId = 585, originalTitle = "Monsters, Inc."))
        library.statuses.save(MediaStatus(user.id!!, MediaType.movie, movie.id!!.value, WatchStatus.WATCHED, 0))
        val pixar = list(user, 862)

        val response = controller.suggestions(user, pixar.id!!.value, limit = null, skip = null)

        assertEquals(pixar.id!!.value, response.listId)
        assertEquals(1, response.listSize)
        assertEquals(1, response.basedOn)
        assertEquals(0, response.remaining)
        assertEquals(listOf(863, 585, 1084244), response.suggestions.map { it.tmdbId })
        val first = response.suggestions.first()
        assertEquals("movie", first.type)
        assertEquals("/movie/863", first.url)
        assertEquals(1999, first.year)
        assertEquals("https://image.tmdb.org/t/p/w342/p863.jpg", first.posterUrl)
        assertEquals("recommended", first.reason)
        assertEquals(1, first.recommendedBy)
        val monsters = response.suggestions[1]
        assertTrue(monsters.seen)
        assertEquals("watched", monsters.status)
        assertTrue(response.suggestions.last().upcoming)
        assertFalse(first.upcoming)
    }

    @Test
    fun `limit and skip page through the suggestions`() {
        serve()
        val pixar = list(user, 862)

        val first = controller.suggestions(user, pixar.id!!.value, limit = 1, skip = null)
        assertEquals(listOf(863), first.suggestions.map { it.tmdbId })
        assertEquals(2, first.remaining)

        val next = controller.suggestions(user, pixar.id!!.value, limit = 5, skip = "movie:863,movie:585")
        assertEquals(listOf(1084244), next.suggestions.map { it.tmdbId })

        listOf(0, 51).forEach { bad ->
            val e = assertThrows<ApiException> { controller.suggestions(user, pixar.id!!.value, limit = bad, skip = null) }
            assertEquals(HttpStatus.BAD_REQUEST, e.status)
            assertEquals("invalid_parameter", e.code)
        }
    }

    @Test
    fun `someone else's list is 404, or 403 when it is official, and missing lists are 404`() {
        serve()
        val theirs = list(other, 862)
        val e = assertThrows<ApiException> { controller.suggestions(user, theirs.id!!.value, null, null) }
        assertEquals(HttpStatus.NOT_FOUND, e.status)

        official.mark(theirs.id!!, 1)
        val forbidden = assertThrows<ApiException> { controller.suggestions(user, theirs.id!!.value, null, null) }
        assertEquals(HttpStatus.FORBIDDEN, forbidden.status)
        assertEquals("forbidden", forbidden.code)

        assertEquals(HttpStatus.NOT_FOUND, assertThrows<ApiException> { controller.suggestions(user, 999, null, null) }.status)
    }

    @Test
    fun `TMDB errors map to the API's codes`() {
        val pixar = list(user, 862)
        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(TmdbRecommendationsError.Unavailable.left())
        val down = assertThrows<ApiException> { controller.suggestions(user, pixar.id!!.value, null, null) }
        assertEquals(HttpStatus.BAD_GATEWAY, down.status)
        assertEquals("tmdb_error", down.code)

        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(TmdbRecommendationsError.ApiKeyMissing.left())
        val noKey = assertThrows<ApiException> { controller.suggestions(user, pixar.id!!.value, null, null) }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, noKey.status)
        assertEquals("tmdb_not_configured", noKey.code)
    }

    @Test
    fun `an empty list has no suggestions`() {
        val response = controller.suggestions(user, list(user).id!!.value, null, null)
        assertTrue(response.suggestions.isEmpty())
        assertEquals(0, response.listSize)
    }
}
