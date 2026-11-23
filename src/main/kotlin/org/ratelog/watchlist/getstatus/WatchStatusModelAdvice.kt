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
    val status = user?.id
        ?.takeIf { tmdbId > 0 }
        ?.let { handler.handle(GetWatchStatus(it, mediaType, TmdbId(tmdbId))) }
    model.addAttribute("watchStatus", status)
    model.addAttribute("watchStatusOptions", WatchStatus.entries)
}
