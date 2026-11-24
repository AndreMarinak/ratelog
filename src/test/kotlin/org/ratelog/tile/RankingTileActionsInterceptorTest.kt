package org.ratelog.tile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.home.TitleMark
import org.ratelog.movie.top.GetTopMoviesResponse
import org.ratelog.test.InMemoryHomeRepository
import org.ratelog.test.MutableClock
import org.ratelog.test.UserFactory
import org.ratelog.tvshow.top.GetTopTvShowsResponse
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.TileActionsView
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.ModelAndView

class RankingTileActionsInterceptorTest {

    private val home = InMemoryHomeRepository()
    private val user = UserFactory.aUser(id = 1)
    private val interceptor = RankingTileActionsInterceptor(home, { user }, MutableClock())

    private fun movie(rank: Int, tmdbId: Int, score: Double) = GetTopMoviesResponse(rank, tmdbId, "Movie $tmdbId", 1999, null, score, emptyList())
    private fun show(rank: Int, tmdbId: Int, score: Double) = GetTopTvShowsResponse(rank, tmdbId, "Show $tmdbId", 2008, null, score, emptyList())

    private fun handle(view: String, tops: List<Any>): ModelAndView {
        val mav = ModelAndView(view, mapOf("tops" to tops))
        interceptor.postHandle(MockHttpServletRequest(), MockHttpServletResponse(), Any(), mav)
        return mav
    }

    @Suppress("UNCHECKED_CAST")
    private fun actions(mav: ModelAndView) = mav.model["rankingActions"] as Map<Int, TileActionsView>

    @Test
    fun `every ranked movie gets the quick actions with your overall score, in one query`() {
        home.setMark(user.id!!, MediaType.movie, 603, TitleMark(WatchStatus.WATCHED, 8.6))
        home.setMark(user.id!!, MediaType.movie, 949, TitleMark(null, 7.0))

        val mav = handle("movies", listOf(movie(1, 603, 9.5), movie(2, 949, 7.0)))
        val a = actions(mav)

        assertEquals(1, home.markQueries.get())
        assertEquals(setOf(603, 949), a.keys)
        assertEquals(8.6, a.getValue(603).score, "the overall score, not the ranked category's")
        assertEquals("8.6", a.getValue(603).scoreLabel)
        assertTrue(a.getValue(603).seenLocked)
        assertTrue(a.getValue(603).canRate)
        assertEquals("/movie/603?rate=1", a.getValue(603).rateHref)
        assertNull(a.getValue(949).status)
        assertTrue(a.getValue(949).seen, "rated means seen")
    }

    @Test
    fun `ranked shows get the rating picker on their star`() {
        home.setMark(user.id!!, MediaType.tvshow, 1396, TitleMark(WatchStatus.WATCHING, 9.1))

        val a = actions(handle("tvshows", listOf(show(1, 1396, 9.1))))

        assertEquals("tv", a.getValue(1396).type)
        assertEquals("/tv/1396?rate=1", a.getValue(1396).rateHref)
        assertEquals("tile.rate.on", a.getValue(1396).rateLabelKey)
        assertFalse(a.getValue(1396).seen, "a show you're watching isn't seen yet")
    }

    @Test
    fun `other views, an empty ranking or no user add nothing`() {
        assertNull(handle("search", listOf(movie(1, 603, 9.0))).model["rankingActions"])
        assertEquals(emptyMap<Int, TileActionsView>(), actions(handle("movies", emptyList())))

        val anonymous = RankingTileActionsInterceptor(home, { null }, MutableClock())
        val mav = ModelAndView("movies", mapOf("tops" to listOf(movie(1, 603, 9.0))))
        anonymous.postHandle(MockHttpServletRequest(), MockHttpServletResponse(), Any(), mav)
        assertNull(mav.model["rankingActions"])
    }
}
