package org.ratelog.watchlist.seen

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.media.MediaImporter
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.quickadd.QuickWatchlistController
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import java.time.LocalDate
import java.util.Locale

class ToggleSeenControllerTest {

    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val ratings = InMemoryRatingRepository()
    private val reader = SeenStateReader(statuses, ratings, InMemoryTvRatingRepository())
    private val importer: MediaImporter = mock()
    private val messages = BrowseBarViewsTest.testMessageSource()
    private val controller = ToggleSeenController(importer, ToggleSeenHandler(statuses, movies, shows, reader, MutableClock()), messages)
    private val quickJson = QuickWatchlistJsonController(
        importer,
        QuickWatchlistController(importer, statuses, SetWatchStatusHandler(statuses, movies, shows)),
        reader,
        messages,
    )

    private val user = UserFactory.aUser(id = 1)
    private val heat = movies.save(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseDate = LocalDate.parse("1995-12-15")))
    private val doomsday = movies.save(MovieFactory.aMovie(tmdbId = 1003596, originalTitle = "Avengers: Doomsday", releaseDate = LocalDate.parse("2026-12-18")))

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        listOf(heat, doomsday).forEach { m ->
            whenever(importer.ensure(eq(m.tmdbId.value), eq(MediaType.movie), any()))
                .thenReturn(Result.success(LocalMedia(m.id!!.value, m.tmdbId.value, MediaType.movie, m.originalTitle.value, 2000, null)))
        }
        whenever(importer.ensure(eq(404), any(), any())).thenReturn(Result.failure(IllegalStateException("not on TMDB")))
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun request(referer: String? = null) = MockHttpServletRequest().apply { referer?.let { addHeader("Referer", it) } }
    private fun status(id: Long = heat.id!!.value) = statuses.find(user.id!!, MediaType.movie, id)?.status

    @Test
    fun `a form post toggles seen and goes back to the page it came from`() {
        val flash = RedirectAttributesModelMap()

        val view = controller.toggle(user, 949, "movie", null, request("http://localhost:8150/discover?type=movie&genre=horror"), flash)

        assertEquals("redirect:/discover?type=movie&genre=horror", view)
        assertEquals(WatchStatus.WATCHED, status())
        assertEquals("seen.marked", flash.flashAttributes["success"])
    }

    @Test
    fun `an explicit back wins over the referer and keeps its anchor`() {
        val view = controller.toggle(user, 949, "movie", "/list/7?show=unseen&mark=1#list-item-3", request("http://localhost/elsewhere"), RedirectAttributesModelMap())

        assertEquals("redirect:/list/7?show=unseen&mark=1#list-item-3", view)
    }

    @Test
    fun `a back pointing outside the app is ignored`() {
        val view = controller.toggle(user, 949, "movie", "https://evil.example/phish", null.let { request() }, RedirectAttributesModelMap())

        assertEquals("redirect:/phish", view, "only the path of a full URL is ever used")
        assertEquals("redirect:/watchlist", controller.toggle(user, 949, "movie", "javascript:alert(1)", request(), RedirectAttributesModelMap()))
    }

    @Test
    fun `tapping seen on a rated title explains instead of clearing it`() {
        ratings.save(RatingFactory.aQuickRating(movieId = heat.id!!, userId = user.id!!, score = 9.0))
        statuses.save(MediaStatus(user.id!!, MediaType.movie, heat.id!!.value, WatchStatus.WATCHED, 1L))
        val flash = RedirectAttributesModelMap()

        controller.toggle(user, 949, "movie", "/movie/949", request(), flash)

        assertEquals("seen.locked.rated", flash.flashAttributes["error"])
        assertEquals(WatchStatus.WATCHED, status())
    }

    @Test
    fun `bad input and unknown titles are reported, never saved`() {
        val bad = RedirectAttributesModelMap()
        controller.toggle(user, 949, "book", null, request(), bad)
        assertEquals("status.error.invalid", bad.flashAttributes["error"])

        val missing = RedirectAttributesModelMap()
        controller.toggle(user, 404, "movie", null, request(), missing)
        assertEquals("status.error.media.not.found", missing.flashAttributes["error"])

        verify(importer, never()).ensure(eq(949), eq(MediaType.tvshow), anyOrNull())
        assertNull(status())
    }

    @Test
    fun `fetch gets the tile's new state as JSON`() {
        statuses.save(MediaStatus(user.id!!, MediaType.movie, heat.id!!.value, WatchStatus.WANT_TO_WATCH, 1L))

        val on = controller.toggleJson(user, 949, "movie")
        assertEquals(HttpStatus.OK, on.statusCode)
        with(on.body!!) {
            assertTrue(ok)
            assertEquals("watched", status)
            assertTrue(seen)
            assertFalse(seenLocked)
            assertFalse(onWatchlist, "seen takes it off the watchlist")
            assertFalse(canToggleWatchlist, "the bookmark hides on a Watched title")
            assertEquals(1, seenDelta)
            assertEquals("Marked as seen.", message)
        }

        val off = controller.toggleJson(user, 949, "movie").body!!
        assertNull(off.status)
        assertFalse(off.seen)
        assertTrue(off.canToggleWatchlist, "the bookmark comes back")
        assertEquals(-1, off.seenDelta)
    }

    @Test
    fun `fetch on an upcoming title answers ok=false with the reason, and changes nothing`() {
        val response = controller.toggleJson(user, 1003596, "movie")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertFalse(response.body!!.ok)
        assertEquals("It isn't out yet, so it can't be marked as seen.", response.body!!.message)
        assertNull(status(doomsday.id!!.value))
    }

    @Test
    fun `fetch with bad input gets a 4xx so the page falls back to a normal post`() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.toggleJson(user, 949, "game").statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, controller.toggleJson(user, 0, "movie").statusCode)
        assertEquals(HttpStatus.NOT_FOUND, controller.toggleJson(user, 404, "movie").statusCode)
    }

    @Test
    fun `the bookmark answers fetch with JSON too`() {
        val added = quickJson.toggle(user, 949, "movie").body!!
        assertTrue(added.onWatchlist)
        assertEquals("want_to_watch", added.status)
        assertEquals("Added to your watchlist.", added.message)

        val removed = quickJson.toggle(user, 949, "movie").body!!
        assertFalse(removed.onWatchlist)
        assertNull(removed.status)

        statuses.save(MediaStatus(user.id!!, MediaType.movie, heat.id!!.value, WatchStatus.WATCHED, 1L))
        val unchanged = quickJson.toggle(user, 949, "movie").body!!
        assertEquals("watched", unchanged.status, "the bookmark never downgrades a Watched title")
        assertTrue(unchanged.seen)

        assertEquals(HttpStatus.BAD_REQUEST, quickJson.toggle(user, 949, "song").statusCode)
    }

    @Test
    fun `tile types accept movie, tv and tvshow`() {
        assertEquals(MediaType.movie, parseTileType(" Movie "))
        assertEquals(MediaType.tvshow, parseTileType("tv"))
        assertEquals(MediaType.tvshow, parseTileType("tvshow"))
        assertNull(parseTileType("book"))
        assertNull(parseTileType(null))
    }
}
