package org.ratelog.watchlist.seen

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatusController
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * POST /status for `fetch` (header `X-Requested-With: fetch`): the title page's Watchlist toggle and the show's
 * Watching / Dropped control save in place (title-actions.js) and get the title's new state back. Plain form posts
 * still go to [SetWatchStatusController], which redirects back to the page.
 */
@Controller
class StatusJsonController(
    private val handler: SetWatchStatusHandler,
    private val seenStateReader: SeenStateReader,
    private val messages: MessageSource,
) {
    @PostMapping("/status", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun setStatus(
        @CurrentUser user: User,
        @RequestParam("mediaType") mediaType: String,
        @RequestParam("mediaId") mediaId: Long,
        @RequestParam("status", required = false) status: String?,
    ): ResponseEntity<TileStateResponse> {
        val type = MediaType.entries.find { it.name == mediaType }
        val newStatus = status?.takeIf { it.isNotBlank() }?.let { WatchStatus.parse(it) ?: return failed(HttpStatus.BAD_REQUEST, "status.error.invalid") }
        if (type == null) return failed(HttpStatus.BAD_REQUEST, "status.error.invalid")
        return handler.handle(SetWatchStatus(user.id!!, type, mediaId, newStatus)).fold(
            { failed(HttpStatus.NOT_FOUND, "status.error.media.not.found") },
            { result ->
                val state = seenStateReader.read(user.id, type, mediaId)
                val before = SeenState(
                    result.previous?.takeIf { it != WatchStatus.WANT_TO_WATCH }, state.rated, state.isTv, onWatchlist = result.wasOnWatchlist,
                )
                val delta = (if (state.seen) 1 else 0) - (if (before.seen) 1 else 0)
                ResponseEntity.ok(TileStateResponse.of(state, delta, null))
            },
        )
    }

    private fun failed(status: HttpStatus, key: String) = ResponseEntity.status(status).body(
        TileStateResponse.of(SeenState(null, false), 0, messages.getMessage(key, null, key, LocaleContextHolder.getLocale()), ok = false),
    )
}
