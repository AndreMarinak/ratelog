package org.ratelog.customlist.saved.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.MediaType
import org.ratelog.customlist.saved.intitle.OfficialTitleListsRepository
import org.ratelog.customlist.saved.intitle.TitleOfficialListsView
import org.ratelog.movie.detail.MovieDetailResponse
import org.ratelog.tvshow.detail.TvShowDetailResponse
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

/**
 * Adds `officialTitleLists` (a [TitleOfficialListsView]) after the controller ran, so the upstream detail slices and the
 * tile sheet controller stay untouched:
 *  - the movie and TV pages: the "In official lists" chips and the add-to-list sheet's "Also in your saved lists" line;
 *  - GET /tile/lists (a poster tile's add-to-list sheet): the same sheet line.
 * One query either way. A failure leaves the line out; the page works without it.
 */
class OfficialTitleListsInterceptor(
    private val repository: OfficialTitleListsRepository,
    private val currentUser: () -> User?,
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(OfficialTitleListsInterceptor::class.java)

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        if (modelAndView == null || !request.method.equals("GET", ignoreCase = true) || response.status >= 400) return
        val model = modelAndView.model
        val title: Pair<MediaType, Long> = when (modelAndView.viewName) {
            "movie-detail" -> (model["movie"] as? MovieDetailResponse)?.let { MediaType.movie to it.id }
            "tvshow-detail" -> (model["show"] as? TvShowDetailResponse)?.let { MediaType.tvshow to it.id }
            TILE_SHEET_VIEW -> {
                val type = MediaType.entries.find { it.name == model["sheetMediaType"] }
                val id = model["sheetMediaId"] as? Long
                if (type != null && id != null) type to id else null
            }
            else -> null
        } ?: return
        val viewerId = currentUser()?.id ?: return
        val lists = try {
            repository.findContaining(viewerId, title.first, title.second)
        } catch (e: Exception) {
            logger.warn("Official lists unavailable for {}: {}", request.requestURI, e.javaClass.simpleName)
            return
        }
        modelAndView.addObject(MODEL_KEY, TitleOfficialListsView.of(lists, viewerId))
    }

    companion object {
        const val MODEL_KEY = "officialTitleLists"
        const val TILE_SHEET_VIEW = "fragments/tile-rate :: listSheet"
    }
}
