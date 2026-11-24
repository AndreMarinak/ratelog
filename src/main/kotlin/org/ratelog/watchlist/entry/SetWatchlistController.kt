package org.ratelog.watchlist.entry

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.ratelog.watchlist.seen.SeenState
import org.ratelog.watchlist.seen.TileStateResponse
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Fork (2026-11-24): the title page's Watchlist toggle (`templates/fragments/title-actions.html`). `on=1` adds the title
 * to the watchlist, `on=0` takes it off; the status and the rating are never touched (a seen title stays seen), except
 * that adding replaces Watching / Dropped ([SetWatchlistHandler]). Form fields: `mediaType` (movie | tvshow), `mediaId`
 * (local), `on`, `back`. With `X-Requested-With: fetch` it answers with the title's new state (title-actions.js).
 */
@Controller
class SetWatchlistController(
    private val handler: SetWatchlistHandler,
    private val messages: MessageSource,
) {
    @PostMapping("/watchlist/set")
    fun set(
        @CurrentUser user: User,
        @RequestParam("mediaType") mediaType: String,
        @RequestParam("mediaId") mediaId: Long,
        @RequestParam("on") on: String,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        val target = "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/watchlist")
        val type = MediaType.entries.find { it.name == mediaType }
        val wanted = parseOn(on)
        if (type == null || wanted == null) {
            redirectAttributes.addFlashAttribute("error", "status.error.invalid")
            return target
        }
        handler.handle(SetWatchlist(user.id!!, type, mediaId, wanted)).fold(
            { redirectAttributes.addFlashAttribute("error", "status.error.media.not.found") },
            { result -> if (result.changed) redirectAttributes.addFlashAttribute("success", messageKey(wanted)) },
        )
        return target
    }

    @PostMapping("/watchlist/set", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun setJson(
        @CurrentUser user: User,
        @RequestParam("mediaType") mediaType: String,
        @RequestParam("mediaId") mediaId: Long,
        @RequestParam("on") on: String,
    ): ResponseEntity<TileStateResponse> {
        val type = MediaType.entries.find { it.name == mediaType }
        val wanted = parseOn(on)
        if (type == null || wanted == null) return failed(HttpStatus.BAD_REQUEST, "status.error.invalid")
        return handler.handle(SetWatchlist(user.id!!, type, mediaId, wanted)).fold(
            { failed(HttpStatus.NOT_FOUND, "status.error.media.not.found") },
            { result -> ResponseEntity.ok(TileStateResponse.of(result.after, result.seenDelta, message(messageKey(wanted)))) },
        )
    }

    private fun messageKey(on: Boolean) = if (on) "watchlist.quick.added" else "watchlist.quick.removed"

    private fun failed(status: HttpStatus, key: String) =
        ResponseEntity.status(status).body(TileStateResponse.of(SeenState(null, false), 0, message(key), ok = false))

    private fun message(key: String): String =
        messages.getMessage(key, null, key, LocaleContextHolder.getLocale()) ?: key

    companion object {
        /** `1` / `true` / `on` adds, `0` / `false` / `off` removes. */
        fun parseOn(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> null
        }
    }
}
