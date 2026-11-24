package org.ratelog.rewatch

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.media.MediaImporter
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.movie.Movie
import org.ratelog.rewatch.delete.DeleteRewatchHandler
import org.ratelog.rewatch.log.LogRewatchController
import org.ratelog.rewatch.log.RewatchNotice
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryRewatchRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.UserFactory
import org.ratelog.test.WatchlistHandlers
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import java.time.LocalDate
import java.util.Locale

/** POST /rewatch and /rewatch/delete: form posts (flash notice) and fetch (JSON). */
class LogRewatchControllerTest {
    private val clock = MutableClock()
    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val ratings = InMemoryRatingRepository()
    private val rewatches = InMemoryRewatchRepository()
    private val importer: MediaImporter = mock()
    private val controller = LogRewatchController(
        importer,
        WatchlistHandlers.logRewatch(rewatches, statuses, movies, InMemoryTvShowRepository(), ratings, clock = clock),
        DeleteRewatchHandler(rewatches),
        BrowseBarViewsTest.testMessageSource(),
    )
    private val user = UserFactory.aUser(id = 1)
    private val dune = movies.save(MovieFactory.aMovie(tmdbId = 438631, originalTitle = "Dune", releaseDate = LocalDate.parse("2021-09-15")))

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        whenever(importer.findLocal(any(), any(), anyOrNull())).thenAnswer { inv ->
            val tmdbId = inv.getArgument<Int>(0)
            movies.findByTmdbId(org.ratelog.TmdbId(tmdbId))?.let { LocalMedia(it.id!!.value, tmdbId, MediaType.movie, it.originalTitle.value, null, null) }
        }
        whenever(importer.findLocalById(any(), any(), anyOrNull())).thenAnswer { inv ->
            movies.findById(Movie.Id(inv.getArgument(0)))?.let { LocalMedia(it.id!!.value, it.tmdbId.value, MediaType.movie, it.originalTitle.value, null, null) }
        }
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun seen() = statuses.save(MediaStatus(user.id!!, MediaType.movie, dune.id!!.value, WatchStatus.WATCHED, clock.millis()))

    @Test
    fun `a form post logs it and comes back with a notice that offers Undo and a rating`() {
        seen()
        val flash = RedirectAttributesModelMap()
        val target = controller.log(user, 438631, "movie", "/movie/438631", MockHttpServletRequest(), flash)

        assertEquals("redirect:/movie/438631", target)
        val notice = flash.flashAttributes["rewatchNotice"] as RewatchNotice
        val row = rewatches.all().single()
        assertEquals(RewatchNotice("rewatch.notice.logged", "Dune", row.id, "/movie/438631?rate=1", "rewatch.notice.rate.new"), notice)

        ratings.save(RatingFactory.aQuickRating(movieId = dune.id!!, userId = user.id!!, score = 8.0))
        val again = RedirectAttributesModelMap()
        controller.log(user, 438631, "movie", "/movie/438631", MockHttpServletRequest(), again)
        assertEquals("rewatch.notice.rate.update", (again.flashAttributes["rewatchNotice"] as RewatchNotice).rateKey)
    }

    @Test
    fun `an unseen or unknown title is an error notice, bad input too`() {
        val flash = RedirectAttributesModelMap()
        controller.log(user, 438631, "movie", "/movie/438631", MockHttpServletRequest(), flash)
        assertEquals("rewatch.error.not.seen", flash.flashAttributes["error"])

        val unknown = RedirectAttributesModelMap()
        controller.log(user, 777, "movie", null, MockHttpServletRequest().apply { addHeader("Referer", "http://x/movie/777") }, unknown)
        assertEquals("rewatch.error.not.seen", unknown.flashAttributes["error"], "never stored, so never seen")

        val bad = RedirectAttributesModelMap()
        assertEquals("redirect:/", controller.log(user, 438631, "book", "https://evil.example/", MockHttpServletRequest(), bad))
        assertEquals("status.error.invalid", bad.flashAttributes["error"])
        assertTrue(rewatches.all().isEmpty())
    }

    @Test
    fun `fetch gets the count, the undo id and the labels`() {
        seen()
        val body = controller.logJson(user, 438631, "movie").body!!
        assertTrue(body.ok)
        assertEquals("Logged a rewatch of Dune.", body.message)
        assertEquals(2, body.watchCount)
        assertEquals(clock.millis(), body.lastWatchedAtEpochMs)
        assertEquals(rewatches.all().single().id, body.rewatchId)
        assertEquals("Undo", body.undoLabel)
        assertEquals("Rate it?", body.rateLabel)
        assertEquals("/movie/438631?rate=1", body.rateHref)

        val refused = controller.logJson(user, 1, "tv")
        assertEquals(HttpStatus.OK, refused.statusCode)
        assertFalse(refused.body!!.ok)
        assertEquals("Mark it seen first: Watched again logs the times after the first.", refused.body!!.message)
    }

    @Test
    fun `delete is the undo - a notice without actions, then not found`() {
        seen()
        val id = controller.logJson(user, 438631, "movie").body!!.rewatchId!!

        val flash = RedirectAttributesModelMap()
        assertEquals("redirect:/movie/438631", controller.delete(user, id, "/movie/438631", MockHttpServletRequest(), flash))
        val notice = flash.flashAttributes["rewatchNotice"] as RewatchNotice
        assertEquals("rewatch.notice.removed", notice.messageKey)
        assertNull(notice.rewatchId, "nothing more to undo")
        assertNull(notice.rateHref)
        assertTrue(rewatches.all().isEmpty())

        val again = controller.deleteJson(user, id)
        assertEquals(HttpStatus.NOT_FOUND, again.statusCode)
        assertEquals("That rewatch was already removed.", again.body!!.message)
    }

    @Test
    fun `delete by fetch says what is left`() {
        seen()
        controller.logJson(user, 438631, "movie")
        val id = controller.logJson(user, 438631, "movie").body!!.rewatchId!!
        val body = controller.deleteJson(user, id).body!!
        assertTrue(body.ok)
        assertEquals("Removed the rewatch of Dune.", body.message)
        assertEquals(2, body.watchCount)
    }
}
