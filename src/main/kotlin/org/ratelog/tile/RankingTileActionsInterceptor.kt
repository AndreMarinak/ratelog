package org.ratelog.tile

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.MediaType
import org.ratelog.home.HomeRepository
import org.ratelog.movie.top.GetTopMoviesResponse
import org.ratelog.tvshow.top.GetTopTvShowsResponse
import org.ratelog.user.User
import org.ratelog.user.UserDetailsService
import org.ratelog.watchlist.seen.TileActionsView
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock
import java.time.LocalDate

/**
 * Adds `rankingActions` to the Ratings tab (`/movies`, `/tvshows`): each ranked title's poster quick actions, keyed by
 * TMDB id, with one marks query for the page, after the upstream ranking controller ran (it stays untouched). Every
 * title there is rated, so ★ shows your overall score (the big number can be one category's when ranking by it).
 * Ranked titles count as released: the ranking carries no release date, and you rated them.
 */
class RankingTileActionsInterceptor(
    private val homeRepository: HomeRepository,
    private val currentUser: () -> User?,
    private val clock: Clock,
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(RankingTileActionsInterceptor::class.java)

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        // Fork (ranking scroll): also the next page's cards alone ("movies :: ranking", `partial=1`).
        val type = when (modelAndView?.viewName?.substringBefore("::")?.trim()) {
            "movies" -> MediaType.movie
            "tvshows" -> MediaType.tvshow
            else -> return
        }
        val tops = (modelAndView.model["tops"] as? List<*>) ?: return
        val tmdbIds = tops.mapNotNull {
            when (it) {
                is GetTopMoviesResponse -> it.tmdbId
                is GetTopTvShowsResponse -> it.tmdbId
                else -> null
            }
        }
        val userId = currentUser()?.id ?: return
        try {
            modelAndView.addObject("rankingActions", actions(userId, type, tmdbIds))
        } catch (e: Exception) {
            logger.warn("Ranking tile actions unavailable: {}", e.javaClass.simpleName)
        }
    }

    fun actions(userId: User.Id, type: MediaType, tmdbIds: List<Int>): Map<Int, TileActionsView> {
        if (tmdbIds.isEmpty()) return emptyMap()
        val today = LocalDate.now(clock)
        val locale = LocaleContextHolder.getLocale()
        val marks = homeRepository.findMarks(userId, type, tmdbIds)
        return tmdbIds.distinct().associateWith { tmdbId ->
            val mark = marks[tmdbId]
            TileActionsView.of(
                tmdbId = tmdbId,
                mediaType = type,
                status = mark?.status,
                rated = true,
                releaseDate = null,
                today = today,
                locale = locale,
                score = mark?.score,
                onWatchlist = mark?.onWatchlist == true,
            )
        }
    }
}

/** Registers [RankingTileActionsInterceptor] on the Ratings tab. */
@Configuration
class TileWebConfig(
    private val homeRepository: HomeRepository,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(RankingTileActionsInterceptor(homeRepository, { UserDetailsService.getCurrentUser() }, Clock.systemDefaultZone()))
            .addPathPatterns("/movies", "/tvshows")
    }
}
