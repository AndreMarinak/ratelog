package org.ratelog.movie.rating.tile

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.media.MediaImporter
import org.ratelog.movie.Movie
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.ratelog.watchlist.seen.SeenState
import org.ratelog.watchlist.seen.tileScoreLabel
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.math.BigDecimal

/** What the ★ picker gets back (`fetch`): the tile's Seen / Watchlist state, as for the eye, plus the rating. */
data class TileRateResponse(
    val ok: Boolean,
    val status: String?,
    /** The status before, so the watchlist page can move the tile out of its tab. */
    val previousStatus: String?,
    val seen: Boolean,
    val seenLocked: Boolean,
    val onWatchlist: Boolean,
    val canToggleWatchlist: Boolean,
    val seenDelta: Int,
    val rated: Boolean,
    val score: Double?,
    /** "8.5", like the chip the server renders. */
    val scoreLabel: String?,
    /** A detailed rating (only on a refusal): the picker switches to "Edit rating" on the title page. */
    val detailed: Boolean,
    /** Localized, for the toast. */
    val message: String?,
)

/**
 * The ★ picker on poster tiles (static/tile-rate.js). Tiles only know the TMDB id, so the movie is saved locally first
 * if needed (like POST /watchlist/toggle), then [TileRateHandler] / [TileDeleteRatingHandler] run.
 * - POST /movie/rate/tile (`tmdbId`, `score`, optional `back`): rate, or change a quick rating.
 * - POST /movie/rate/tile/delete (`tmdbId`, optional `back`): delete a quick rating.
 * With the header `X-Requested-With: fetch` they answer [TileRateResponse]; a plain form post (the picker's fallback)
 * redirects back with a flash message. Shows are rated per season on their page, so `type` must be `movie`.
 */
@Controller
class TileRateController(
    private val mediaImporter: MediaImporter,
    private val rateHandler: TileRateHandler,
    private val deleteHandler: TileDeleteRatingHandler,
    private val messages: MessageSource,
) {
    @PostMapping("/movie/rate/tile")
    fun rate(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type", required = false) type: String?,
        @RequestParam("score", required = false) score: Double?,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String = plain(run(user, tmdbId, type) { rateHandler.handle(TileRate(user.id!!, Movie.Id(it.id), score)) }, back, tmdbId, request, redirectAttributes)

    @PostMapping("/movie/rate/tile", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun rateJson(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type", required = false) type: String?,
        @RequestParam("score", required = false) score: Double?,
    ): ResponseEntity<TileRateResponse> =
        json(run(user, tmdbId, type) { rateHandler.handle(TileRate(user.id!!, Movie.Id(it.id), score)) })

    @PostMapping("/movie/rate/tile/delete")
    fun delete(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type", required = false) type: String?,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String = plain(run(user, tmdbId, type) { deleteHandler.handle(TileDeleteRating(user.id!!, Movie.Id(it.id))) }, back, tmdbId, request, redirectAttributes)

    @PostMapping("/movie/rate/tile/delete", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun deleteJson(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type", required = false) type: String?,
    ): ResponseEntity<TileRateResponse> =
        json(run(user, tmdbId, type) { deleteHandler.handle(TileDeleteRating(user.id!!, Movie.Id(it.id))) })

    private sealed interface Outcome {
        data class Done(val result: TileRateResult, val title: String) : Outcome
        data class Failed(val error: TileRateHandlerError?, val messageKey: String, val title: String?) : Outcome
    }

    private fun run(
        user: User,
        tmdbId: Int,
        type: String?,
        action: (LocalMedia) -> arrow.core.Either<TileRateHandlerError, TileRateResult>,
    ): Outcome {
        // Only movies: a show's seasons are rated on its page. A missing type means movie (the endpoint's name).
        if (tmdbId <= 0 || (type != null && type.trim().lowercase() != "movie")) return Outcome.Failed(null, "status.error.invalid", null)
        val media = mediaImporter.ensure(tmdbId, MediaType.movie, user.metadataLang).getOrNull()
            ?: return Outcome.Failed(null, "status.error.media.not.found", null)
        return action(media).fold(
            { Outcome.Failed(it, errorKey(it), media.title) },
            { Outcome.Done(it, media.title) },
        )
    }

    private fun plain(outcome: Outcome, back: String?, tmdbId: Int, request: HttpServletRequest, redirectAttributes: RedirectAttributes): String {
        when (outcome) {
            is Outcome.Done -> redirectAttributes.addFlashAttribute(
                "success",
                when (outcome.result.outcome) {
                    TileRateOutcome.RATED -> "success.rating.saved"
                    TileRateOutcome.CHANGED -> "success.rating.updated"
                    TileRateOutcome.DELETED -> "success.rating.deleted"
                },
            )
            // The flash is shown without arguments, so plain posts use the keys without the title.
            is Outcome.Failed -> redirectAttributes.addFlashAttribute(
                "error",
                if (outcome.messageKey.startsWith("tile.rate.")) outcome.messageKey + ".plain" else outcome.messageKey,
            )
        }
        return "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/movie/$tmdbId")
    }

    private fun json(outcome: Outcome): ResponseEntity<TileRateResponse> = when (outcome) {
        is Outcome.Done -> {
            val r = outcome.result
            val key = when (r.outcome) {
                TileRateOutcome.RATED -> if (r.seenDelta > 0) "tile.rate.done.rated.seen" else "tile.rate.done.rated"
                TileRateOutcome.CHANGED -> "tile.rate.done.changed"
                TileRateOutcome.DELETED -> "tile.rate.done.deleted"
            }
            ResponseEntity.ok(response(r.after, r.before.status?.apiName, r.seenDelta, r.score?.value, false, message(key, outcome.title, r.score?.value?.let(::compactScore) ?: "")))
        }
        is Outcome.Failed -> {
            val detailed = outcome.error as? TileRateHandlerError.DetailedRating
            val status = when (outcome.error) {
                null -> if (outcome.messageKey == "status.error.invalid") HttpStatus.BAD_REQUEST else HttpStatus.NOT_FOUND
                TileRateHandlerError.InvalidScore -> HttpStatus.BAD_REQUEST
                TileRateHandlerError.MovieNotFound, TileRateHandlerError.RatingNotFound -> HttpStatus.NOT_FOUND
                is TileRateHandlerError.DetailedRating -> HttpStatus.CONFLICT
                // 200: nothing to fall back to; the tile keeps its state and the toast says why.
                TileRateHandlerError.NotReleased, TileRateHandlerError.SaveFailed -> HttpStatus.OK
            }
            ResponseEntity.status(status).body(
                response(
                    state = SeenState(null, rated = detailed != null),
                    previous = null,
                    seenDelta = 0,
                    score = detailed?.score?.value,
                    detailed = detailed != null,
                    message = if (outcome.messageKey.startsWith("tile.rate.")) message(outcome.messageKey, outcome.title ?: "") else message(outcome.messageKey),
                ).copy(ok = false),
            )
        }
    }

    private fun response(state: SeenState, previous: String?, seenDelta: Int, score: Double?, detailed: Boolean, message: String?) = TileRateResponse(
        ok = true,
        status = state.status?.apiName,
        previousStatus = previous,
        seen = state.seen,
        seenLocked = state.seenLocked,
        onWatchlist = state.onWatchlist,
        canToggleWatchlist = state.canToggleWatchlist,
        seenDelta = seenDelta,
        rated = score != null,
        score = score,
        scoreLabel = score?.let(::scoreLabel),
        detailed = detailed,
        message = message,
    )

    private fun errorKey(error: TileRateHandlerError): String = when (error) {
        TileRateHandlerError.MovieNotFound -> "status.error.media.not.found"
        TileRateHandlerError.NotReleased -> "tile.rate.error.not.released"
        TileRateHandlerError.InvalidScore -> "tile.rate.error.invalid"
        is TileRateHandlerError.DetailedRating -> "tile.rate.error.detailed"
        TileRateHandlerError.RatingNotFound -> "tile.rate.error.not.found"
        TileRateHandlerError.SaveFailed -> "tile.rate.error.failed"
    }

    private fun message(key: String, vararg args: Any): String =
        messages.getMessage(key, args.takeIf { it.isNotEmpty() }, key, LocaleContextHolder.getLocale()) ?: key

    companion object {
        /** The chip's label, as the server renders it (TileActionsView.scoreLabel): "8.0", "7.8", "10". */
        fun scoreLabel(score: Double): String = tileScoreLabel(score)

        /** For sentences: "8", "7.5", "7.75". */
        fun compactScore(score: Double): String = BigDecimal.valueOf(score).stripTrailingZeros().toPlainString()
    }
}
