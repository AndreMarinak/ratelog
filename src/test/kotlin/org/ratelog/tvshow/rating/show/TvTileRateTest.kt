package org.ratelog.tvshow.rating.show

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.SeasonNumber
import org.ratelog.Score
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.media.MediaImporter
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.movie.rating.add.AddRatingHandler
import org.ratelog.movie.rating.delete.DeleteRatingHandler
import org.ratelog.movie.rating.tile.TileDeleteRatingHandler
import org.ratelog.movie.rating.tile.TileRateController
import org.ratelog.movie.rating.tile.TileRateHandler
import org.ratelog.movie.rating.update.UpdateRatingHandler
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MutableClock
import org.ratelog.test.TvShowFactory
import org.ratelog.test.UserFactory
import org.ratelog.tvshow.rating.addseason.AddSeasonRating
import org.ratelog.tvshow.rating.addseason.AddSeasonRatingHandler
import org.ratelog.watchlist.autowatched.AutoWatchedTvRatingRepository
import org.ratelog.watchlist.autowatched.MarkWatchedOnRatingHandler
import org.ratelog.watchlist.seen.SeenStateReader
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import java.time.LocalDate
import java.util.Locale

/** The poster ★ picker on a show (`type=tv`): one tap gives the whole show a score, unless seasons are rated. */
class TvTileRateTest {

    private val clock = MutableClock()
    private val statuses = InMemoryMediaStatusRepository()
    private val shows = InMemoryTvShowRepository()
    private val movies = InMemoryMovieRepository()
    private val ratings = InMemoryRatingRepository()
    private val tvRatings = AutoWatchedTvRatingRepository(InMemoryTvRatingRepository(), MarkWatchedOnRatingHandler(statuses, shows, clock))
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
    private val show = shows.save(TvShowFactory.aTvShow(tmdbId = 1396, originalName = "Breaking Bad", firstAirDate = LocalDate.parse("2008-01-20")))
    private val upcoming = shows.save(TvShowFactory.aTvShow(tmdbId = 999001, originalName = "Next Year", firstAirDate = LocalDate.parse("2027-06-01")))

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        listOf(show to "Breaking Bad", upcoming to "Next Year").forEach { (s, title) ->
            whenever(importer.ensure(eq(s.tmdbId.value), eq(MediaType.tvshow), any()))
                .thenReturn(Result.success(LocalMedia(s.id!!.value, s.tmdbId.value, MediaType.tvshow, title, 2008, null)))
        }
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun rating() = tvRatings.findByTvShowIdAndUserId(show.id!!, user.id!!)

    @Test
    fun `a show's star sets its whole-show score, marks it seen, and can change it`() {
        val body = controller.rateJson(user, 1396, "tv", 8.0).body!!

        assertTrue(body.ok)
        assertEquals(8.0, body.score)
        assertEquals("8.0", body.scoreLabel)
        assertEquals("watched", body.status)
        assertTrue(body.seen)
        assertFalse(body.seenLocked, "shows are never locked")
        assertFalse(body.seasons)
        assertEquals(1, body.seenDelta)
        assertEquals("Rated \"Breaking Bad\" 8/10 and marked it seen.", body.message)
        assertEquals(8.0, rating()!!.showScore?.value)

        val changed = controller.rateJson(user, 1396, "tv", 6.5).body!!
        assertEquals("Changed your rating of \"Breaking Bad\" to 6.5/10.", changed.message)
        assertEquals(6.5, rating()!!.score?.value)
    }

    @Test
    fun `a show rated by season is refused with its average, so the picker offers Rate seasons`() {
        AddSeasonRatingHandler(tvRatings).handle(AddSeasonRating(show.id!!, SeasonNumber(1), user.id!!, null, null, null, null, null, null, null, Score(7.0)))

        val response = controller.rateJson(user, 1396, "tv", 9.0)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        val body = response.body!!
        assertFalse(body.ok)
        assertTrue(body.seasons)
        assertTrue(body.detailed)
        assertEquals(7.0, body.score)
        assertEquals("\"Breaking Bad\" is rated by season, so its score is their average. Rate seasons on its page.", body.message)
        assertNull(rating()!!.showScore)
        assertEquals(HttpStatus.CONFLICT, controller.deleteJson(user, 1396, "tv").statusCode)
    }

    @Test
    fun `delete removes the whole-show score, the show stays seen`() {
        controller.rateJson(user, 1396, "tv", 8.0)

        val body = controller.deleteJson(user, 1396, "tv").body!!

        assertTrue(body.ok)
        assertFalse(body.rated)
        assertTrue(body.seen, "still Watched: deleting never touches the status")
        assertEquals("Deleted your rating of \"Breaking Bad\".", body.message)
        assertNull(rating())
        assertEquals(HttpStatus.NOT_FOUND, controller.deleteJson(user, 1396, "tv").statusCode)
    }

    @Test
    fun `shows that haven't aired are refused, and a plain post goes back to the show`() {
        val body = controller.rateJson(user, 999001, "tv", 8.0).body!!
        assertFalse(body.ok)
        assertEquals("\"Next Year\" isn't out yet.", body.message)

        val flash = RedirectAttributesModelMap()
        val view = controller.rate(user, 1396, "tv", 7.0, null, MockHttpServletRequest(), flash)
        assertEquals("redirect:/tv/1396", view)
        assertEquals("success.rating.saved", flash.flashAttributes["success"])
    }
}
