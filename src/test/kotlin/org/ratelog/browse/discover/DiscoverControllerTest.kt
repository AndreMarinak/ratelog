package org.ratelog.browse.discover

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.browse.BrowseParams
import org.ratelog.browse.BrowseProvider
import org.ratelog.browse.BrowseTitle
import org.ratelog.browse.BrowseTitlePage
import org.ratelog.browse.TmdbBrowseClient
import org.ratelog.browse.TmdbBrowseError
import org.ratelog.browse.TmdbListRequest
import org.ratelog.browse.web.BrowseBarView
import org.ratelog.browse.web.BrowseBarViews
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.browse.web.BrowseTileView
import org.ratelog.browse.web.DiscoverRowView
import org.ratelog.browse.web.DiscoverRowViews
import org.ratelog.browse.web.SeenToggleView
import org.ratelog.test.InMemoryBrowseLibraryRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.ui.ConcurrentModel
import java.time.LocalDate
import java.util.Locale

class DiscoverControllerTest {

    private val client: TmdbBrowseClient = mock()
    private val clock = MutableClock()
    private val library = InMemoryBrowseLibraryRepository()
    private val browseHandler = BrowseHandler(client, library, clock)
    private val barViews = BrowseBarViews(BrowseBarViewsTest.testMessageSource(), client, clock)
    private val controller = DiscoverController(
        browseHandler,
        // One row at a time on the test thread: deterministic request order.
        GetDiscoverRowsHandler(browseHandler, eagerRows = 4, parallelRows = 1, waitMs = 1_000),
        barViews,
        DiscoverRowViews(BrowseBarViewsTest.testMessageSource(), barViews),
        clock,
    )
    private val user = UserFactory.aUser(id = 1, metadataLang = Lang.en)
    private val requests = mutableListOf<TmdbListRequest>()

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        whenever(client.providers(any(), any(), any())).thenReturn(listOf(BrowseProvider(8, "Netflix", null, 0)).right())
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    /** 20 titles per page with posters, ids `page * 100 + i`; [failGenres] (TMDB genre ids) fail. */
    private fun serve(totalPages: Int = 4, failGenres: Set<String> = emptySet()) {
        whenever(client.page(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<TmdbListRequest>(0)
            requests += request
            if (request.params["with_genres"] in failGenres) return@thenAnswer TmdbBrowseError.Failed("IOException").left()
            val page = request.params.getValue("page").toInt()
            val items = (1..20).map { i ->
                BrowseTitle(page * 100 + i, request.type, "Title $i", LocalDate.parse("2015-01-01"), "/p$i.jpg", 7.1, 300, emptyList(), "en")
            }
            BrowseTitlePage(items, page, totalPages, totalPages * 20).right()
        }
    }

    private fun rateMovie(tmdbId: Int) {
        val movie = library.movies.save(MovieFactory.aMovie(tmdbId = tmdbId, originalTitle = "Movie $tmdbId"))
        library.ratings.save(RatingFactory.aQuickRating(movieId = movie.id!!, userId = user.id!!, score = 8.0))
    }

    @Suppress("UNCHECKED_CAST")
    private fun ConcurrentModel.rows() = getAttribute("rows") as List<DiscoverRowView>

    @Suppress("UNCHECKED_CAST")
    private fun ConcurrentModel.tiles() = getAttribute("tiles") as List<BrowseTileView>

    private fun ConcurrentModel.bar() = getAttribute("browseBar") as BrowseBarView

    private fun ConcurrentModel.toggle() = getAttribute("seenToggle") as SeenToggleView

    // --- Landing ---

    @Test
    fun `given no filter then shows Popular now and genre rows, the first four loaded and the rest left to the page`() {
        serve()
        val model = ConcurrentModel()

        val view = controller.discover(user, BrowseParams(), null, model)

        assertEquals("discover", view)
        assertEquals(true, model.getAttribute("landing"))
        val rows = model.rows()
        assertEquals(
            listOf("popular", "horror", "comedy", "action", "animation", "drama", "science-fiction", "thriller", "romance", "crime",
                "fantasy", "mystery", "family", "documentary").map { "discover-row-$it" },
            rows.map { it.id },
        )
        assertEquals(listOf("Popular now", "Horror", "Comedy", "Action"), rows.take(4).map { it.title })
        assertTrue(rows.take(4).none { it.pending })
        assertTrue(rows.drop(4).all { it.pending && it.tiles.isEmpty() })
        assertEquals(20, rows[0].tiles.size)
        assertEquals("/discover?type=movie&popular=now", rows[0].moreHref)
        assertEquals("/discover?type=movie&popular=alltime&minRating=7&genre=horror", rows[1].moreHref)
        assertEquals("/discover/row?type=movie&row=drama", rows[5].srcHref)

        // Popular now, Horror, Comedy, Action: one TMDB page each, the same requests their "More" grids make.
        assertEquals(listOf(null, "27", "35", "28"), requests.map { it.params["with_genres"] })
        assertTrue(requests.all { it.path == "/discover/movie" && it.params["page"] == "1" })
        // "Popular now" is what's current; genre rows are well-known, well-rated titles from every era.
        assertEquals("popularity.desc", requests.first().params["sort_by"])
        assertTrue(requests.drop(1).all { it.params["sort_by"] == "vote_count.desc" && it.params["vote_average.gte"]?.startsWith("7") == true })

        assertNull(model.bar().sortMenu, "rows have fixed orderings, so no Sort menu")
        assertEquals(SeenToggleView(on = true, href = "/discover?type=movie&hideSeen=0"), model.toggle())
        assertFalse(model.containsAttribute("errorKey"))
    }

    @Test
    fun `given TV then the rows use TMDB's TV genres`() {
        serve()
        val model = ConcurrentModel()

        controller.discover(user, BrowseParams(type = "tv"), null, model)

        assertEquals(
            listOf("popular", "drama", "comedy", "crime", "sci-fi-fantasy", "action-adventure", "mystery", "animation",
                "documentary", "reality", "family", "kids", "war-politics", "western").map { "discover-row-$it" },
            model.rows().map { it.id },
        )
        assertEquals(listOf(null, "18", "35", "80"), requests.map { it.params["with_genres"] })
        assertTrue(requests.all { it.path == "/discover/tv" })
        assertEquals("/discover?type=tv&popular=alltime&minRating=7&genre=sci-fi-fantasy", model.rows()[4].moreHref)
    }

    @Test
    fun `given titles I've seen then rows hide them by default and show them with hideSeen=0`() {
        serve()
        (101..105).forEach { rateMovie(it) }

        val hidden = ConcurrentModel().also { controller.discover(user, BrowseParams(), null, it) }.rows()[0]
        assertEquals(15, hidden.tiles.size)
        assertTrue(hidden.tiles.none { it.tmdbId in 101..105 })

        val shown = ConcurrentModel().also { controller.discover(user, BrowseParams(hideSeen = "0"), null, it) }.rows()[0]
        assertEquals(20, shown.tiles.size)
        assertEquals("8.0", shown.tiles.first { it.tmdbId == 101 }.userScore)
    }

    @Test
    fun `given hideSeen=0 then the toggle, row links, pending rows and bar links all carry it`() {
        serve()
        val model = ConcurrentModel()

        controller.discover(user, BrowseParams(type = "tv", hideSeen = "0"), null, model)

        assertEquals(true, model.getAttribute("landing"), "showing seen titles is a view setting, not a filter")
        assertEquals(SeenToggleView(on = false, href = "/discover?type=tv"), model.toggle())
        val rows = model.rows()
        assertTrue(rows.all { it.moreHref.endsWith("&hideSeen=0") }, rows.map { it.moreHref }.toString())
        assertEquals("/discover/row?type=tv&row=reality&hideSeen=0", rows.first { it.id == "discover-row-reality" }.srcHref)
        val bar = model.bar()
        assertEquals("/discover?type=movie&hideSeen=0", bar.typeLinks[0].href)
        assertEquals("/discover?type=tv&genre=crime&hideSeen=0", bar.menus.first { it.id == "genre" }.sections[0].links.first { it.label == "Crime" }.href)
        assertTrue(bar.menus.flatMap { m -> m.sections.flatMap { it.links } }.all { it.href.contains("hideSeen=0") })
    }

    @Test
    fun `given one row fails then the landing drops it and keeps the rest`() {
        serve(failGenres = setOf("27"))
        val model = ConcurrentModel()

        controller.discover(user, BrowseParams(), null, model)

        val ids = model.rows().map { it.id }
        assertFalse("discover-row-horror" in ids)
        assertEquals(listOf("discover-row-popular", "discover-row-comedy", "discover-row-action", "discover-row-animation"), ids.take(4))
        assertFalse(model.containsAttribute("errorKey"))
    }

    @Test
    fun `given every row fails then the landing shows the TMDB message instead of rows`() {
        whenever(client.page(any())).thenReturn(TmdbBrowseError.Failed("IOException").left())
        val model = ConcurrentModel()

        val view = controller.discover(user, BrowseParams(), null, model)

        assertEquals("discover", view)
        assertEquals(emptyList<Any>(), model.rows())
        assertEquals("browse.error.unreachable", model.getAttribute("errorKey"))
        assertEquals("/discover?type=movie", model.getAttribute("retryHref"))

        whenever(client.page(any())).thenReturn(TmdbBrowseError.ApiKeyMissing.left())
        controller.discover(user, BrowseParams(), null, model)
        assertEquals("browse.error.not.configured", model.getAttribute("errorKey"))

        whenever(client.page(any())).thenReturn(TmdbBrowseError.ApiKeyRejected.left())
        controller.discover(user, BrowseParams(), null, model)
        assertEquals("discover.error.bad.key", model.getAttribute("errorKey"))
    }

    @Test
    fun `given bad or unusable parameters then ignores them and lands on the rows`() {
        serve()
        val model = ConcurrentModel()

        val view = controller.discover(
            user,
            BrowseParams(type = "tv", decade = "1999s", year = "upcoming", minRating = "11", genre = "horror", runtime = "long", sort = "random", page = "-3"),
            null,
            model,
        )

        assertEquals("discover", view)
        assertEquals(true, model.getAttribute("landing"))
        assertTrue(model.bar().chips.isEmpty())
        assertTrue(requests.all { it.path == "/discover/tv" && it.params["page"] == "1" })
        assertTrue(requests.none { it.params.containsKey("first_air_date.gte") })
        // The bad minRating=11 is ignored: only the genre rows' own 7+ floor is sent, and "Popular now" has none.
        assertTrue(requests.none { it.params["vote_average.gte"]?.startsWith("11") == true })
        assertNull(requests.first().params["vote_average.gte"])
        assertEquals("popularity.desc", requests.first().params["sort_by"])
    }

    // --- Grid ---

    @Test
    fun `given filters then renders the grid with the bar, tiles and a load more link`() {
        serve()
        val model = ConcurrentModel()

        val view = controller.discover(user, BrowseParams(type = "tv", genre = "drama", sort = "rating"), null, model)

        assertEquals("discover", view)
        assertEquals(false, model.getAttribute("landing"))
        val bar = model.bar()
        assertEquals("tv", bar.type)
        assertEquals(listOf("Drama"), bar.chips.map { it.label })
        assertTrue(bar.sortMenu != null)
        val tiles = model.tiles()
        assertEquals(20, tiles.size)
        assertEquals("/tv/101", tiles.first().href)
        assertEquals("/discover?type=tv&genre=drama&sort=rating&page=2", model.getAttribute("nextHref"))
        assertNull(model.getAttribute("firstPageHref"))
        assertEquals("/discover/tv", requests.single().path)
        assertEquals("18", requests.single().params["with_genres"])
        assertEquals(SeenToggleView(on = true, href = "/discover?type=tv&genre=drama&sort=rating&hideSeen=0"), model.toggle())
    }

    @Test
    fun `given the grid with hideSeen=0 then seen titles stay and Load more, the toggle and the chips keep the setting`() {
        serve()
        rateMovie(101)
        val model = ConcurrentModel()

        controller.discover(user, BrowseParams(genre = "horror", hideSeen = "0", page = "1"), null, model)

        assertTrue(model.tiles().any { it.tmdbId == 101 })
        assertEquals("/discover?type=movie&genre=horror&hideSeen=0&page=2", model.getAttribute("nextHref"))
        assertEquals(SeenToggleView(on = false, href = "/discover?type=movie&genre=horror"), model.toggle())
        assertEquals("/discover?type=movie&hideSeen=0", model.bar().chips.single().removeHref)

        val hiding = ConcurrentModel().also { controller.discover(user, BrowseParams(genre = "horror"), null, it) }
        assertTrue(hiding.tiles().none { it.tmdbId == 101 })
    }

    @Test
    fun `given a later page then it is the grid and links back to the first page`() {
        serve()
        val model = ConcurrentModel()

        controller.discover(user, BrowseParams(page = "3"), null, model)

        assertEquals(false, model.getAttribute("landing"))
        assertEquals("/discover?type=movie", model.getAttribute("firstPageHref"))
        assertEquals("/discover?type=movie&page=4", model.getAttribute("nextHref"))
        assertEquals("3", requests.single().params["page"])
    }

    @Test
    fun `given partial then returns only the results fragment and skips the bar`() {
        serve()
        val model = ConcurrentModel()

        val view = controller.discover(user, BrowseParams(page = "2"), "1", model)

        assertEquals("discover :: results", view)
        assertFalse(model.containsAttribute("browseBar"))
        assertTrue(model.containsAttribute("tiles"))
    }

    @Test
    fun `given TMDB fails on the grid then shows an inline message instead of an error page`() {
        whenever(client.page(any())).thenReturn(TmdbBrowseError.Failed("IOException").left())
        val model = ConcurrentModel()

        val view = controller.discover(user, BrowseParams(genre = "horror"), null, model)

        assertEquals("discover", view)
        assertEquals("browse.error.unreachable", model.getAttribute("errorKey"))
        assertEquals("/discover?type=movie&genre=horror", model.getAttribute("retryHref"))
        assertEquals(emptyList<Any>(), model.getAttribute("tiles"))
        assertTrue(model.containsAttribute("browseBar"))
    }

    @Test
    fun `given the user's metadata language then queries TMDB in it and uses its region for services`() {
        serve()
        val spanish = UserFactory.aUser(id = 2, metadataLang = Lang.es)

        controller.discover(spanish, BrowseParams(provider = "8"), null, ConcurrentModel())

        assertEquals("es", requests.single().params["language"])
        assertEquals("ES", requests.single().params["watch_region"])
        assertEquals(MediaType.movie, requests.single().type)
    }

    // --- /discover/row ---

    @Test
    fun `given a row id then returns that row loaded, carrying the seen setting`() {
        serve()
        rateMovie(101)
        library.statuses.save(MediaStatus(user.id!!, MediaType.movie, library.movies.save(MovieFactory.aMovie(tmdbId = 102, originalTitle = "M")).id!!.value, WatchStatus.WATCHED, 1L))
        val model = ConcurrentModel()

        val view = controller.row(user, BrowseParams(type = "movie"), "mystery", model)

        assertEquals("fragments/discover-rows :: lazyRow", view)
        val row = model.getAttribute("row") as DiscoverRowView
        assertEquals("discover-row-mystery", row.id)
        assertEquals("Mystery", row.title)
        assertFalse(row.pending)
        assertEquals(18, row.tiles.size)
        assertEquals("/discover?type=movie&popular=alltime&minRating=7&genre=mystery", row.moreHref)
        assertEquals("9648", requests.single().params["with_genres"])

        val shown = ConcurrentModel().also { controller.row(user, BrowseParams(type = "movie", hideSeen = "0"), "mystery", it) }
        assertEquals(20, (shown.getAttribute("row") as DiscoverRowView).tiles.size)
        assertEquals("/discover?type=movie&popular=alltime&minRating=7&genre=mystery&hideSeen=0", (shown.getAttribute("row") as DiscoverRowView).moreHref)
    }

    @Test
    fun `given an unknown row, another type's genre, or a TMDB failure then the row is empty`() {
        serve(failGenres = setOf("99"))

        listOf(
            BrowseParams(type = "movie") to "nope",
            BrowseParams(type = "tv") to "horror",
            BrowseParams(type = "movie") to null,
            BrowseParams(type = "movie") to "documentary",
        ).forEach { (params, row) ->
            val model = ConcurrentModel()
            assertEquals("fragments/discover-rows :: lazyRow", controller.row(user, params, row, model))
            assertNull(model.getAttribute("row"), "$params $row")
        }
    }
}
