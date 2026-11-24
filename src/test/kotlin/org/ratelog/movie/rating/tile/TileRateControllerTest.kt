package org.ratelog.movie.rating.tile

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.media.MediaImporter
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.movie.rating.add.AddRatingHandler
import org.ratelog.movie.rating.delete.DeleteRatingHandler
import org.ratelog.movie.rating.update.UpdateRatingHandler
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.UserFactory
import org.ratelog.tvshow.rating.show.DeleteShowRatingHandler
import org.ratelog.tvshow.rating.show.RateShowHandler
import org.ratelog.tvshow.rating.show.TvTileRateHandler
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.autowatched.AutoWatchedRatingRepository
import org.ratelog.watchlist.autowatched.MarkWatchedOnRatingHandler
import org.ratelog.watchlist.seen.SeenStateReader
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import java.time.LocalDate
import java.util.Locale

class TileRateControllerTest {

    private val clock = MutableClock()
    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val ratings = AutoWatchedRatingRepository(InMemoryRatingRepository(), MarkWatchedOnRatingHandler(statuses, InMemoryTvShowRepository(), clock))
    private val tvRatings = InMemoryTvRatingRepository()
    private val shows = InMemoryTvShowRepository()
    private val reader = SeenStateReader(statuses, ratings, tvRatings)
    private val importer: MediaImporter = mock()
    private val controller = TileRateController(
        importer,
        TileRateHandler(movies, ratings, AddRatingHandler(ratings), UpdateRatingHandler(ratings), reader, clock),
        TileDeleteRatingHandler(ratings, DeleteRatingHandler(movies, ratings), reader),
        TvTileRateHandler(shows, tvRatings, RateShowHandler(shows, tvRatings, clock), DeleteShowRatingHandler(tvRatings), reader, clock),
        BrowseBarViewsTest.testMessageSource(),
    )

    private val user = UserFactory.aUser(id = 1)
    private val heat = movies.save(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseDate = LocalDate.parse("1995-12-15")))
    private val doomsday = movies.save(MovieFactory.aMovie(tmdbId = 1003596, originalTitle = "Avengers: Doomsday", releaseDate = LocalDate.parse("2026-12-18")))

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        listOf(heat to "Heat", doomsday to "Avengers: Doomsday").forEach { (m, title) ->
            whenever(importer.ensure(eq(m.tmdbId.value), eq(MediaType.movie), any()))
                .thenReturn(Result.success(LocalMedia(m.id!!.value, m.tmdbId.value, MediaType.movie, title, 2000, null)))
        }
        whenever(importer.ensure(eq(404), any(), any())).thenReturn(Result.failure(IllegalStateException("not on TMDB")))
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun request(referer: String? = null) = MockHttpServletRequest().apply { referer?.let { addHeader("Referer", it) } }
    private fun rating() = ratings.findByMovieIdAndUserId(heat.id!!, user.id!!)

    @Test
    fun `fetch - rating answers the tile's new state, the chip label and a toast`() {
        val response = controller.rateJson(user, 949, "movie", 8.0)
        val body = response.body!!

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(body.ok)
        assertTrue(body.rated)
        assertEquals(8.0, body.score)
        assertEquals("8.0", body.scoreLabel)
        assertFalse(body.detailed)
        assertEquals("watched", body.status)
        assertNull(body.previousStatus)
        assertTrue(body.seen)
        assertTrue(body.seenLocked)
        assertTrue(body.canToggleWatchlist, "a rated title can go on the watchlist too (fork, 2026-11-24)")
        assertEquals(1, body.seenDelta)
        assertEquals("Rated \"Heat\" 8/10 and marked it seen.", body.message)
    }

    @Test
    fun `fetch - changing a quick rating says so, with the compact score`() {
        ratings.save(RatingFactory.aQuickRating(movieId = heat.id!!, userId = user.id!!, score = 6.0))

        val body = controller.rateJson(user, 949, null, 7.5).body!!

        assertEquals("Changed your rating of \"Heat\" to 7.5/10.", body.message)
        assertEquals("7.5", body.scoreLabel)
        assertEquals(0, body.seenDelta)
    }

    @Test
    fun `fetch - a seen movie rated is just Rated, and a perfect 10 reads 10`() {
        statuses.save(MediaStatus(user.id!!, MediaType.movie, heat.id!!.value, WatchStatus.WATCHED, 1L))

        val body = controller.rateJson(user, 949, "movie", 10.0).body!!

        assertEquals("Rated \"Heat\" 10/10.", body.message)
        assertEquals("10", body.scoreLabel)
        assertEquals("watched", body.previousStatus)
    }

    @Test
    fun `fetch - a detailed rating is refused with 409 and reported as detailed, untouched`() {
        ratings.save(RatingFactory.aRating(movieId = heat.id!!, userId = user.id!!, directing = 9.0, cinematography = 9.0, acting = 9.0, soundtrack = 9.0, screenplay = 9.0))

        val response = controller.rateJson(user, 949, "movie", 2.0)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        val body = response.body!!
        assertFalse(body.ok)
        assertTrue(body.detailed)
        assertTrue(body.rated)
        assertEquals("9.0", body.scoreLabel)
        assertTrue(body.message!!.startsWith("\"Heat\" has a detailed rating"), body.message)
        assertFalse(rating()!!.isQuick)
    }

    @Test
    fun `fetch - upcoming, invalid scores, shows and unknown titles are refused with a message`() {
        val upcoming = controller.rateJson(user, 1003596, "movie", 8.0)
        assertEquals(HttpStatus.OK, upcoming.statusCode)
        assertFalse(upcoming.body!!.ok)
        assertEquals("\"Avengers: Doomsday\" isn't out yet.", upcoming.body!!.message)

        val invalid = controller.rateJson(user, 949, "movie", 11.0)
        assertEquals(HttpStatus.BAD_REQUEST, invalid.statusCode)
        assertEquals("Pick a score from 1 to 10.", invalid.body!!.message)

        // Fork: `tv` rates a show as a whole (TvTileRateTest); other types are refused before any lookup.
        val tv = controller.rateJson(user, 949, "book", 8.0)
        assertEquals(HttpStatus.BAD_REQUEST, tv.statusCode)
        verify(importer, never()).ensure(eq(949), eq(MediaType.tvshow), any())

        val unknown = controller.rateJson(user, 404, "movie", 8.0)
        assertEquals(HttpStatus.NOT_FOUND, unknown.statusCode)
        assertEquals("That title could not be found.", unknown.body!!.message)
        assertNull(rating())
    }

    @Test
    fun `fetch - delete answers the unrated state, still seen`() {
        controller.rateJson(user, 949, "movie", 8.0)

        val body = controller.deleteJson(user, 949, "movie").body!!

        assertTrue(body.ok)
        assertFalse(body.rated)
        assertNull(body.score)
        assertNull(body.scoreLabel)
        assertTrue(body.seen)
        assertFalse(body.seenLocked)
        assertEquals("Deleted your rating of \"Heat\".", body.message)
        assertNull(rating())
    }

    @Test
    fun `form posts redirect back to the page with a flash message`() {
        val flash = RedirectAttributesModelMap()
        val view = controller.rate(user, 949, "movie", 9.0, "/discover?type=movie#row-horror", request("http://localhost/other"), flash)

        assertEquals("redirect:/discover?type=movie#row-horror", view)
        assertEquals("success.rating.saved", flash.flashAttributes["success"])

        val again = RedirectAttributesModelMap()
        assertEquals("redirect:/list/3", controller.rate(user, 949, "movie", 4.0, null, request("http://localhost:8175/list/3"), again))
        assertEquals("success.rating.updated", again.flashAttributes["success"])

        val deleted = RedirectAttributesModelMap()
        assertEquals("redirect:/movie/949", controller.delete(user, 949, "movie", "javascript:alert(1)", request(), deleted))
        assertEquals("success.rating.deleted", deleted.flashAttributes["success"])
    }

    @Test
    fun `form post errors use the keys without arguments`() {
        ratings.save(RatingFactory.aRating(movieId = heat.id!!, userId = user.id!!))
        val flash = RedirectAttributesModelMap()

        controller.rate(user, 949, "movie", 3.0, "/", request(), flash)
        assertEquals("tile.rate.error.detailed.plain", flash.flashAttributes["error"])

        val unknown = RedirectAttributesModelMap()
        controller.rate(user, 404, "movie", 3.0, "/", request(), unknown)
        assertEquals("status.error.media.not.found", unknown.flashAttributes["error"])
    }

    @Test
    fun `scores in sentences drop trailing zeros`() {
        assertEquals("8", TileRateController.compactScore(8.0))
        assertEquals("7.5", TileRateController.compactScore(7.5))
        assertEquals("7.25", TileRateController.compactScore(7.25))
        assertEquals("7.8", TileRateController.scoreLabel(7.75))
    }
}
