package org.ratelog.watchlist.getstatus

import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.annotations.CurrentUser
import org.ratelog.movie.detail.DetailMovieController
import org.ratelog.tvshow.detail.DetailTvShowController
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable

/*
 * Puts the current user's status on the movie and TV detail pages (`watchStatus`, plus `watchStatusOptions`
 * for the buttons) without touching the upstream detail controllers and handlers.
 * Rendered by templates/fragments/watch-status.html.
 */

@ControllerAdvice(assignableTypes = [DetailMovieController::class])
class MovieWatchStatusModelAdvice(
    private val handler: GetWatchStatusHandler,
) {
    @ModelAttribute
    fun addWatchStatus(@CurrentUser user: User?, @PathVariable("id") tmdbId: Int, model: Model) =
        putWatchStatus(model, handler, user, MediaType.movie, tmdbId)
}

@ControllerAdvice(assignableTypes = [DetailTvShowController::class])
class TvWatchStatusModelAdvice(
    private val handler: GetWatchStatusHandler,
) {
    @ModelAttribute
    fun addWatchStatus(@CurrentUser user: User?, @PathVariable("id") tmdbId: Int, model: Model) =
        putWatchStatus(model, handler, user, MediaType.tvshow, tmdbId)
}

private fun putWatchStatus(model: Model, handler: GetWatchStatusHandler, user: User?, mediaType: MediaType, tmdbId: Int) {
    val query = user?.id
        ?.takeIf { tmdbId > 0 }
        ?.let { GetWatchStatus(it, mediaType, TmdbId(tmdbId)) }
    model.addAttribute("watchStatus", query?.let(handler::handle))
    // Fork (2026-11-24): the watchlist is independent of the status (TitleActionsInterceptor reads it).
    model.addAttribute("watchOnWatchlist", query?.let(handler::onWatchlist) == true)
    model.addAttribute("watchStatusOptions", WatchStatus.entries)
}
