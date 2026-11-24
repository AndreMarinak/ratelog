package org.ratelog.movie.rating.tile

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.media.MediaImporter
import org.ratelog.movie.Movie
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.show.TvTileRateHandler
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
    /** Fork: a show rated by season (only on a refusal): the picker shows the average and "Rate seasons". */
    val seasons: Boolean = false,
    /** Localized, for the toast. */
    val message: String?,
)

/**
 * The ★ picker on poster tiles (static/tile-rate.js). Tiles only know the TMDB id, so the movie is saved locally first
 * if needed (like POST /watchlist/toggle), then [TileRateHandler] / [TileDeleteRatingHandler] run.
 * - POST /movie/rate/tile (`tmdbId`, `score`, optional `back`): rate, or change a quick rating.
 * - POST /movie/rate/tile/delete (`tmdbId`, optional `back`): delete a quick rating.
 * With the header `X-Requested-With: fetch` they answer [TileRateResponse]; a plain form post (the picker's fallback)
 * redirects back with a flash message.
 * Fork (2026-11-23): `type=tv` rates a show as a whole ([TvTileRateHandler]), unless it has rated seasons.
 */
@Controller
class TileRateController(
    private val mediaImporter: MediaImporter,
    private val rateHandler: TileRateHandler,
    private val deleteHandler: TileDeleteRatingHandler,
    private val tvHandler: TvTileRateHandler,
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
    ): String = plain(rateOutcome(user, tmdbId, type, score), back, titlePath(type, tmdbId), request, redirectAttributes)

    @PostMapping("/movie/rate/tile", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun rateJson(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type", required = false) type: String?,
        @RequestParam("score", required = false) score: Double?,
    ): ResponseEntity<TileRateResponse> = json(rateOutcome(user, tmdbId, type, score))

    @PostMapping("/movie/rate/tile/delete")
    fun delete(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type", required = false) type: String?,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String = plain(deleteOutcome(user, tmdbId, type), back, titlePath(type, tmdbId), request, redirectAttributes)

    @PostMapping("/movie/rate/tile/delete", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun deleteJson(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type", required = false) type: String?,
    ): ResponseEntity<TileRateResponse> = json(deleteOutcome(user, tmdbId, type))

    private fun rateOutcome(user: User, tmdbId: Int, type: String?, score: Double?): Outcome =
        run(user, tmdbId, type) { media, mediaType ->
            if (mediaType == MediaType.tvshow) tvHandler.rate(user.id!!, TvShow.Id(media.id), score)
            else rateHandler.handle(TileRate(user.id!!, Movie.Id(media.id), score))
        }

    private fun deleteOutcome(user: User, tmdbId: Int, type: String?): Outcome =
        run(user, tmdbId, type) { media, mediaType ->
            if (mediaType == MediaType.tvshow) tvHandler.delete(user.id!!, TvShow.Id(media.id))
            else deleteHandler.handle(TileDeleteRating(user.id!!, Movie.Id(media.id)))
        }

    /** `movie` (or no type: the endpoint's name) or `tv` (fork: a whole-show score); anything else is refused. */
    private fun mediaTypeOf(type: String?): MediaType? = when (type?.trim()?.lowercase()) {
        null, "movie" -> MediaType.movie
        "tv", "tvshow" -> MediaType.tvshow
        else -> null
    }

    private fun titlePath(type: String?, tmdbId: Int): String =
        if (mediaTypeOf(type) == MediaType.tvshow) "/tv/$tmdbId" else "/movie/$tmdbId"

    private sealed interface Outcome {
        data class Done(val result: TileRateResult, val title: String) : Outcome
        data class Failed(val error: TileRateHandlerError?, val messageKey: String, val title: String?) : Outcome
    }

    private fun run(
        user: User,
        tmdbId: Int,
        type: String?,
        action: (LocalMedia, MediaType) -> arrow.core.Either<TileRateHandlerError, TileRateResult>,
    ): Outcome {
        val mediaType = mediaTypeOf(type)
        if (tmdbId <= 0 || mediaType == null) return Outcome.Failed(null, "status.error.invalid", null)
        val media = mediaImporter.ensure(tmdbId, mediaType, user.metadataLang).getOrNull()
            ?: return Outcome.Failed(null, "status.error.media.not.found", null)
        return action(media, mediaType).fold(
            { Outcome.Failed(it, errorKey(it), media.title) },
            { Outcome.Done(it, media.title) },
        )
    }

    private fun plain(outcome: Outcome, back: String?, titlePath: String, request: HttpServletRequest, redirectAttributes: RedirectAttributes): String {
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
        return "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), titlePath)
    }

    private fun json(outcome: Outcome): ResponseEntity<TileRateResponse> = when (outcome) {
        is Outcome.Done -> {
            val r = outcome.result
            val key = when (r.outcome) {
                TileRateOutcome.RATED -> if (r.seenDelta > 0) "tile.rate.done.rated.seen" else "tile.rate.done.rated"
                TileRateOutcome.CHANGED -> "tile.rate.done.changed"
                TileRateOutcome.DELETED -> "tile.rate.done.deleted"
            }
            ResponseEntity.ok(response(r.after, r.before.effectiveStatus?.apiName, r.seenDelta, r.score?.value, false, message(key, outcome.title, r.score?.value?.let(::compactScore) ?: "")))
        }
        is Outcome.Failed -> {
            val detailed = outcome.error as? TileRateHandlerError.DetailedRating
            val bySeason = outcome.error as? TileRateHandlerError.RatedBySeason
            val status = when (outcome.error) {
                null -> if (outcome.messageKey == "status.error.invalid") HttpStatus.BAD_REQUEST else HttpStatus.NOT_FOUND
                TileRateHandlerError.InvalidScore -> HttpStatus.BAD_REQUEST
                TileRateHandlerError.MovieNotFound, TileRateHandlerError.RatingNotFound -> HttpStatus.NOT_FOUND
                is TileRateHandlerError.DetailedRating, is TileRateHandlerError.RatedBySeason -> HttpStatus.CONFLICT
                // 200: nothing to fall back to; the tile keeps its state and the toast says why.
                TileRateHandlerError.NotReleased, TileRateHandlerError.SaveFailed -> HttpStatus.OK
            }
            ResponseEntity.status(status).body(
                response(
                    state = SeenState(null, rated = detailed != null || bySeason != null, isTv = bySeason != null),
                    previous = null,
                    seenDelta = 0,
                    score = detailed?.score?.value ?: bySeason?.score?.value,
                    detailed = detailed != null || bySeason != null,
                    message = if (outcome.messageKey.startsWith("tile.rate.")) message(outcome.messageKey, outcome.title ?: "") else message(outcome.messageKey),
                ).copy(ok = false, seasons = bySeason != null),
            )
        }
    }

    private fun response(state: SeenState, previous: String?, seenDelta: Int, score: Double?, detailed: Boolean, message: String?) = TileRateResponse(
        ok = true,
        status = state.effectiveStatus?.apiName,
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
        is TileRateHandlerError.RatedBySeason -> "tile.rate.error.seasons"
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
