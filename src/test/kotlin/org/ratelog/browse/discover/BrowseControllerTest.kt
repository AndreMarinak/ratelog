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
import org.ratelog.test.InMemoryBrowseLibraryRepository
import org.ratelog.test.MutableClock
import org.ratelog.test.UserFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.ui.ConcurrentModel
import java.time.LocalDate
import java.util.Locale

class BrowseControllerTest {

    private val client: TmdbBrowseClient = mock()
    private val clock = MutableClock()
    private val controller = BrowseController(
        BrowseHandler(client, InMemoryBrowseLibraryRepository(), clock),
        BrowseBarViews(BrowseBarViewsTest.testMessageSource(), client, clock),
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

    private fun serve(totalPages: Int = 4) {
        whenever(client.page(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<TmdbListRequest>(0)
            requests += request
            val page = request.params.getValue("page").toInt()
            val items = (1..20).map { i ->
                BrowseTitle(page * 100 + i, request.type, "Title $i", LocalDate.parse("2015-01-01"), null, 7.1, 300, emptyList(), "en")
            }
            BrowseTitlePage(items, page, totalPages, totalPages * 20).right()
        }
    }

    @Test
    fun `given filters then renders the page with the bar, tiles and a load more link`() {
        serve()
        val model = ConcurrentModel()

        val view = controller.browse(user, BrowseParams(type = "tv", genre = "drama", sort = "rating"), null, model)

        assertEquals("browse", view)
        val bar = model.getAttribute("browseBar") as BrowseBarView
        assertEquals("tv", bar.type)
        assertEquals(listOf("Drama"), bar.chips.map { it.label })
        @Suppress("UNCHECKED_CAST")
        val tiles = model.getAttribute("tiles") as List<BrowseTileView>
        assertEquals(20, tiles.size)
        assertEquals("/tv/101", tiles.first().href)
        assertEquals("/browse?type=tv&genre=drama&sort=rating&page=2", model.getAttribute("nextHref"))
        assertNull(model.getAttribute("firstPageHref"))
        assertEquals("/discover/tv", requests.single().path)
        assertEquals("18", requests.single().params["with_genres"])
    }

    @Test
    fun `given bad or unusable parameters then ignores them instead of failing`() {
        serve()
        val model = ConcurrentModel()

        val view = controller.browse(
            user,
            BrowseParams(type = "tv", decade = "1999s", year = "upcoming", minRating = "11", genre = "horror", runtime = "long", sort = "random", page = "-3"),
            null,
            model,
        )

        assertEquals("browse", view)
        val bar = model.getAttribute("browseBar") as BrowseBarView
        assertTrue(bar.chips.isEmpty())
        assertNull(requests.single().params["first_air_date.gte"])
        assertNull(requests.single().params["with_genres"])
        assertNull(requests.single().params["vote_average.gte"])
        assertEquals("1", requests.single().params["page"])
        assertEquals("popularity.desc", requests.single().params["sort_by"])
    }

    @Test
    fun `given a later page then links back to the first page`() {
        serve()
        val model = ConcurrentModel()

        controller.browse(user, BrowseParams(page = "3"), null, model)

        assertEquals("/browse?type=movie", model.getAttribute("firstPageHref"))
        assertEquals("/browse?type=movie&page=4", model.getAttribute("nextHref"))
        assertEquals("3", requests.single().params["page"])
    }

    @Test
    fun `given partial then returns only the results fragment and skips the bar`() {
        serve()
        val model = ConcurrentModel()

        val view = controller.browse(user, BrowseParams(page = "2"), "1", model)

        assertEquals("browse :: results", view)
        assertFalse(model.containsAttribute("browseBar"))
        assertTrue(model.containsAttribute("tiles"))
    }

    @Test
    fun `given TMDB fails then shows an inline message instead of an error page`() {
        whenever(client.page(any())).thenReturn(TmdbBrowseError.Failed("IOException").left())
        val model = ConcurrentModel()

        val view = controller.browse(user, BrowseParams(genre = "horror"), null, model)

        assertEquals("browse", view)
        assertEquals("browse.error.unreachable", model.getAttribute("errorKey"))
        assertEquals("/browse?type=movie&genre=horror", model.getAttribute("retryHref"))
        assertEquals(emptyList<Any>(), model.getAttribute("tiles"))
        assertTrue(model.containsAttribute("browseBar"))

        whenever(client.page(any())).thenReturn(TmdbBrowseError.ApiKeyMissing.left())
        controller.browse(user, BrowseParams(), null, model)
        assertEquals("browse.error.not.configured", model.getAttribute("errorKey"))

        whenever(client.page(any())).thenReturn(TmdbBrowseError.ApiKeyRejected.left())
        controller.browse(user, BrowseParams(), null, model)
        assertEquals("browse.error.bad.key", model.getAttribute("errorKey"))
    }

    @Test
    fun `given the user's metadata language then queries TMDB in it and uses its region for services`() {
        serve()
        val spanish = UserFactory.aUser(id = 2, metadataLang = Lang.es)

        controller.browse(spanish, BrowseParams(provider = "8"), null, ConcurrentModel())

        assertEquals("es", requests.single().params["language"])
        assertEquals("ES", requests.single().params["watch_region"])
        assertEquals(MediaType.movie, requests.single().type)
    }
}
