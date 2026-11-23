package org.ratelog.customlist.suggest.add

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.api.media.MediaImporter
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.customlist.membership.SetListMembershipHandler
import org.ratelog.customlist.removeitem.RemoveFromListHandler
import org.ratelog.movie.MovieDescription
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryListMembershipRepository
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.test.UserFactory
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tmdb.TmdbError
import org.ratelog.tvshow.TvDescription
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import java.util.Locale

/** "+ Add" on a suggestion: the handler (real list, membership and import code on in-memory fakes) and its controller. */
class AddSuggestionTest {

    private val tmdb: TmdbClient = mock()
    private val lists = InMemoryCustomListRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val handler = AddSuggestionHandler(
        lists,
        MediaImporter(tmdb, movies, InMemoryMovieDescriptionRepository(), shows, InMemoryTvDescriptionRepository()),
        SetListMembershipHandler(lists, AddToListHandler(lists), RemoveFromListHandler(lists)),
        InMemoryListMembershipRepository(lists),
    )
    private val controller = AddSuggestionController(handler, BrowseBarViewsTest.testMessageSource())

    private val user = UserFactory.aUser(id = 1)
    private val other = UserFactory.aUser(id = 2)
    private val toyStory = movies.save(MovieFactory.aMovie(tmdbId = 862, originalTitle = "Toy Story")).id!!.value
    private val nemo = movies.save(MovieFactory.aMovie(tmdbId = 12, originalTitle = "Finding Nemo")).id!!.value
    private val up = movies.save(MovieFactory.aMovie(tmdbId = 14160, originalTitle = "Up")).id!!.value

    private lateinit var pixar: CustomList

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    @BeforeEach
    fun setUp() {
        whenever(tmdb.movieTranslations(any(), any())).thenReturn(emptyList<MovieDescription>().right())
        whenever(tmdb.tvTranslations(any(), any())).thenReturn(emptyList<TvDescription>().right())
        pixar = list(user.id!!, "Pixar", toyStory, nemo)
    }

    private fun list(owner: org.ratelog.user.User.Id, name: String, vararg mediaIds: Long): CustomList {
        val list = lists.save(CustomList(null, owner, ListName(name), isPublic = true, createdAtEpochMs = 1))
        mediaIds.forEachIndexed { i, id -> lists.addItem(CustomListItem(null, list.id!!, id, MediaType.movie, i + 1, 1)) }
        return list
    }

    private fun add(tmdbId: Int, type: MediaType = MediaType.movie, list: CustomList = pixar, by: org.ratelog.user.User = user) =
        handler.handle(AddSuggestion(list.id!!, by.id!!, type, tmdbId, Lang.en))

    private fun items(list: CustomList = pixar) = lists.findById(list.id!!)!!.items.sortedBy { it.position }

    // --- Handler ---

    @Test
    fun `a title Ratelog already has goes to the end of the list, with its place`() {
        val result = add(14160).getOrNull()!!

        assertTrue(result.added)
        assertEquals(3, result.rank)
        assertEquals("Up", result.title)
        assertEquals("Pixar", result.listName)
        assertEquals(listOf(toyStory, nemo, up), items().map { it.mediaId })
        assertEquals(items().last().id!!.value, result.itemId)
        verify(tmdb, never()).movieDetails(any())
    }

    @Test
    fun `a title Ratelog hasn't seen yet is saved from TMDB first`() {
        whenever(tmdb.movieDetails(TmdbId(2062))).thenReturn(MovieFactory.aMovie(tmdbId = 2062, originalTitle = "Ratatouille").right())

        val result = add(2062).getOrNull()!!

        assertEquals("Ratatouille", result.title)
        val saved = movies.findByTmdbId(TmdbId(2062))
        assertNotNull(saved)
        assertEquals(saved!!.id!!.value, items().last().mediaId)
    }

    @Test
    fun `shows can be added too`() {
        whenever(tmdb.tvShowDetails(TmdbId(1396))).thenReturn(TvShowFactory.aTvShow(tmdbId = 1396, originalName = "Breaking Bad").right())

        val result = add(1396, MediaType.tvshow).getOrNull()!!

        assertEquals("Breaking Bad", result.title)
        assertEquals(MediaType.tvshow, items().last().mediaType)
    }

    @Test
    fun `a title already in the list stays where it is`() {
        val result = add(862).getOrNull()!!

        assertFalse(result.added)
        assertEquals(1, result.rank)
        assertEquals(2, items().size)
    }

    @Test
    fun `only the owner can add, and nobody else makes the server call TMDB`() {
        assertEquals(AddSuggestionError.NotOwner.left(), add(2062, by = other))
        assertEquals(AddSuggestionError.ListNotFound.left(), handler.handle(AddSuggestion(CustomList.Id(999), user.id!!, MediaType.movie, 2062, Lang.en)))
        verify(tmdb, never()).movieDetails(any())
        assertEquals(2, items().size)
    }

    @Test
    fun `TMDB problems are reported without adding anything`() {
        whenever(tmdb.movieDetails(TmdbId(1))).thenReturn(TmdbError.MovieNotFound.left())
        whenever(tmdb.movieDetails(TmdbId(2))).thenReturn(TmdbError.ApiKeyMissing.left())
        whenever(tmdb.movieDetails(TmdbId(3))).thenThrow(IllegalStateException("https://api.themoviedb.org/3/movie/3?api_key=secret"))

        assertEquals(AddSuggestionError.TitleNotFound.left(), add(1))
        assertEquals(AddSuggestionError.TmdbNotConfigured.left(), add(2))
        assertEquals(AddSuggestionError.TmdbUnavailable.left(), add(3))
        assertEquals(2, items().size)
    }

    // --- Controller: plain form posts (no JS) ---

    private fun request(referer: String? = null) = MockHttpServletRequest("POST", "/list/1/suggestions/add").apply {
        referer?.let { addHeader("Referer", it) }
    }

    @Test
    fun `a plain post adds the title and goes back to the list`() {
        val flash = RedirectAttributesModelMap()

        val view = controller.add(pixar.id!!.value, user, 14160, "movie", back = "/list/${pixar.id!!.value}", request(), flash)

        assertEquals("redirect:/list/${pixar.id!!.value}", view)
        assertEquals(AddSuggestionController.ADDED_PLAIN, flash.flashAttributes["success"])
        assertEquals(3, items().size)
    }

    @Test
    fun `a plain post never redirects off the site and reports problems`() {
        val flash = RedirectAttributesModelMap()
        val view = controller.add(pixar.id!!.value, other, 14160, "movie", back = "https://evil.example/x", request(), flash)

        // BackPath keeps only the path of a full URL, so the redirect stays on this site.
        assertEquals("redirect:/x", view)
        assertEquals("list.error.not.owner", flash.flashAttributes["error"])

        val again = RedirectAttributesModelMap()
        controller.add(pixar.id!!.value, user, 862, "movie", back = null, request(referer = "http://localhost/list/${pixar.id!!.value}?show=unseen"), again)
        assertEquals("list.error.item.already.in.list", again.flashAttributes["error"])
    }

    // --- Controller: in place (fetch, JSON) ---

    @Test
    fun `fetch gets the item, its place and the toast`() {
        val response = controller.addJson(pixar.id!!.value, user, 14160, "movie")

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertTrue(body.ok)
        assertTrue(body.added)
        assertEquals(3, body.rank)
        assertEquals(items().last().id!!.value, body.itemId)
        assertEquals("Added \"Up\" to Pixar as #3.", body.message)
    }

    @Test
    fun `fetch says so when the title is already there`() {
        val body = controller.addJson(pixar.id!!.value, user, 862, "movie").body!!

        assertTrue(body.ok)
        assertFalse(body.added)
        assertEquals("\"Toy Story\" is already in Pixar.", body.message)
    }

    @Test
    fun `fetch errors come back as JSON with a status and a readable message`() {
        val notYours = controller.addJson(pixar.id!!.value, other, 14160, "movie")
        assertEquals(HttpStatus.FORBIDDEN, notYours.statusCode)
        assertFalse(notYours.body!!.ok)
        assertEquals("You are not the owner of this list.", notYours.body!!.message)

        val badType = controller.addJson(pixar.id!!.value, user, 14160, "book")
        assertEquals(HttpStatus.BAD_REQUEST, badType.statusCode)
        assertEquals("That title can't be added.", badType.body!!.message)
        assertEquals(HttpStatus.BAD_REQUEST, controller.addJson(pixar.id!!.value, user, 0, "movie").statusCode)

        whenever(tmdb.movieDetails(TmdbId(9))).thenThrow(IllegalStateException("boom"))
        val down = controller.addJson(pixar.id!!.value, user, 9, "movie")
        assertEquals(HttpStatus.BAD_GATEWAY, down.statusCode)
        assertEquals("TMDB isn't answering right now. Try again in a moment.", down.body!!.message)
        assertNull(down.body!!.itemId)

        assertEquals(HttpStatus.NOT_FOUND, controller.addJson(999, user, 14160, "movie").statusCode)
        assertEquals(2, items().size)
    }

    @Test
    fun `tv is accepted as the show type`() {
        whenever(tmdb.tvShowDetails(TmdbId(1398))).thenReturn(TvShowFactory.aTvShow(tmdbId = 1398, originalName = "The Sopranos").right())
        val body = controller.addJson(pixar.id!!.value, user, 1398, "tv").body!!
        assertTrue(body.added)
        assertEquals(MediaType.tvshow, items().last().mediaType)
    }
}
