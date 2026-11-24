package org.ratelog.rewatch.log

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.MediaImporter
import org.ratelog.rewatch.RewatchSummary
import org.ratelog.rewatch.delete.DeleteRewatch
import org.ratelog.rewatch.delete.DeleteRewatchError
import org.ratelog.rewatch.delete.DeleteRewatchHandler
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.ratelog.watchlist.seen.parseTileType
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
 * The flash message after "Watched again" (rendered by fragments/title-actions.html `rewatchNotice`): "Logged a rewatch
 * of Dune." with Undo (POST /rewatch/delete) and "Update your rating?" (the page's rate modal or seasons). inplace.js
 * shows it as a toast with the same two actions; without JS it shows in the action panel.
 */
data class RewatchNotice(
    val messageKey: String,
    val title: String,
    /** The row Undo deletes; null after an undo (nothing more to undo). */
    val rewatchId: Long? = null,
    /** `/movie/603?rate=1`: opens the rate modal (a show: Rate show, or its seasons). Null: no rating offer. */
    val rateHref: String? = null,
    /** messages key of the rating offer: "Update your rating?" when rated, else "Rate it?". */
    val rateKey: String? = null,
) : Serializable

/** What POST /rewatch and /rewatch/delete answer to `fetch` (the poster "⋯" chooser, tile-actions.js). */
data class RewatchResponse(
    val ok: Boolean,
    val message: String?,
    /** The row just logged (Undo deletes it), or null. */
    val rewatchId: Long? = null,
    /** 1 + rewatches; null when it failed. */
    val watchCount: Int? = null,
    val lastWatchedAtEpochMs: Long? = null,
    val undoLabel: String? = null,
    val rateHref: String? = null,
    val rateLabel: String? = null,
)

/**
 * Fork (2026-11-24, rewatch log). POST /rewatch (`tmdbId`, `type` = movie | tv, `back`): "Watched again" on a title you've
 * seen (title page panel, poster chooser). POST /rewatch/delete (`id`, `back`): its Undo. Like POST /seen/toggle, a plain
 * form post redirects back with a notice and `fetch` (`X-Requested-With: fetch`) gets JSON. The title must already be
 * stored (it is, once seen), so no TMDB call happens here.
 */
@Controller
class LogRewatchController(
    private val mediaImporter: MediaImporter,
    private val logHandler: LogRewatchHandler,
    private val deleteHandler: DeleteRewatchHandler,
    private val messages: MessageSource,
) {
    @PostMapping("/rewatch")
    fun log(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type") type: String,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        val target = "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/")
        when (val outcome = run(user, tmdbId, type)) {
            is LogOutcome.Done -> redirectAttributes.addFlashAttribute(
                "rewatchNotice",
                RewatchNotice(LOGGED, outcome.title, outcome.result.rewatch.id, outcome.rateHref, rateKey(outcome.result.rated)),
            )
            is LogOutcome.Failed -> redirectAttributes.addFlashAttribute("error", outcome.messageKey)
        }
        return target
    }

    @PostMapping("/rewatch", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun logJson(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type") type: String,
    ): ResponseEntity<RewatchResponse> = when (val outcome = run(user, tmdbId, type)) {
        is LogOutcome.Done -> ResponseEntity.ok(
            RewatchResponse(
                ok = true,
                message = message(LOGGED, outcome.title),
                rewatchId = outcome.result.rewatch.id,
                watchCount = outcome.result.summary.watchCount,
                lastWatchedAtEpochMs = outcome.result.summary.lastAtEpochMs,
                undoLabel = message(UNDO),
                rateHref = outcome.rateHref,
                rateLabel = message(rateKey(outcome.result.rated)),
            ),
        )
        is LogOutcome.Failed -> ResponseEntity.status(outcome.httpStatus).body(RewatchResponse(false, message(outcome.messageKey)))
    }

    @PostMapping("/rewatch/delete")
    fun delete(
        @CurrentUser user: User,
        @RequestParam("id") id: Long,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        val target = "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/")
        when (val outcome = runDelete(user, id)) {
            is DeleteOutcome.Done -> redirectAttributes.addFlashAttribute("rewatchNotice", RewatchNotice(REMOVED, outcome.title))
            DeleteOutcome.Gone -> redirectAttributes.addFlashAttribute("error", NOT_FOUND)
        }
        return target
    }

    @PostMapping("/rewatch/delete", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun deleteJson(@CurrentUser user: User, @RequestParam("id") id: Long): ResponseEntity<RewatchResponse> =
        when (val outcome = runDelete(user, id)) {
            is DeleteOutcome.Done -> ResponseEntity.ok(
                RewatchResponse(
                    ok = true,
                    message = message(REMOVED, outcome.title),
                    watchCount = outcome.summary.watchCount,
                    lastWatchedAtEpochMs = outcome.summary.lastAtEpochMs,
                ),
            )
            DeleteOutcome.Gone -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(RewatchResponse(false, message(NOT_FOUND)))
        }

    private sealed interface LogOutcome {
        data class Done(val result: LogRewatchResult, val title: String, val rateHref: String) : LogOutcome
        data class Failed(val messageKey: String, val httpStatus: HttpStatus) : LogOutcome
    }

    private sealed interface DeleteOutcome {
        data class Done(val title: String, val summary: RewatchSummary) : DeleteOutcome
        data object Gone : DeleteOutcome
    }

    private fun run(user: User, tmdbId: Int, type: String): LogOutcome {
        val mediaType = parseTileType(type)
        if (mediaType == null || tmdbId <= 0) return LogOutcome.Failed("status.error.invalid", HttpStatus.BAD_REQUEST)
        // A seen title is always stored already; one that isn't can't be seen, so there is nothing to import.
        val media = mediaImporter.findLocal(tmdbId, mediaType, user.metadataLang)
            ?: return LogOutcome.Failed(NOT_SEEN, HttpStatus.OK)
        return logHandler.handle(LogRewatch(user.id!!, mediaType, media.id)).fold(
            { error ->
                when (error) {
                    LogRewatchError.MediaNotFound -> LogOutcome.Failed("status.error.media.not.found", HttpStatus.NOT_FOUND)
                    // 200: the page keeps its state and shows the message (like Seen on an unreleased title).
                    LogRewatchError.NotSeen -> LogOutcome.Failed(NOT_SEEN, HttpStatus.OK)
                    LogRewatchError.NotReleased -> LogOutcome.Failed("seen.error.not.released", HttpStatus.OK)
                    LogRewatchError.InvalidDate -> LogOutcome.Failed("status.error.invalid", HttpStatus.BAD_REQUEST)
                }
            },
            { LogOutcome.Done(it, media.title, rateHref(mediaType, tmdbId)) },
        )
    }

    private fun runDelete(user: User, id: Long): DeleteOutcome =
        deleteHandler.handle(DeleteRewatch(user.id!!, id)).fold(
            { error -> when (error) { DeleteRewatchError.NotFound -> DeleteOutcome.Gone } },
            { result ->
                val title = mediaImporter.findLocalById(result.deleted.mediaId, result.deleted.mediaType, user.metadataLang)?.title ?: ""
                DeleteOutcome.Done(title, result.summary)
            },
        )

    private fun message(key: String, vararg args: Any): String =
        messages.getMessage(key, args, key, LocaleContextHolder.getLocale()) ?: key

    companion object {
        const val LOGGED = "rewatch.notice.logged"
        const val REMOVED = "rewatch.notice.removed"
        const val UNDO = "rewatch.notice.undo"
        const val NOT_SEEN = "rewatch.error.not.seen"
        const val NOT_FOUND = "rewatch.error.not.found"

        fun rateKey(rated: Boolean) = if (rated) "rewatch.notice.rate.update" else "rewatch.notice.rate.new"

        /** The title page with `?rate=1`: it opens the rate modal (a show: Rate show, or scrolls to its seasons). */
        fun rateHref(type: MediaType, tmdbId: Int): String = when (type) {
            MediaType.movie -> "/movie/$tmdbId?rate=1"
            MediaType.tvshow -> "/tv/$tmdbId?rate=1"
        }
    }
}
