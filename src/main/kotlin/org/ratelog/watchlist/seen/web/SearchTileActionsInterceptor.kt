package org.ratelog.watchlist.seen.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.MediaType
import org.ratelog.home.HomeRepository
import org.ratelog.search.SearchResultItem
import org.ratelog.toLocalDate
import org.ratelog.user.User
import org.ratelog.watchlist.seen.TileActionsView
import org.slf4j.LoggerFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import java.time.Clock
import java.time.LocalDate

/**
 * Adds `resultActions` to the search results page: the viewer's quick actions per result, keyed `movie-603` /
 * `tvshow-1396` (the result's `type` + TMDB id). One marks query per media type, after the upstream search
 * controller ran, so it stays untouched. Later pages (search.js) load the same server-rendered page.
 */
class SearchTileActionsInterceptor(
    private val homeRepository: HomeRepository,
    private val currentUser: () -> User?,
    private val clock: Clock,
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(SearchTileActionsInterceptor::class.java)

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        if (modelAndView == null || modelAndView.viewName != "search") return
        val results = (modelAndView.model["results"] as? List<*>)?.filterIsInstance<SearchResultItem>() ?: return
        val userId = currentUser()?.id ?: return
        try {
            modelAndView.addObject("resultActions", actions(userId, results))
        } catch (e: Exception) {
            logger.warn("Search tile actions unavailable: {}", e.javaClass.simpleName)
        }
    }

    fun actions(userId: User.Id, results: List<SearchResultItem>): Map<String, TileActionsView> {
        val today = LocalDate.now(clock)
        val locale = LocaleContextHolder.getLocale()
        val byType = results.groupBy { typeOf(it) }
        val marks = byType.mapNotNull { (type, items) ->
            type?.let { it to homeRepository.findMarks(userId, it, items.map { item -> item.tmdbId }) }
        }.toMap()
        return results.mapNotNull { item ->
            val type = typeOf(item) ?: return@mapNotNull null
            val mark = marks[type]?.get(item.tmdbId)
            val releaseDate = item.releaseDate?.take(10)?.let { runCatching { it.toLocalDate() }.getOrNull() }
            "${item.type}-${item.tmdbId}" to TileActionsView.of(
                tmdbId = item.tmdbId,
                mediaType = type,
                status = mark?.status,
                rated = mark?.score != null,
                releaseDate = releaseDate,
                today = today,
                locale = locale,
                score = mark?.score,
                onWatchlist = mark?.onWatchlist == true,
            )
        }.toMap()
    }

    private fun typeOf(item: SearchResultItem): MediaType? = MediaType.entries.find { it.name == item.type }
}
