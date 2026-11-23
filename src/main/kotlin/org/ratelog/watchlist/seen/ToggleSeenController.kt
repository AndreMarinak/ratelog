package org.ratelog.watchlist.seen

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.MediaImporter
import org.ratelog.user.User
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** Parses the `type` form field of tile forms: `movie`, or `tv` / `tvshow`. */
fun parseTileType(type: String?): MediaType? = when (type?.trim()?.lowercase()) {
    "movie" -> MediaType.movie
    "tv", "tvshow" -> MediaType.tvshow
    else -> null
}

/**
 * POST /seen/toggle (`tmdbId`, `type`, optional `back`): the Seen toggle on title pages, poster tiles and lists.
 * Tiles only know the TMDB id, so the title is saved locally first if needed (like the watchlist bookmark).
 * A plain form post redirects back with a flash message; `fetch` with `X-Requested-With: fetch` gets JSON instead.
 */
@Controller
class ToggleSeenController(
    private val mediaImporter: MediaImporter,
    private val handler: ToggleSeenHandler,
    private val messages: MessageSource,
) {
    @PostMapping("/seen/toggle")
    fun toggle(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type") type: String,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        val target = "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/watchlist")
        when (val outcome = run(user, tmdbId, type)) {
            is Outcome.Done -> redirectAttributes.addFlashAttribute(
                if (outcome.result.outcome == ToggleSeenOutcome.LOCKED_BY_RATING) "error" else "success",
                outcome.result.outcome.messageKey,
            )
            is Outcome.Failed -> redirectAttributes.addFlashAttribute("error", outcome.messageKey)
        }
        return target
    }

    @PostMapping("/seen/toggle", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun toggleJson(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type") type: String,
    ): ResponseEntity<TileStateResponse> = when (val outcome = run(user, tmdbId, type)) {
        is Outcome.Done -> ResponseEntity.ok(
            TileStateResponse.of(outcome.result.state, outcome.result.seenDelta, message(outcome.result.outcome.messageKey)),
        )
        is Outcome.Failed -> ResponseEntity.status(outcome.httpStatus).body(
            TileStateResponse.of(outcome.state ?: SeenState(null, false), 0, message(outcome.messageKey), ok = false),
        )
    }

    private sealed interface Outcome {
        data class Done(val result: ToggleSeenResult) : Outcome
        data class Failed(val messageKey: String, val httpStatus: HttpStatus, val state: SeenState? = null) : Outcome
    }

    private fun run(user: User, tmdbId: Int, type: String): Outcome {
        val mediaType = parseTileType(type)
        if (mediaType == null || tmdbId <= 0) return Outcome.Failed("status.error.invalid", HttpStatus.BAD_REQUEST)
        val media = mediaImporter.ensure(tmdbId, mediaType, user.metadataLang).getOrNull()
            ?: return Outcome.Failed("status.error.media.not.found", HttpStatus.NOT_FOUND)
        return handler.handle(ToggleSeen(user.id!!, mediaType, media.id)).fold(
            { error ->
                when (error) {
                    ToggleSeenError.MediaNotFound -> Outcome.Failed("status.error.media.not.found", HttpStatus.NOT_FOUND)
                    // 200: the page keeps its state and shows the message; nothing to fall back to.
                    ToggleSeenError.NotReleased -> Outcome.Failed("seen.error.not.released", HttpStatus.OK)
                }
            },
            { Outcome.Done(it) },
        )
    }

    private fun message(key: String): String =
        messages.getMessage(key, null, key, LocaleContextHolder.getLocale()) ?: key
}
