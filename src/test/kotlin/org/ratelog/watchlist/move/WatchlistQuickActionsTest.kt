package org.ratelog.watchlist.move

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
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.getwatchlist.GetWatchlistHandler
import org.ratelog.watchlist.getwatchlist.WatchlistController
import org.ratelog.watchlist.getwatchlist.WatchlistItemResponse
import org.ratelog.watchlist.getwatchlist.WatchlistTabResponse
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import java.time.LocalDate
import java.util.Locale

/** The /watchlist page: tab names, the buttons per tile, and POST /watchlist/item (form and fetch). */
class WatchlistQuickActionsTest {

    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val ratings = InMemoryRatingRepository()
    private val importer: MediaImporter = mock()
    private val handler = MoveWatchlistItemHandler(SetWatchStatusHandler(statuses, movies, shows))
    private val controller = MoveWatchlistItemController(handler, importer, BrowseBarViewsTest.testMessageSource())
    private val page = WatchlistController(
        GetWatchlistHandler(statuses, movies, InMemoryMovieDescriptionRepository(), ratings, shows, InMemoryTvDescriptionRepository(), InMemoryTvRatingRepository()),
        MutableClock(), // today: 2026-11-22
    )

    private val user = UserFactory.aUser(id = 1)
    private val dune = movies.save(MovieFactory.aMovie(tmdbId = 693134, originalTitle = "Dune: Part Two", releaseDate = LocalDate.parse("2024-02-27")))
    private val doomsday = movies.save(MovieFactory.aMovie(tmdbId = 1003596, originalTitle = "Avengers: Doomsday", releaseDate = LocalDate.parse("2026-12-18")))
    private val bear = shows.save(TvShowFactory.aTvShow(tmdbId = 136315, originalName = "The Bear", firstAirDate = LocalDate.parse("2022-06-23")))

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        whenever(importer.findLocalById(any(), any(), anyOrNull())).thenAnswer { inv ->
            val id = inv.getArgument<Long>(0)
            val type = inv.getArgument<MediaType>(1)
            val title = when (type) {
                MediaType.movie -> movies.findById(org.ratelog.movie.Movie.Id(id))?.originalTitle?.value
                MediaType.tvshow -> shows.findById(org.ratelog.tvshow.TvShow.Id(id))?.originalName?.value
            }
            title?.let { LocalMedia(id, 0, type, it, null, null) }
        }
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun want(id: Long, type: MediaType = MediaType.movie) = statuses.save(MediaStatus(user.id!!, type, id, WatchStatus.WANT_TO_WATCH, 1L))
    private fun status(id: Long, type: MediaType = MediaType.movie) = statuses.find(user.id!!, type, id)?.status
    private fun request() = MockHttpServletRequest()

    // --- POST /watchlist/item (plain form) ---

    @Test
    fun `remove clears the status and comes back to the same tab and filter with an undo notice`() {
        want(dune.id!!.value)
        val flash = RedirectAttributesModelMap()

        val view = controller.move(user, "movie", dune.id!!.value, "remove", "/watchlist?status=want_to_watch&type=movie", null, request(), flash)

        assertEquals("redirect:/watchlist?status=want_to_watch&type=movie", view)
        assertNull(status(dune.id!!.value))
        val notice = flash.flashAttributes["watchlistNotice"] as WatchlistNotice
        assertEquals("watchlist.notice.removed.want_to_watch", notice.messageKey)
        assertEquals("Dune: Part Two", notice.title)
        assertEquals("want_to_watch", notice.undoMove, "undo puts it back on the watchlist")
        assertEquals("movie", notice.mediaType)
    }

    @Test
    fun `undo restores the previous status and offers no further undo`() {
        val flash = RedirectAttributesModelMap()

        controller.move(user, "movie", dune.id!!.value, "want_to_watch", "/watchlist", "1", request(), flash)

        assertEquals(WatchStatus.WANT_TO_WATCH, status(dune.id!!.value))
        val notice = flash.flashAttributes["watchlistNotice"] as WatchlistNotice
        assertEquals("watchlist.notice.restored", notice.messageKey)
        assertNull(notice.undoMove)
    }

    @Test
    fun `seen and watching move the title to those tabs`() {
        want(dune.id!!.value)
        want(bear.id!!.value, MediaType.tvshow)

        val seen = RedirectAttributesModelMap()
        controller.move(user, "movie", dune.id!!.value, "seen", "/watchlist", null, request(), seen)
        controller.move(user, "tvshow", bear.id!!.value, "watching", "/watchlist?type=tv", null, request(), RedirectAttributesModelMap())

        assertEquals(WatchStatus.WATCHED, status(dune.id!!.value))
        assertEquals(WatchStatus.WATCHING, status(bear.id!!.value, MediaType.tvshow))
        assertEquals("watchlist.notice.watched", (seen.flashAttributes["watchlistNotice"] as WatchlistNotice).messageKey)
    }

    @Test
    fun `without a back field the referer is used, and a bad one falls back to the watchlist`() {
        want(dune.id!!.value)
        val req = MockHttpServletRequest().apply { addHeader("Referer", "http://localhost:8150/watchlist?status=watching") }

        assertEquals("redirect:/watchlist?status=watching", controller.move(user, "movie", dune.id!!.value, "remove", null, null, req, RedirectAttributesModelMap()))
        assertEquals("redirect:/watchlist", controller.move(user, "movie", dune.id!!.value, "remove", "elsewhere", null, request(), RedirectAttributesModelMap()))
    }

    @Test
    fun `nothing changed means no notice, bad input an error`() {
        val same = RedirectAttributesModelMap()
        controller.move(user, "movie", dune.id!!.value, "remove", "/watchlist", null, request(), same)
        assertFalse(same.flashAttributes.containsKey("watchlistNotice"))

        val bad = RedirectAttributesModelMap()
        controller.move(user, "book", dune.id!!.value, "remove", "/watchlist", null, request(), bad)
        assertEquals("status.error.invalid", bad.flashAttributes["error"])

        val unknownMove = RedirectAttributesModelMap()
        controller.move(user, "movie", dune.id!!.value, "binge", "/watchlist", null, request(), unknownMove)
        assertEquals("status.error.invalid", unknownMove.flashAttributes["error"])

        val missing = RedirectAttributesModelMap()
        controller.move(user, "movie", 999, "seen", "/watchlist", null, request(), missing)
        assertEquals("status.error.media.not.found", missing.flashAttributes["error"])
    }

    // --- POST /watchlist/item (fetch) ---

    @Test
    fun `fetch gets the move, a localized message and the undo`() {
        want(dune.id!!.value)

        val response = controller.moveJson(user, "movie", dune.id!!.value, "remove", null)

        assertEquals(HttpStatus.OK, response.statusCode)
        with(response.body!!) {
            assertTrue(ok)
            assertEquals("want_to_watch", previous)
            assertNull(current)
            assertEquals("Removed \"Dune: Part Two\" from your watchlist.", message)
            assertEquals("want_to_watch", undoMove)
            assertEquals("Undo", undoLabel)
        }

        val undo = controller.moveJson(user, "movie", dune.id!!.value, "want_to_watch", "1").body!!
        assertEquals("Put \"Dune: Part Two\" back.", undo.message)
        assertNull(undo.undoMove)
        assertEquals(WatchStatus.WANT_TO_WATCH, status(dune.id!!.value))
    }

    @Test
    fun `fetch with bad input or an unknown title is a 4xx`() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.moveJson(user, "movie", dune.id!!.value, "binge", null).statusCode)
        assertEquals(HttpStatus.NOT_FOUND, controller.moveJson(user, "movie", 999, "seen", null).statusCode)
    }

    @Test
    fun `move names parse loosely`() {
        assertEquals(WatchlistMove.SEEN, WatchlistMove.parse(" Seen "))
        assertEquals(WatchlistMove.SEEN, WatchlistMove.parse("watched"))
        assertEquals(WatchlistMove.REMOVE, WatchlistMove.parse("clear"))
        assertEquals(WatchlistMove.TO_WATCH, WatchlistMove.parse("want-to-watch"))
        assertNull(WatchlistMove.parse("binge"))
        assertNull(WatchlistMove.parse(null))
        assertEquals(WatchlistMove.REMOVE, WatchlistMove.to(null))
        assertEquals(WatchlistMove.DROPPED, WatchlistMove.to(WatchStatus.DROPPED))
    }

    // --- GET /watchlist ---

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `tabs read To watch, Watching, Seen, Dropped, and every tile can be removed in one tap`() {
        want(dune.id!!.value)
        want(doomsday.id!!.value)
        want(bear.id!!.value, MediaType.tvshow)
        val model = ExtendedModelMap()

        page.watchlistPage(user, null, null, model)

        val tabs = model["tabs"] as List<WatchlistTabResponse>
        assertEquals(listOf("watchlist.tab.want_to_watch", "watchlist.tab.watching", "watchlist.tab.watched", "watchlist.tab.dropped"), tabs.map { it.labelKey })
        assertEquals(listOf("want_to_watch", "watching", "watched", "dropped"), tabs.map { it.status })
        assertEquals(3, tabs.first().count)
        assertEquals("/watchlist?status=want_to_watch", model["watchlistSelf"])
        assertEquals("watchlist.tab.empty.want_to_watch", model["emptyKey"])

        val items = (model["items"] as List<WatchlistItemResponse>).associateBy { it.title }
        assertTrue(items.values.all { item -> item.actions.any { it.to == "remove" } }, "Remove on every tile")
        assertEquals(listOf("seen", "remove"), items.getValue("Dune: Part Two").actions.map { it.to })
        assertEquals(listOf("seen", "watching", "remove"), items.getValue("The Bear").actions.map { it.to })
        val upcoming = items.getValue("Avengers: Doomsday")
        assertEquals(listOf("remove"), upcoming.actions.map { it.to }, "no Seen before it's out")
        assertEquals("Dec 18", upcoming.upcomingDate)
        assertNull(items.getValue("Dune: Part Two").upcomingDate)
        assertEquals("tvshow", items.getValue("The Bear").mediaType)
        assertEquals(bear.id!!.value, items.getValue("The Bear").mediaId)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `the back link keeps the type filter`() {
        val model = ExtendedModelMap()
        page.watchlistPage(user, "watching", "tv", model)
        assertEquals("/watchlist?status=watching&type=tv", model["watchlistSelf"])
        assertEquals("watchlist.tab.hint.watching", model["tabHintKey"])
    }

    @Test
    fun `buttons per tab`() {
        fun moves(status: WatchStatus, isTv: Boolean = false, upcoming: Boolean = false, rated: Boolean = false) =
            WatchlistController.actionsFor(status, isTv, upcoming, rated).map { it.to }

        assertEquals(listOf("seen", "remove"), moves(WatchStatus.WANT_TO_WATCH))
        assertEquals(listOf("seen", "watching", "remove"), moves(WatchStatus.WANT_TO_WATCH, isTv = true))
        assertEquals(listOf("remove"), moves(WatchStatus.WANT_TO_WATCH, isTv = true, upcoming = true))
        assertEquals(listOf("seen", "dropped", "remove"), moves(WatchStatus.WATCHING, isTv = true))
        assertEquals(listOf("seen", "remove"), moves(WatchStatus.WATCHING))
        assertEquals(listOf("remove"), moves(WatchStatus.WATCHED))
        assertEquals(emptyList<String>(), moves(WatchStatus.WATCHED, rated = true), "a rated title stays seen through its rating")
        assertEquals(listOf("watching", "seen", "remove"), moves(WatchStatus.DROPPED, isTv = true))
        assertEquals("watchlist.action.remove.aria.watched", WatchlistController.actionsFor(WatchStatus.WATCHED, false, false, false).single().ariaKey)
    }

    @Test
    fun `a rated title on the Seen tab shows its score and no remove`() {
        statuses.save(MediaStatus(user.id!!, MediaType.movie, dune.id!!.value, WatchStatus.WATCHED, 1L))
        ratings.save(RatingFactory.aQuickRating(movieId = dune.id!!, userId = user.id!!, score = 9.0))
        val model = ExtendedModelMap()

        page.watchlistPage(user, "watched", null, model)

        @Suppress("UNCHECKED_CAST")
        val item = (model["items"] as List<WatchlistItemResponse>).single()
        assertEquals(9.0, item.score)
        assertTrue(item.actions.isEmpty())
    }
}
