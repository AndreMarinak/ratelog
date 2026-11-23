package org.ratelog.watchlist.move

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.MediaImporter
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.BackPath
import org.ratelog.watchlist.setstatus.SetWatchStatusError
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.io.Serializable

/**
 * The flash message after a watchlist quick action: "Removed "Dune" from your watchlist. [Undo]".
 * Undo posts [undoMove] back to POST /watchlist/item, so removing needs no confirmation.
 */
data class WatchlistNotice(
    val messageKey: String,
    val title: String,
    val mediaType: String,
    val mediaId: Long,
    /** Param of the move that restores the previous status; null after an undo (nothing more to undo). */
    val undoMove: String?,
) : Serializable

/** What POST /watchlist/item answers to `fetch` (watchlist.js moves the tile out of the tab in place). */
data class WatchlistMoveResponse(
    val ok: Boolean,
    /** Api names of the status before and after (null = none). */
    val previous: String?,
    val current: String?,
    /** Localized notice, e.g. `Removed "Dune" from your watchlist.` */
    val message: String?,
    /** The `to` that undoes this move, or null when there is nothing to undo. */
    val undoMove: String?,
    val undoLabel: String?,
)

@Controller
class MoveWatchlistItemController(
    private val handler: MoveWatchlistItemHandler,
    private val mediaImporter: MediaImporter,
    private val messages: MessageSource,
) {
    /**
     * Plain form post from a /watchlist tile: `mediaType` (movie | tvshow), `mediaId` (local), `to` (seen | watching |
     * dropped | want_to_watch | remove), `back` (the tab and filter to return to), `undo=1` when it undoes a move.
     */
    @PostMapping("/watchlist/item")
    fun move(
        @CurrentUser user: User,
        @RequestParam("mediaType") mediaType: String,
        @RequestParam("mediaId") mediaId: Long,
        @RequestParam("to") to: String,
        @RequestParam("back", required = false) back: String?,
        @RequestParam("undo", required = false) undo: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        val target = "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/watchlist")
        val type = MediaType.entries.find { it.name == mediaType }
        val move = WatchlistMove.parse(to)
        if (type == null || move == null) {
            redirectAttributes.addFlashAttribute("error", "status.error.invalid")
            return target
        }

        handler.handle(MoveWatchlistItem(user.id!!, type, mediaId, move)).fold(
            { error ->
                redirectAttributes.addFlashAttribute("error", when (error) {
                    SetWatchStatusError.MediaNotFound -> "status.error.media.not.found"
                })
            },
            { result ->
                if (result.changed) {
                    val title = mediaImporter.findLocalById(mediaId, type, user.metadataLang)?.title ?: ""
                    val isUndo = undo == "1"
                    redirectAttributes.addFlashAttribute(
                        "watchlistNotice",
                        WatchlistNotice(
                            messageKey = if (isUndo) "watchlist.notice.restored" else noticeKey(result.previous, result.current),
                            title = title,
                            mediaType = type.name,
                            mediaId = mediaId,
                            undoMove = if (isUndo) null else WatchlistMove.to(result.previous).param,
                        ),
                    )
                }
            },
        )
        return target
    }

    /** The same move for `fetch` (header `X-Requested-With: fetch`): JSON instead of a redirect. */
    @PostMapping("/watchlist/item", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun moveJson(
        @CurrentUser user: User,
        @RequestParam("mediaType") mediaType: String,
        @RequestParam("mediaId") mediaId: Long,
        @RequestParam("to") to: String,
        @RequestParam("undo", required = false) undo: String?,
    ): ResponseEntity<WatchlistMoveResponse> {
        val type = MediaType.entries.find { it.name == mediaType }
        val move = WatchlistMove.parse(to)
        if (type == null || move == null) return failed(HttpStatus.BAD_REQUEST, "status.error.invalid")
        return handler.handle(MoveWatchlistItem(user.id!!, type, mediaId, move)).fold(
            { failed(HttpStatus.NOT_FOUND, "status.error.media.not.found") },
            { result ->
                val title = mediaImporter.findLocalById(mediaId, type, user.metadataLang)?.title ?: ""
                val isUndo = undo == "1"
                val key = if (isUndo) "watchlist.notice.restored" else noticeKey(result.previous, result.current)
                ResponseEntity.ok(
                    WatchlistMoveResponse(
                        ok = true,
                        previous = result.previous?.apiName,
                        current = result.current?.apiName,
                        message = message(key, title),
                        undoMove = if (isUndo || !result.changed) null else WatchlistMove.to(result.previous).param,
                        undoLabel = message("watchlist.notice.undo"),
                    ),
                )
            },
        )
    }

    private fun failed(status: HttpStatus, key: String) =
        ResponseEntity.status(status).body(WatchlistMoveResponse(false, null, null, message(key), null, null))

    private fun message(key: String, vararg args: Any): String =
        messages.getMessage(key, args, key, LocaleContextHolder.getLocale()) ?: key

    companion object {
        fun noticeKey(previous: WatchStatus?, current: WatchStatus?): String = when (current) {
            null -> "watchlist.notice.removed." + (previous?.apiName ?: "want_to_watch")
            else -> "watchlist.notice." + current.apiName
        }
    }
}
