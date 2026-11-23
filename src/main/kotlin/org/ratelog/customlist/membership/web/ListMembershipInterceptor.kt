package org.ratelog.customlist.membership.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.membership.ListMembershipRepository
import org.ratelog.customlist.membership.TitleInList
import org.ratelog.movie.detail.MovieDetailResponse
import org.ratelog.tvshow.detail.TvShowDetailResponse
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

/**
 * Adds `listMembership` (a [ListMembershipView]) to the movie and TV detail pages after their controllers ran, so the
 * upstream detail slices stay untouched: the viewer's lists come from the page's own `userLists`, which of them have
 * the title from one query ([ListMembershipRepository]).
 */
class ListMembershipInterceptor(
    private val repository: ListMembershipRepository,
    private val currentUser: () -> User?,
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(ListMembershipInterceptor::class.java)

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        if (modelAndView == null || !request.method.equals("GET", ignoreCase = true)) return
        val model = modelAndView.model
        val title = when (modelAndView.viewName) {
            "movie-detail" -> (model["movie"] as? MovieDetailResponse)?.let { Title(MediaType.movie, it.id, it.tmdbId) }
            "tvshow-detail" -> (model["show"] as? TvShowDetailResponse)?.let { Title(MediaType.tvshow, it.id, it.tmdbId) }
            else -> null
        } ?: return
        val userId = currentUser()?.id ?: return
        val lists = (model["userLists"] as? List<*>)?.filterIsInstance<CustomList>() ?: emptyList()
        val inLists: List<TitleInList> = if (lists.isEmpty()) emptyList() else try {
            repository.findListsContaining(userId, title.type, title.mediaId)
        } catch (e: Exception) {
            // The sheet still works without the ticks: adding a title that is already there changes nothing.
            logger.warn("List membership unavailable for {}: {}", request.requestURI, e.javaClass.simpleName)
            emptyList()
        }
        modelAndView.addObject("listMembership", ListMembershipView.of(title.type, title.mediaId, title.tmdbId, lists, inLists))
    }

    private data class Title(val type: MediaType, val mediaId: Long, val tmdbId: Int)
}
