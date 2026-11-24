package org.ratelog.api.recommendations

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.ApiException
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.notFound
import org.ratelog.api.parseMediaType
import org.ratelog.api.posterUrl
import org.ratelog.recommendations.related.GetRelatedTitles
import org.ratelog.recommendations.related.GetRelatedTitlesError
import org.ratelog.recommendations.related.GetRelatedTitlesHandler
import org.ratelog.recommendations.related.RelatedTile
import org.ratelog.user.User
import org.ratelog.watchlist.seen.ReleaseRules
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

data class RecommendationsResponse(
    val tmdbId: Int,
    val type: String,
    /** Movies in a TMDB collection only, otherwise null. */
    val series: SeriesDto?,
    val moreLikeThis: List<RelatedTitleDto>,
)

data class SeriesDto(
    val collectionId: Int,
    val name: String,
    /** Entries that are out and you've seen, and entries that are out in all (upcoming and undated ones can't be seen yet). */
    val seen: Int,
    val released: Int,
    /** Every entry, in release order; `current` marks the requested title. */
    val titles: List<RelatedTitleDto>,
)

data class RelatedTitleDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val releaseDate: String?,
    val posterUrl: String?,
    val url: String,
    val tmdbVoteAverage: Double?,
    /** Released after today. */
    val upcoming: Boolean,
    /** The tiles' Seen rule: a movie rated or Watched; a show Watched, or rated with no status. */
    val seen: Boolean,
    /** Your watch status (`want_to_watch`, `watching`, `watched`, `dropped`) or null. */
    val status: String?,
    /** Fork (2026-11-24): on your watchlist, independent of `status` (a `watched` title can be on it too, to rewatch). */
    val onWatchlist: Boolean = false,
    /** Your own score, null when unrated. */
    val userScore: Double?,
    /** Series entries only: the requested title. */
    val current: Boolean? = null,
)

/**
 * `GET /api/v1/titles/{type}/{tmdbId}/recommendations`: what the title page shows under the title, the movie's series
 * (TMDB collection, every entry) and "More like this" (TMDB recommendations). Like `/api/v1/browse`, seen titles are only
 * left out of `moreLikeThis` with `hideSeen=true` (the page hides them). See docs/api.md#recommendations.
 */
@RestController
@RequestMapping("/api/v1/titles")
class ApiRecommendationsController(
    private val handler: GetRelatedTitlesHandler,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @GetMapping("/{type}/{tmdbId}/recommendations")
    fun recommendations(
        @CurrentUser user: User,
        @PathVariable type: String,
        @PathVariable tmdbId: Int,
        @RequestParam("hideSeen", required = false) hideSeen: String?,
    ): RecommendationsResponse {
        val mediaType = parseMediaType(type) ?: throw badRequest("invalid_type", "type must be \"movie\" or \"tv\"")
        if (tmdbId <= 0) throw badRequest("invalid_parameter", "tmdbId must be a positive integer")
        val hide = parseBoolean(hideSeen)

        val related = handler.handle(GetRelatedTitles(user.id!!, mediaType, tmdbId, user.metadataLang, hideSeen = hide))
            .fold({ throw failure(it, mediaType, tmdbId) }, { it })
        val today = LocalDate.now(clock)

        return RecommendationsResponse(
            tmdbId = tmdbId,
            type = mediaType.apiName(),
            series = related.series?.let { series ->
                val released = series.entries.filter { it.isOut(today) }
                SeriesDto(
                    collectionId = series.collectionId,
                    name = series.name,
                    seen = released.count { it.seen },
                    released = released.size,
                    titles = series.entries.map { it.toDto(today, current = it.current) },
                )
            },
            moreLikeThis = related.moreLikeThis.map { it.toDto(today, current = null) },
        )
    }

    private fun RelatedTile.toDto(today: LocalDate, current: Boolean?) = RelatedTitleDto(
        tmdbId = title.tmdbId,
        type = title.type.apiName(),
        title = title.title,
        year = title.year,
        releaseDate = title.releaseDate?.toString(),
        posterUrl = title.posterPath?.let(::posterUrl),
        url = if (title.type == MediaType.movie) "/movie/${title.tmdbId}" else "/tv/${title.tmdbId}",
        tmdbVoteAverage = title.voteAverage,
        upcoming = ReleaseRules.isUpcoming(title.releaseDate, today),
        seen = seen,
        status = mark?.effectiveStatus?.apiName,
        onWatchlist = mark?.onWatchlist == true,
        userScore = mark?.score,
        current = current,
    )

    private fun parseBoolean(value: String?): Boolean = when (value?.trim()?.lowercase()) {
        null, "", "false", "0", "no" -> false
        "true", "1", "yes" -> true
        else -> throw badRequest("invalid_parameter", "hideSeen must be true or false")
    }

    private fun failure(error: GetRelatedTitlesError, type: MediaType, tmdbId: Int): ApiException = when (error) {
        GetRelatedTitlesError.TmdbNotConfigured ->
            ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB API key is not configured (Admin > Configuration)")
        GetRelatedTitlesError.NotFound -> notFound("No ${type.apiName()} with TMDB id $tmdbId")
        GetRelatedTitlesError.TmdbUnavailable -> ApiException(HttpStatus.BAD_GATEWAY, "tmdb_error", "TMDB lookup failed")
    }
}
