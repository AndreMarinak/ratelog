package org.ratelog.customlist.saved

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.official.OfficialListsFixture
import org.ratelog.customlist.saved.intitle.TitleOfficialListsView
import org.ratelog.customlist.saved.web.OfficialTitleListsInterceptor
import org.ratelog.movie.Movie
import org.ratelog.movie.detail.MovieDetailResponse
import org.ratelog.test.InMemoryListOptionsRepository
import org.ratelog.test.InMemoryOfficialTitleListsRepository
import org.ratelog.user.User
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.ModelAndView

/**
 * The title page's "In official lists" chips and the add-to-list sheet's "Also in your saved lists" line: every public
 * official list with the title (one query), saved ones first, ranks only on numbered lists.
 */
class OfficialTitleListsTest {

    private val f = OfficialListsFixture()
    private val options = InMemoryListOptionsRepository()
    private val repository = InMemoryOfficialTitleListsRepository(f.p.lists, f.official, f.saved, options)
    private var viewer: User? = f.andre
    private val interceptor = OfficialTitleListsInterceptor(repository) { viewer }
    private val matrix: Movie = f.p.movie("The Matrix")

    /** An official public list with filler titles and The Matrix at [place] (1-based). */
    private fun officialWith(name: String, place: Int, size: Int = 5, isPublic: Boolean = true, owner: User = f.admin): CustomList {
        val list = f.p.lists.save(CustomList(null, owner.id!!, org.ratelog.ListName(name), isPublic, createdAtEpochMs = 1_000))
        val titles = (1..size).map { if (it == place) matrix else f.p.movie("$name filler $it") }
        f.p.add(list, *titles.toTypedArray())
        f.markOfficial(list)
        return f.p.lists.findById(list.id!!)!!
    }

    private fun saveAt(list: CustomList, ms: Long, user: User = f.andre) = f.saved.save(user.id!!, list.id!!, ms)

    private fun view(): TitleOfficialListsView =
        TitleOfficialListsView.of(repository.findContaining(viewer!!.id!!, MediaType.movie, matrix.id!!.value), viewer!!.id!!)

    private fun moviePage(): ModelAndView = ModelAndView("movie-detail").apply {
        addObject(
            "movie",
            MovieDetailResponse(
                id = matrix.id!!.value, tmdbId = 603, name = "The Matrix", overview = null, firstAirYear = 1999, posterPath = null,
                tmdbVoteAverage = null, genres = emptyList(), directing = null, cinematography = null, acting = null, soundtrack = null,
                screenplay = null, score = null, quickRating = false, reviewTitle = null, reviewText = null, isFollowed = false, hasRating = false,
            ),
        )
    }

    private fun intercept(mav: ModelAndView, method: String = "GET", status: Int = 200): ModelAndView {
        val response = MockHttpServletResponse().apply { this.status = status }
        interceptor.postHandle(MockHttpServletRequest(method, "/movie/603"), response, Any(), mav)
        return mav
    }

    @Test
    fun `every public official list with the title, ranked by place, saved first`() {
        val imdb = officialWith("IMDb Top 250", place = 4)
        officialWith("AFI 100", place = 2)
        val classics = officialWith("Classics", place = 3)
        options.setNumbered(classics.id!!, false)
        officialWith("Drafts", place = 1, isPublic = false)
        f.p.lists.save(CustomList(null, f.admin.id!!, org.ratelog.ListName("Not official"), true, createdAtEpochMs = 1)).also { f.p.add(it, matrix) }
        saveAt(imdb, 5_000)

        val chips = view().chips

        assertEquals(listOf("IMDb Top 250", "AFI 100", "Classics"), chips.map { it.name }, "saved, then by rank, then plain lists")
        assertEquals(listOf(4, 2, null), chips.map { it.rank }, "no rank on a plain list")
        assertEquals(listOf(true, false, false), chips.map { it.saved })
        assertEquals("/list/${imdb.id!!.value}", chips[0].href)
    }

    @Test
    fun `three chips, the rest behind more`() {
        (1..5).forEach { officialWith("List $it", place = it, size = 6) }

        val v = view()

        assertEquals(3, v.shown.size)
        assertEquals(listOf("List 4", "List 5"), v.more.map { it.name })
    }

    @Test
    fun `saved lists newest save first, and the sheet line leaves out lists you own`() {
        val a = officialWith("A", place = 2)
        val b = officialWith("B", place = 1)
        val own = officialWith("Own", place = 1, owner = f.andre)
        saveAt(a, 5_000)
        saveAt(b, 6_000)
        saveAt(own, 7_000)

        val v = view()

        assertEquals(listOf("Own", "B", "A"), v.chips.map { it.name })
        assertTrue(v.chips.first().owned)
        assertEquals(listOf("B", "A"), v.savedNotOwned.map { it.name }, "your own list is a tick in the sheet already")
    }

    @Test
    fun `the movie page gets the view from one query, after the controller`() {
        officialWith("IMDb Top 250", place = 4)

        val mav = intercept(moviePage())

        val v = mav.model[OfficialTitleListsInterceptor.MODEL_KEY] as TitleOfficialListsView
        assertEquals(listOf("IMDb Top 250"), v.chips.map { it.name })
        assertEquals(1, repository.queries.get())
    }

    @Test
    fun `the tile sheet gets it too, from the sheet's own model`() {
        officialWith("IMDb Top 250", place = 4)
        val mav = ModelAndView(OfficialTitleListsInterceptor.TILE_SHEET_VIEW).apply {
            addObject("sheetMediaId", matrix.id!!.value)
            addObject("sheetMediaType", "movie")
        }

        intercept(mav)

        assertTrue(mav.model.containsKey(OfficialTitleListsInterceptor.MODEL_KEY))
    }

    @Test
    fun `nothing for other views, POSTs, errors or without a viewer`() {
        officialWith("IMDb Top 250", place = 4)

        assertFalse(intercept(ModelAndView("list-detail")).model.containsKey(OfficialTitleListsInterceptor.MODEL_KEY))
        assertFalse(intercept(moviePage(), method = "POST").model.containsKey(OfficialTitleListsInterceptor.MODEL_KEY))
        assertFalse(intercept(moviePage(), status = 404).model.containsKey(OfficialTitleListsInterceptor.MODEL_KEY))
        viewer = null
        assertFalse(intercept(moviePage()).model.containsKey(OfficialTitleListsInterceptor.MODEL_KEY))
        assertEquals(0, repository.queries.get())
    }

    @Test
    fun `an empty view when the title is in no official list`() {
        val v = intercept(moviePage()).model[OfficialTitleListsInterceptor.MODEL_KEY] as TitleOfficialListsView
        assertTrue(v.isEmpty)
        assertNull(v.chips.firstOrNull())
    }
}
