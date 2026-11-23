package org.ratelog.security

import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.movie.rating.delete.DeleteRatingController
import org.ratelog.movie.rating.delete.DeleteRatingHandler
import org.ratelog.movie.togglefollow.ToggleMovieFollowController
import org.ratelog.movie.togglefollow.ToggleMovieFollowHandler
import org.ratelog.test.UserFactory
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRatingController
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRatingHandler
import org.ratelog.tvshow.togglefollow.ToggleTvFollowController
import org.ratelog.tvshow.togglefollow.ToggleTvFollowHandler
import org.ratelog.watchlist.setstatus.SetWatchStatusController
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.ratelog.watchlist.setstatus.SetWatchStatusResult
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap

/** Fork: redirects "back" to the Referer only ever go to a same-site path (BackPath), never another host. */
class RefererRedirectsTest {

    private val user = UserFactory.aUser(id = 1)

    private fun from(referer: String?) = MockHttpServletRequest("POST", "/x").apply { referer?.let { addHeader("Referer", it) } }

    private val movieFollow = ToggleMovieFollowController(mock<ToggleMovieFollowHandler>().also { whenever(it.handle(any())).thenReturn(Unit.right()) })
    private val tvFollow = ToggleTvFollowController(mock<ToggleTvFollowHandler>().also { whenever(it.handle(any())).thenReturn(Unit.right()) })
    private val deleteRating = DeleteRatingController(mock<DeleteRatingHandler>().also { whenever(it.handle(any())).thenReturn(Unit.right()) })
    private val deleteSeason = DeleteSeasonRatingController(mock<DeleteSeasonRatingHandler>().also { whenever(it.handle(any())).thenReturn(Unit.right()) })
    private val setStatus = SetWatchStatusController(mock<SetWatchStatusHandler>().also { whenever(it.handle(any())).thenReturn(SetWatchStatusResult(null, null).right()) })

    @Test
    fun `follow buttons go back to the page they were on, as a local path`() {
        assertEquals("redirect:/movie/603?tab=info", movieFollow.toggleMovieFollow(user, 1, from("https://ratelog.example/movie/603?tab=info")))
        assertEquals("redirect:/tv/1396", tvFollow.toggleTvFollow(user, 1, from("/tv/1396")))
    }

    @Test
    fun `a Referer on another site or a protocol-relative one never leaves the app`() {
        listOf("https://elsewhere.example/", "//elsewhere.example/x", "/\\elsewhere.example", "javascript:alert(1)", "", null).forEach { referer ->
            val target = movieFollow.toggleMovieFollow(user, 1, from(referer))
            assertTrue(target.startsWith("redirect:/") && !target.startsWith("redirect://") && !target.contains("elsewhere"), "$referer -> $target")
        }
        // Only the path of a full or protocol-relative URL is kept.
        assertEquals("redirect:/tv/1", tvFollow.toggleTvFollow(user, 1, from("//elsewhere.example/tv/1")))
        assertEquals("redirect:/", tvFollow.toggleTvFollow(user, 1, from("javascript:alert(1)")))
    }

    @Test
    fun `deleting a rating returns to its title page, else the ratings list`() {
        assertEquals("redirect:/movie/603", deleteRating.deleteRating(user, 1, RedirectAttributesModelMap(), from("http://localhost:8185/movie/603")))
        assertEquals("redirect:/movie/603", deleteRating.deleteRating(user, 1, RedirectAttributesModelMap(), from("https://elsewhere.example/movie/603")))
        assertEquals("redirect:/movies", deleteRating.deleteRating(user, 1, RedirectAttributesModelMap(), from("https://elsewhere.example/x?u=/movie/603")))
        assertEquals("redirect:/movies", deleteRating.deleteRating(user, 1, RedirectAttributesModelMap(), from("/ratings")))
        assertEquals("redirect:/movies", deleteRating.deleteRating(user, 1, RedirectAttributesModelMap(), from(null)))

        assertEquals("redirect:/tv/1396", deleteSeason.deleteRating(user, 1, 1, RedirectAttributesModelMap(), from("http://localhost/tv/1396")))
        assertEquals("redirect:/tvshows", deleteSeason.deleteRating(user, 1, 1, RedirectAttributesModelMap(), from("https://elsewhere.example/?next=/tv/1")))
    }

    @Test
    fun `the status form goes back to a local path, else the watchlist`() {
        assertEquals("redirect:/movie/603", setStatus.setStatus(user, MediaType.movie.name, 1, "watching", from("https://ratelog.example/movie/603"), RedirectAttributesModelMap()))
        assertEquals("redirect:/x", setStatus.setStatus(user, MediaType.movie.name, 1, "watching", from("//elsewhere.example/x"), RedirectAttributesModelMap()), "only the path is kept")
        assertEquals("redirect:/watchlist", setStatus.setStatus(user, MediaType.movie.name, 1, "watching", from("javascript:alert(1)"), RedirectAttributesModelMap()))
        assertEquals("redirect:/watchlist", setStatus.setStatus(user, MediaType.movie.name, 1, "watching", from(null), RedirectAttributesModelMap()))
    }
}
