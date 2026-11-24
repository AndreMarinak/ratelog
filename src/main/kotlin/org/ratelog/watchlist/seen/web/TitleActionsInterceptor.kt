package org.ratelog.watchlist.seen.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.MediaType
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieRepository
import org.ratelog.movie.detail.MovieDetailResponse
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.tvshow.detail.TvShowDetailResponse
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.TitleActionsView
import org.slf4j.LoggerFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import java.time.Clock
import java.time.LocalDate

/**
 * Adds `titleActions` (a [TitleActionsView]) to the movie and TV detail pages after their controllers ran, so the
 * upstream detail slices stay untouched. It reads the controller's response (id, rating, follow) and `watchStatus`
 * (WatchStatusModelAdvice), plus the stored release date and TMDB status of the title.
 */
class TitleActionsInterceptor(
    private val movieRepository: MovieRepository,
    private val tvShowRepository: TvShowRepository,
    private val clock: Clock,
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(TitleActionsInterceptor::class.java)

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        if (modelAndView == null || !request.method.equals("GET", ignoreCase = true)) return
        val model = modelAndView.model
        val status = model["watchStatus"] as? WatchStatus
        val today = LocalDate.now(clock)
        val locale = LocaleContextHolder.getLocale()
        try {
            val view = when (modelAndView.viewName) {
                "movie-detail" -> (model["movie"] as? MovieDetailResponse)?.let { movie ->
                    val local = movieRepository.findById(Movie.Id(movie.id))
                    TitleActionsView.of(
                        type = MediaType.movie,
                        mediaId = movie.id,
                        tmdbId = movie.tmdbId,
                        status = status,
                        rated = movie.hasRating,
                        releaseDate = local?.releaseDate,
                        tmdbStatus = local?.status?.value,
                        followed = movie.isFollowed,
                        today = today,
                        locale = locale,
                    )
                }

                "tvshow-detail" -> (model["show"] as? TvShowDetailResponse)?.let { show ->
                    val local = tvShowRepository.findById(TvShow.Id(show.id))
                    TitleActionsView.of(
                        type = MediaType.tvshow,
                        mediaId = show.id,
                        tmdbId = show.tmdbId,
                        status = status,
                        // Seen = at least one rated season (the list progress rule), not just an empty rating row.
                        // Fork (2026-11-23): or a whole-show score.
                        rated = show.seasons.any { it.hasRating } || show.showScore != null,
                        releaseDate = local?.firstAirDate,
                        tmdbStatus = local?.status?.value,
                        followed = show.isFollowed,
                        today = today,
                        locale = locale,
                    )
                }

                else -> null
            }
            view?.let { modelAndView.addObject("titleActions", it) }
        } catch (e: Exception) {
            logger.warn("Title actions unavailable for {}: {}", request.requestURI, e.javaClass.simpleName)
        }
    }
}
