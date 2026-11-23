package org.ratelog.backnav.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.backnav.BackNavLabel
import org.ratelog.backnav.BackNavLink
import org.ratelog.backnav.BackNavList
import org.ratelog.backnav.BackNavNames
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.getlistdetail.ListDetail
import org.ratelog.movie.detail.MovieDetailResponse
import org.ratelog.test.UserFactory
import org.ratelog.tvshow.detail.TvShowDetailResponse
import org.ratelog.user.User
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.ModelAndView

/** The back-link model every page gets: its own name, and on title pages the link from the Referer. */
class BackNavInterceptorTest {

    private val user = UserFactory.aUser(id = 1)
    private var current: User? = user
    private val names = object : BackNavNames {
        override fun list(listId: Long) = if (listId == 7L) BackNavList(user.id!!, false, "Best Disney Movies") else null
        override fun username(userId: Long) = if (userId == 2L) "marisol" else null
    }
    private val interceptor = BackNavInterceptor(names) { current }

    private fun movie() = MovieDetailResponse(
        id = 42, tmdbId = 10193, name = "Toy Story 3", overview = null, firstAirYear = 2010, posterPath = null, tmdbVoteAverage = null,
        genres = emptyList(), directing = null, cinematography = null, acting = null, soundtrack = null, screenplay = null,
        score = null, quickRating = false, reviewTitle = null, reviewText = null, isFollowed = false, hasRating = false,
    )

    private fun show() = TvShowDetailResponse(
        id = 5, tmdbId = 82728, name = "Bluey", overview = null, firstAirYear = 2018, posterPath = null, tmdbVoteAverage = null,
        genres = emptyList(), seasons = emptyList(), overallScore = null, isFollowed = false, hasRating = false,
    )

    private fun handle(
        mav: ModelAndView?,
        uri: String,
        query: String? = null,
        referer: String? = null,
        method: String = "GET",
    ): ModelAndView? {
        val request = MockHttpServletRequest(method, uri).apply {
            serverName = "ratelog.example"
            queryString = query
            referer?.let { addHeader("Referer", it) }
        }
        interceptor.postHandle(request, MockHttpServletResponse(), Any(), mav)
        return mav
    }

    @Test
    fun `a movie page opened from a Discover grid links back to that grid`() {
        val mav = handle(ModelAndView("movie-detail", mapOf("movie" to movie())), "/movie/10193",
            referer = "https://ratelog.example/discover?genre=16&type=movie")!!

        assertEquals(BackNavLink("/discover?genre=16&type=movie", BackNavLabel.DISCOVER, "Toy Story 3"), mav.model["backNav"])
        assertNull(mav.model["backNavPage"], "a title page is never an origin")
    }

    @Test
    fun `a show page opened from one of your lists links back to it by name`() {
        val mav = handle(ModelAndView("tvshow-detail", mapOf("show" to show())), "/tv/82728",
            referer = "https://ratelog.example/list/7")!!

        assertEquals(BackNavLink("/list/7", BackNavLabel.list("Best Disney Movies"), "Bluey"), mav.model["backNav"])
    }

    @Test
    fun `opened directly, a title links Home`() {
        val mav = handle(ModelAndView("movie-detail", mapOf("movie" to movie())), "/movie/10193")!!

        assertEquals(BackNavLink("/", BackNavLabel.HOME, "Toy Story 3"), mav.model["backNav"])
    }

    @Test
    fun `opened from another title, the link is Home until the script fills it in`() {
        val mav = handle(ModelAndView("movie-detail", mapOf("movie" to movie())), "/movie/10193",
            referer = "https://ratelog.example/movie/863")!!

        val back = mav.model["backNav"] as BackNavLink
        assertTrue(back.pending)
        assertEquals("/", back.href)
    }

    @Test
    fun `a title that failed to load gets no back link`() {
        val mav = handle(ModelAndView("movie-detail", mapOf("error" to "error.load.movie")), "/movie/10193")!!
        assertFalse(mav.model.containsKey("backNav"))
    }

    @Test
    fun `a failing lookup falls back to Home instead of failing the page`() {
        val broken = BackNavInterceptor(object : BackNavNames {
            override fun list(listId: Long): BackNavList? = error("database down")
            override fun username(userId: Long): String? = error("database down")
        }) { current }
        val mav = ModelAndView("movie-detail", mapOf("movie" to movie()))
        val request = MockHttpServletRequest("GET", "/movie/10193").apply {
            serverName = "ratelog.example"
            addHeader("Referer", "https://ratelog.example/list/7")
        }
        broken.postHandle(request, MockHttpServletResponse(), Any(), mav)

        assertEquals(BackNavLink("/", BackNavLabel.HOME, "Toy Story 3"), mav.model["backNav"])
    }

    @Test
    fun `pages name themselves for the title pages opened from them`() {
        assertEquals(BackNavLabel.DISCOVER, handle(ModelAndView("discover"), "/discover", "genre=27")!!.model["backNavPage"])
        assertEquals(BackNavLabel.HOME, handle(ModelAndView("search"), "/")!!.model["backNavPage"])
        assertEquals(BackNavLabel.SEARCH, handle(ModelAndView("search"), "/", "q=toy")!!.model["backNavPage"])
        assertEquals(BackNavLabel.WATCHLIST, handle(ModelAndView("watchlist"), "/watchlist", "tab=seen")!!.model["backNavPage"])
        assertEquals(BackNavLabel.RATINGS, handle(ModelAndView("movies"), "/movies")!!.model["backNavPage"])
        assertEquals(BackNavLabel.OFFICIAL_LISTS, handle(ModelAndView("lists-official"), "/lists/official")!!.model["backNavPage"])
    }

    @Test
    fun `a list page is named after the list, a profile after its owner`() {
        // What the upstream list controller puts in the model (GetListDetailHandler's ListDetail).
        val list = ListDetail(CustomList.Id(7), user.id!!, "Best Disney Movies", isPublic = false, createdAtEpochMs = 0, items = emptyList())
        assertEquals(BackNavLabel.list("Best Disney Movies"),
            handle(ModelAndView("list-detail", mapOf("list" to list)), "/list/7")!!.model["backNavPage"])
        assertNull(handle(ModelAndView("list-detail", mapOf("error" to "list.not.found")), "/list/8")!!.model["backNavPage"])

        assertEquals(BackNavLabel.profile("marisol"),
            handle(ModelAndView("profile", mapOf("username" to "marisol", "isLoggedUser" to false)), "/profile/2")!!.model["backNavPage"])
        assertEquals(BackNavLabel.OWN_PROFILE,
            handle(ModelAndView("profile", mapOf("username" to "admin", "isLoggedUser" to true)), "/profile/1")!!.model["backNavPage"])
    }

    @Test
    fun `settings, errors, redirects, fragments and posts get nothing`() {
        listOf(
            handle(ModelAndView("api-settings"), "/settings/api"),
            handle(ModelAndView("not-found"), "/nope"),
            handle(ModelAndView("tmdb-error"), "/discover"),
            handle(ModelAndView("redirect:/discover"), "/discover"),
            handle(ModelAndView("fragments/discover-rows :: lazyRow"), "/discover/row"),
            handle(ModelAndView("discover"), "/discover", method = "POST"),
        ).forEach { mav ->
            assertFalse(mav!!.model.containsKey("backNavPage"), mav.viewName)
            assertFalse(mav.model.containsKey("backNav"), mav.viewName)
        }
        assertNull(handle(null, "/discover"))
    }
}
