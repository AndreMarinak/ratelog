package org.ratelog.watchlist.seen

import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.MediaImporter
import org.ratelog.user.User
import org.ratelog.watchlist.quickadd.QuickWatchlistController
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * The poster bookmark (POST /watchlist/toggle) for `fetch`: same toggle as [QuickWatchlistController], but it answers
 * with the tile's new state as JSON, so `tile-actions.js` can update the tile in place. Plain form posts still go to
 * [QuickWatchlistController] and redirect back.
 */
@Controller
class QuickWatchlistJsonController(
    private val mediaImporter: MediaImporter,
    private val quickWatchlist: QuickWatchlistController,
    private val seenStateReader: SeenStateReader,
    private val messages: MessageSource,
) {
    @PostMapping("/watchlist/toggle", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun toggle(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type") type: String,
    ): ResponseEntity<TileStateResponse> {
        val mediaType = parseTileType(type)
        if (mediaType == null || tmdbId <= 0) return failed(HttpStatus.BAD_REQUEST, "status.error.invalid")
        val media = mediaImporter.ensure(tmdbId, mediaType, user.metadataLang).getOrNull()
            ?: return failed(HttpStatus.NOT_FOUND, "status.error.media.not.found")
        val outcome = quickWatchlist.toggle(user.id!!, mediaType, media.id)
        val state = seenStateReader.read(user.id, mediaType, media.id)
        return ResponseEntity.ok(TileStateResponse.of(state, 0, message(outcome.messageKey)))
    }

    private fun failed(status: HttpStatus, key: String) =
        ResponseEntity.status(status).body(TileStateResponse.of(SeenState(null, false), 0, message(key), ok = false))

    private fun message(key: String): String =
        messages.getMessage(key, null, key, LocaleContextHolder.getLocale()) ?: key
}
