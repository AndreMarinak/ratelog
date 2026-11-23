package org.ratelog.customlist.membership.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.movie.detail.MovieDetailResponse
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryListMembershipRepository
import org.ratelog.test.UserFactory
import org.ratelog.tvshow.detail.TvShowDetailResponse
import org.ratelog.user.User
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.ModelAndView

/** The title pages' `listMembership` and the view's order and counts. */
class ListMembershipInterceptorTest {

    private val lists = InMemoryCustomListRepository()
    private val membership = InMemoryListMembershipRepository(lists)
    private val user = UserFactory.aUser(id = 1)
    private var current: User? = user
    private val interceptor = ListMembershipInterceptor(membership) { current }

    private val cocoId = 42L // local movies.id of Coco (TMDB 354912)

    private fun list(name: String, createdAt: Long, vararg mediaIds: Long, type: MediaType = MediaType.movie, owner: User.Id = user.id!!): CustomList {
        val list = lists.save(CustomList(null, owner, ListName(name), isPublic = false, createdAtEpochMs = createdAt))
        mediaIds.forEachIndexed { index, mediaId -> lists.addItem(CustomListItem(null, list.id!!, mediaId, type, index + 1, createdAt)) }
        return list
    }

    private fun movie(id: Long, tmdbId: Int) = MovieDetailResponse(
        id = id, tmdbId = tmdbId, name = "Coco", overview = null, firstAirYear = 2017, posterPath = null, tmdbVoteAverage = null,
        genres = emptyList(), directing = null, cinematography = null, acting = null, soundtrack = null, screenplay = null,
        score = null, quickRating = false, reviewTitle = null, reviewText = null, isFollowed = false, hasRating = false,
    )

    private fun show(id: Long, tmdbId: Int) = TvShowDetailResponse(
        id = id, tmdbId = tmdbId, name = "Bluey", overview = null, firstAirYear = 2018, posterPath = null, tmdbVoteAverage = null,
        genres = emptyList(), seasons = emptyList(), overallScore = null, isFollowed = false, hasRating = false,
    )

    /** What the upstream detail controllers put in the model: the viewer's lists, newest first. */
    private fun page(view: String, title: Any, key: String) =
        ModelAndView(view, mapOf(key to title, "userLists" to lists.findByUserId(user.id!!)))

    private fun handle(mav: ModelAndView?, method: String = "GET") =
        interceptor.postHandle(MockHttpServletRequest(method, "/movie/354912"), MockHttpServletResponse(), Any(), mav)

    @Test
    fun `a movie page gets every list, the ones with the title first and ticked with its rank`() {
        list("Watch with kids", 1_000)
        list("Best Disney Movies", 2_000, 10, 11, cocoId, 12)
        list("Pixar ranked", 3_000, cocoId)
        list("Newest, without it", 4_000, 10)
        list("Someone else's", 5_000, cocoId, owner = User.Id(2))
        val mav = page("movie-detail", movie(cocoId, 354912), "movie")

        handle(mav)

        val m = mav.model["listMembership"] as ListMembershipView
        assertEquals("movie", m.mediaType)
        assertEquals(cocoId, m.mediaId)
        assertEquals("/movie/354912", m.back)
        assertEquals(2, m.count)
        assertTrue(m.inAny)
        assertEquals(
            listOf("Pixar ranked", "Best Disney Movies", "Newest, without it", "Watch with kids"),
            m.rows.map { it.name },
            "lists with the title first; each group newest first; never someone else's",
        )
        assertEquals(listOf(true, true, false, false), m.rows.map { it.inList })
        assertEquals(listOf(1, 3, null, null), m.rows.map { it.rank })
        assertEquals(listOf(1, 2, 0, 3), m.rows.map { it.order }, "the usual order, for re-sorting in the browser")
        assertEquals(1, membership.queries.get(), "one query per page")
    }

    @Test
    fun `a show page matches shows only, and goes back to the TV page`() {
        list("Mixed", 1_000, cocoId) // movie 42
        list("Shows", 2_000, cocoId, type = MediaType.tvshow) // show 42
        val mav = page("tvshow-detail", show(cocoId, 82728), "show")

        handle(mav)

        val m = mav.model["listMembership"] as ListMembershipView
        assertEquals("tvshow", m.mediaType)
        assertEquals("/tv/82728", m.back)
        assertEquals(listOf("Shows" to true, "Mixed" to false), m.rows.map { it.name to it.inList })
    }

    @Test
    fun `no lists means no query and an empty sheet`() {
        val mav = page("movie-detail", movie(cocoId, 354912), "movie")

        handle(mav)

        val m = mav.model["listMembership"] as ListMembershipView
        assertTrue(m.rows.isEmpty())
        assertEquals(0, m.count)
        assertFalse(m.inAny)
        assertEquals(0, membership.queries.get())
    }

    @Test
    fun `if the query fails the sheet still lists everything, unticked`() {
        list("Best Disney Movies", 2_000, cocoId)
        membership.failure = IllegalStateException("db down")
        val mav = page("movie-detail", movie(cocoId, 354912), "movie")

        handle(mav)

        val m = mav.model["listMembership"] as ListMembershipView
        assertEquals(listOf("Best Disney Movies" to false), m.rows.map { it.name to it.inList })
    }

    @Test
    fun `nothing is added for failed pages, other views, posts or without a user`() {
        list("Best Disney Movies", 2_000, cocoId)

        val failed = ModelAndView("movie-detail", mapOf("error" to "error.load.movie"))
        handle(failed)
        assertNull(failed.model["listMembership"])

        val other = page("watchlist", movie(cocoId, 354912), "movie")
        handle(other)
        assertNull(other.model["listMembership"])

        val post = page("movie-detail", movie(cocoId, 354912), "movie")
        handle(post, method = "POST")
        assertNull(post.model["listMembership"])

        current = null
        val anonymous = page("movie-detail", movie(cocoId, 354912), "movie")
        handle(anonymous)
        assertNull(anonymous.model["listMembership"])

        handle(null)
        assertEquals(0, membership.queries.get())
    }
}
