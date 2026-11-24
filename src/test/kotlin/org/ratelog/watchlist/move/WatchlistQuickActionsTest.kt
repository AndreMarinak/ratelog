package org.ratelog.watchlist.move

import org.ratelog.test.WatchlistHandlers
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
import org.ratelog.watchlist.getwatchlist.WatchlistTypeFilterResponse
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
    private val rewatches = org.ratelog.test.InMemoryRewatchRepository()
    private val handler = WatchlistHandlers.move(statuses, movies, shows, ratings, rewatches = rewatches)
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
    private fun status(id: Long, type: MediaType = MediaType.movie) = statuses.effective(user.id!!, type, id)
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
        assertEquals("relist", notice.undoMove, "undo puts it back on the watchlist")
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

    // --- Fork (2026-11-24, rewatch log): Watched again on a rewatch ---

    private fun rewatchOnWatchlist() {
        statuses.save(MediaStatus(user.id!!, MediaType.movie, dune.id!!.value, WatchStatus.WATCHED, 1))
        statuses.addToWatchlist(org.ratelog.watchlist.WatchlistEntry(user.id!!, MediaType.movie, dune.id!!.value, 2, rewatch = true))
    }

    @Test
    fun `Watched again logs a rewatch, takes it off the watchlist and keeps it seen, Undo reverses both`() {
        rewatchOnWatchlist()
        val flash = RedirectAttributesModelMap()

        controller.move(user, "movie", dune.id!!.value, "rewatched", "/watchlist?status=want_to_watch&type=movie", null, request(), flash)

        assertNull(statuses.findWatchlistEntry(user.id!!, MediaType.movie, dune.id!!.value), "off the watchlist")
        assertEquals(WatchStatus.WATCHED, statuses.find(user.id!!, MediaType.movie, dune.id!!.value)?.status, "still seen")
        val row = rewatches.all().single()
        val notice = flash.flashAttributes["watchlistNotice"] as WatchlistNotice
        assertEquals("watchlist.notice.rewatched", notice.messageKey)
        assertEquals("unrewatch", notice.undoMove)
        assertEquals(row.id, notice.rewatchId)

        val undo = RedirectAttributesModelMap()
        controller.move(user, "movie", dune.id!!.value, "unrewatch", "/watchlist", "1", request(), undo, rewatchId = row.id)
        assertTrue(rewatches.all().isEmpty(), "the logged row is gone")
        val entry = statuses.findWatchlistEntry(user.id!!, MediaType.movie, dune.id!!.value)!!
        assertTrue(entry.rewatch, "back on To watch as a rewatch")
        assertEquals("watchlist.notice.restored", (undo.flashAttributes["watchlistNotice"] as WatchlistNotice).messageKey)
    }

    @Test
    fun `Watched again by fetch moves the To watch count only, and undo deletes only that title's row`() {
        rewatchOnWatchlist()
        val response = controller.moveJson(user, "movie", dune.id!!.value, "rewatched", null).body!!
        assertTrue(response.ok)
        assertEquals("want_to_watch", response.previous)
        assertNull(response.current, "it was already on the Seen tab")
        assertEquals("Logged a rewatch of \"Dune: Part Two\" and took it off your watchlist.", response.message)
        assertEquals("unrewatch", response.undoMove)
        val id = response.rewatchId!!

        // Another title's id is ignored: only Dune's rows can be undone from Dune's tile.
        controller.moveJson(user, "tvshow", bear.id!!.value, "unrewatch", "1", rewatchId = id)
        assertEquals(1, rewatches.all().size)

        val undo = controller.moveJson(user, "movie", dune.id!!.value, "unrewatch", "1", rewatchId = id).body!!
        assertTrue(undo.ok)
        assertEquals("want_to_watch", undo.current)
        assertNull(undo.undoMove)
        assertNull(undo.rewatchId)
        assertTrue(rewatches.all().isEmpty())
    }

    @Test
    fun `Watched again on a title you have not seen is refused and changes nothing`() {
        want(dune.id!!.value)
        val response = controller.moveJson(user, "movie", dune.id!!.value, "rewatched", null)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertFalse(response.body!!.ok)
        assertEquals("Mark it seen first: Watched again logs the times after the first.", response.body!!.message)
        assertTrue(rewatches.all().isEmpty())
        assertEquals(WatchStatus.WANT_TO_WATCH, status(dune.id!!.value), "still on the watchlist")

        val flash = RedirectAttributesModelMap()
        controller.move(user, "movie", dune.id!!.value, "rewatched", "/watchlist", null, request(), flash)
        assertEquals("rewatch.error.not.seen", flash.flashAttributes["error"])
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
            assertEquals("relist", undoMove)
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
        assertEquals(2, tabs.first().count, "opens on Movies")
        assertEquals("/watchlist?status=want_to_watch&type=movie", model["watchlistSelf"])
        assertEquals("watchlist.tab.empty.want_to_watch", model["emptyKey"])

        val tv = ExtendedModelMap().also { page.watchlistPage(user, null, "tv", it) }
        val items = ((model["items"] as List<WatchlistItemResponse>) + (tv["items"] as List<WatchlistItemResponse>)).associateBy { it.title }
        assertTrue(items.values.all { item -> item.actions.any { it.to == "unlist" } }, "Remove (off the watchlist) on every tile")
        assertEquals(listOf("seen", "unlist"), items.getValue("Dune: Part Two").actions.map { it.to })
        assertEquals(2, items.getValue("Dune: Part Two").firstSpan, "a movie's Seen takes the Watching slot, Remove lines up")
        assertEquals(listOf("seen", "watching", "unlist"), items.getValue("The Bear").actions.map { it.to })
        assertEquals(1, items.getValue("The Bear").firstSpan)
        assertTrue(items.values.all { it.slots == 3 })
        val upcoming = items.getValue("Avengers: Doomsday")
        assertEquals(listOf("unlist"), upcoming.actions.map { it.to }, "no Seen before it's out")
        assertEquals("watchlist.note.upcoming", upcoming.noteKey)
        assertEquals(2, upcoming.noteSpan)
        assertEquals(1, upcoming.firstSpan)
        assertEquals("tile.rate.mark", items.getValue("Dune: Part Two").tileActions!!.rateLabelKey, "the poster gets the star too")
        assertEquals(false, upcoming.tileActions!!.canRate)
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

    // --- Movies by default (user, 2026-11-23: "watchlist tab should default to movies like everything else") ---

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `the watchlist opens on Movies, and Movies or TV is a two-way switch every tab keeps`() {
        want(dune.id!!.value)
        want(bear.id!!.value, MediaType.tvshow)
        val model = ExtendedModelMap()

        page.watchlistPage(user, null, null, model)

        assertEquals(listOf("Dune: Part Two"), (model["items"] as List<WatchlistItemResponse>).map { it.title })
        val filters = model["typeFilters"] as List<WatchlistTypeFilterResponse>
        assertEquals(listOf("watchlist.filter.movies", "watchlist.filter.tv"), filters.map { it.labelKey }, "no All, like Ratings and Discover")
        assertEquals(listOf(true, false), filters.map { it.active })
        assertEquals(listOf("/watchlist?status=want_to_watch&type=movie", "/watchlist?status=want_to_watch&type=tv"), filters.map { it.href })
        val tabs = model["tabs"] as List<WatchlistTabResponse>
        assertTrue(tabs.all { it.href.endsWith("&type=movie") }, "status tabs keep the type: ${tabs.map { it.href }}")

        val tv = ExtendedModelMap().also { page.watchlistPage(user, "want_to_watch", "tv", it) }
        assertEquals(listOf("The Bear"), (tv["items"] as List<WatchlistItemResponse>).map { it.title })
        assertEquals(listOf(false, true), (tv["typeFilters"] as List<WatchlistTypeFilterResponse>).map { it.active })
        assertTrue((tv["tabs"] as List<WatchlistTabResponse>).all { it.href.endsWith("&type=tv") })

        val unknown = ExtendedModelMap().also { page.watchlistPage(user, null, "all", it) }
        assertEquals(listOf("Dune: Part Two"), (unknown["items"] as List<WatchlistItemResponse>).map { it.title }, "an unknown type is Movies too")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `Watching is for shows - a bare Watching link opens on TV, and Movies says why it's empty`() {
        statuses.save(MediaStatus(user.id!!, MediaType.tvshow, bear.id!!.value, WatchStatus.WATCHING, 1L))

        val bare = ExtendedModelMap().also { page.watchlistPage(user, "watching", null, it) } // Home's "Continue watching" See all
        assertEquals(listOf("The Bear"), (bare["items"] as List<WatchlistItemResponse>).map { it.title })
        assertEquals("/watchlist?status=watching&type=tv", bare["watchlistSelf"])
        assertEquals("watchlist.tab.hint.watching", bare["tabHintKey"])
        assertEquals(1, (bare["tabs"] as List<WatchlistTabResponse>).single { it.status == "watching" }.count)

        val movies = ExtendedModelMap().also { page.watchlistPage(user, "watching", "movie", it) }
        assertTrue((movies["items"] as List<WatchlistItemResponse>).isEmpty())
        assertEquals("watchlist.tab.empty.watching.movie", movies["emptyKey"])
        assertEquals("watchlist.tab.hint.watching.movie", movies["tabHintKey"])
        assertTrue((movies["tabs"] as List<WatchlistTabResponse>).any { it.status == "watching" }, "the tab stays on Movies")

        // A movie still Watching from before shows on Movies with Seen and Remove (no Drop: movies have none).
        statuses.save(MediaStatus(user.id!!, MediaType.movie, dune.id!!.value, WatchStatus.WATCHING, 2L))
        val legacy = ExtendedModelMap().also { page.watchlistPage(user, "watching", "movie", it) }
        assertEquals(listOf("seen", "remove"), (legacy["items"] as List<WatchlistItemResponse>).single().actions.map { it.to })

        assertEquals(MediaType.movie, WatchlistController.defaultType(WatchStatus.WANT_TO_WATCH))
        assertEquals(MediaType.movie, WatchlistController.defaultType(WatchStatus.WATCHED))
        assertEquals(MediaType.movie, WatchlistController.defaultType(WatchStatus.DROPPED))
        assertEquals(MediaType.tvshow, WatchlistController.defaultType(WatchStatus.WATCHING))
    }

    @Test
    fun `buttons per tab`() {
        fun moves(status: WatchStatus, isTv: Boolean = false, upcoming: Boolean = false, rated: Boolean = false) =
            WatchlistController.actionsFor(status, isTv, upcoming, rated).map { it.to }

        // Seen first, Watching / Drop for shows only, Remove last (2026-11-23: Watching is "bc its a series").
        // Fork (2026-11-24): To watch's Remove is `unlist` (off the watchlist only, so a rewatch stays seen).
        assertEquals(listOf("seen", "unlist"), moves(WatchStatus.WANT_TO_WATCH))
        assertEquals(listOf("seen", "watching", "unlist"), moves(WatchStatus.WANT_TO_WATCH, isTv = true))
        listOf(false, true).forEach { tv ->
            assertEquals(listOf("unlist"), moves(WatchStatus.WANT_TO_WATCH, isTv = tv, upcoming = true))
            assertEquals(listOf("remove"), moves(WatchStatus.WATCHED, isTv = tv))
            assertEquals(emptyList<String>(), moves(WatchStatus.WATCHED, isTv = tv, rated = true), "a rated title stays seen through its rating")
            assertEquals(listOf("remove"), moves(WatchStatus.DROPPED, isTv = tv, upcoming = true))
        }
        assertEquals(listOf("seen", "dropped", "remove"), moves(WatchStatus.WATCHING, isTv = true))
        assertEquals(listOf("seen", "remove"), moves(WatchStatus.WATCHING))
        assertEquals(listOf("seen", "watching", "remove"), moves(WatchStatus.DROPPED, isTv = true), "same order as To watch")
        assertEquals(listOf("seen", "remove"), moves(WatchStatus.DROPPED))
        assertEquals(3, WatchlistController.slotsFor(WatchStatus.WANT_TO_WATCH))
        assertEquals(1, WatchlistController.slotsFor(WatchStatus.WATCHED))
        assertEquals("watchlist.note.upcoming", WatchlistController.noteFor(WatchStatus.WANT_TO_WATCH, upcoming = true, rated = false, hasDate = true))
        assertEquals("watchlist.note.upcoming.soon", WatchlistController.noteFor(WatchStatus.DROPPED, upcoming = true, rated = false, hasDate = false))
        assertEquals("watchlist.note.rated", WatchlistController.noteFor(WatchStatus.WATCHED, upcoming = false, rated = true, hasDate = true))
        assertEquals(null, WatchlistController.noteFor(WatchStatus.WATCHED, upcoming = false, rated = false, hasDate = true))
        assertEquals("watchlist.action.remove.aria.watched", WatchlistController.actionsFor(WatchStatus.WATCHED, false, false, false).single().ariaKey)
        // Fork (2026-11-24, rewatch log): a rewatch on To watch gets Watched again, then Remove; not while it isn't out.
        listOf(false, true).forEach { tv ->
            assertEquals(listOf("rewatched", "unlist"), WatchlistController.actionsFor(WatchStatus.WANT_TO_WATCH, tv, false, false, rewatch = true).map { it.to })
            assertEquals(listOf("unlist"), WatchlistController.actionsFor(WatchStatus.WANT_TO_WATCH, tv, true, false, rewatch = true).map { it.to })
        }
        assertEquals("is-rewatch", WatchlistController.actionsFor(WatchStatus.WANT_TO_WATCH, false, false, true, rewatch = true).first().kind)
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
