package org.ratelog.watchlist.move

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.MediaImporter
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.BackPath
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
    /** Fork (2026-11-24): the rewatch "Watched again" logged, which its Undo (`unrewatch`) deletes. */
    val rewatchId: Long? = null,
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
    /** Fork (2026-11-24): sent back as `rewatchId` with the undo of "Watched again". */
    val rewatchId: Long? = null,
)

@Controller
class MoveWatchlistItemController(
    private val handler: MoveWatchlistItemHandler,
    private val mediaImporter: MediaImporter,
    private val messages: MessageSource,
) {
    /**
     * Plain form post from a /watchlist tile: `mediaType` (movie | tvshow), `mediaId` (local), `to` (seen | watching |
     * dropped | want_to_watch | remove | unlist | relist | rewatched | unrewatch), `back` (the tab and filter to return
     * to), `undo=1` when it undoes a move, `rewatchId` with `unrewatch` (the logged row to delete).
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
        @RequestParam("rewatchId", required = false) rewatchId: Long? = null,
    ): String {
        val target = "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/watchlist")
        val type = MediaType.entries.find { it.name == mediaType }
        val move = WatchlistMove.parse(to)
        if (type == null || move == null) {
            redirectAttributes.addFlashAttribute("error", "status.error.invalid")
            return target
        }

        handler.handle(MoveWatchlistItem(user.id!!, type, mediaId, move, rewatchId)).fold(
            { error -> redirectAttributes.addFlashAttribute("error", errorKey(error)) },
            { result ->
                if (result.changed) {
                    val title = mediaImporter.findLocalById(mediaId, type, user.metadataLang)?.title ?: ""
                    val isUndo = undo == "1"
                    redirectAttributes.addFlashAttribute(
                        "watchlistNotice",
                        WatchlistNotice(
                            messageKey = if (isUndo) "watchlist.notice.restored" else noticeKey(result),
                            title = title,
                            mediaType = type.name,
                            mediaId = mediaId,
                            undoMove = if (isUndo) null else result.undo?.param,
                            rewatchId = if (isUndo) null else result.rewatchId,
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
        @RequestParam("rewatchId", required = false) rewatchId: Long? = null,
    ): ResponseEntity<WatchlistMoveResponse> {
        val type = MediaType.entries.find { it.name == mediaType }
        val move = WatchlistMove.parse(to)
        if (type == null || move == null) return failed(HttpStatus.BAD_REQUEST, "status.error.invalid")
        return handler.handle(MoveWatchlistItem(user.id!!, type, mediaId, move, rewatchId)).fold(
            { error ->
                // Not seen / not out: 200 with ok = false, the tile stays and the toast says why.
                if (error == MoveWatchlistItemError.MediaNotFound) failed(HttpStatus.NOT_FOUND, errorKey(error))
                else failed(HttpStatus.OK, errorKey(error))
            },
            { result ->
                val title = mediaImporter.findLocalById(mediaId, type, user.metadataLang)?.title ?: ""
                val isUndo = undo == "1"
                val key = if (isUndo) "watchlist.notice.restored" else noticeKey(result)
                ResponseEntity.ok(
                    WatchlistMoveResponse(
                        ok = true,
                        previous = result.previous?.apiName,
                        current = result.current?.apiName,
                        message = message(key, title),
                        undoMove = if (isUndo) null else result.undo?.param,
                        undoLabel = message("watchlist.notice.undo"),
                        rewatchId = if (isUndo) null else result.rewatchId,
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
        fun errorKey(error: MoveWatchlistItemError): String = when (error) {
            MoveWatchlistItemError.MediaNotFound -> "status.error.media.not.found"
            MoveWatchlistItemError.NotSeen -> "rewatch.error.not.seen"
            MoveWatchlistItemError.NotReleased -> "seen.error.not.released"
        }

        /** "Logged a rewatch of "Dune" and took it off your watchlist." after Watched again, else by the tabs it moved between. */
        fun noticeKey(result: MoveWatchlistItemResult): String =
            if (result.move == WatchlistMove.REWATCHED) "watchlist.notice.rewatched" else noticeKey(result.previous, result.current)

        fun noticeKey(previous: WatchStatus?, current: WatchStatus?): String = when (current) {
            null -> "watchlist.notice.removed." + (previous?.apiName ?: "want_to_watch")
            else -> "watchlist.notice." + current.apiName
        }
    }
}
