package org.ratelog.api.recommendations

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.ApiException
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.parseMediaType
import org.ratelog.api.posterUrl
import org.ratelog.recommendations.foryou.ForYouPick
import org.ratelog.recommendations.foryou.ForYouSeed
import org.ratelog.recommendations.foryou.GetForYou
import org.ratelog.recommendations.foryou.GetForYouError
import org.ratelog.recommendations.foryou.GetForYouHandler
import org.ratelog.user.User
import org.ratelog.watchlist.seen.ReleaseRules
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

data class ForYouResponse(
    /** `movie`, `tv`, or `all` (both, like Home). */
    val type: String,
    /** The rated titles the recommendations start from, in the order they were merged. */
    val seeds: List<ForYouSeedDto>,
    val titles: List<ForYouTitleDto>,
)

data class ForYouSeedDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    /** Your score. */
    val score: Double,
)

data class ForYouTitleDto(
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
    /** The seed it was recommended for (the first, when several recommend it). */
    val because: ForYouBecauseDto,
)

data class ForYouBecauseDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
)

/**
 * `GET /api/v1/recommendations?type=movie|tv&limit=`: "Recommended for you", the row on Home (no `type`: movies and
 * TV) and Discover (`type`). Titles you have rated or given any status are never included. See
 * docs/api.md#recommended-for-you.
 */
@RestController
@RequestMapping("/api/v1/recommendations")
class ApiForYouController(
    private val handler: GetForYouHandler,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @GetMapping
    fun recommendations(
        @CurrentUser user: User,
        @RequestParam("type", required = false) type: String?,
        @RequestParam("limit", required = false) limit: String?,
    ): ForYouResponse {
        val mediaType = if (type.isNullOrBlank() || type.trim().lowercase() == "all") null
        else parseMediaType(type) ?: throw badRequest("invalid_type", "type must be \"movie\", \"tv\" or \"all\"")
        val max = parseLimit(limit)

        val forYou = handler.handle(GetForYou(user.id!!, user.metadataLang, mediaType, limit = max))
            .fold({ throw failure(it) }, { it })
        val today = LocalDate.now(clock)
        return ForYouResponse(
            type = mediaType?.apiName() ?: "all",
            seeds = forYou.seeds.map { it.toDto() },
            titles = forYou.picks.map { it.toDto(today) },
        )
    }

    private fun parseLimit(value: String?): Int {
        if (value.isNullOrBlank()) return DEFAULT_LIMIT
        val n = value.trim().toIntOrNull() ?: throw badRequest("invalid_parameter", "limit must be a whole number from 1 to $MAX_LIMIT")
        if (n !in 1..MAX_LIMIT) throw badRequest("invalid_parameter", "limit must be a whole number from 1 to $MAX_LIMIT")
        return n
    }

    private fun ForYouSeed.toDto() = ForYouSeedDto(tmdbId, type.apiName(), title, score)

    private fun ForYouPick.toDto(today: LocalDate) = ForYouTitleDto(
        tmdbId = title.tmdbId,
        type = title.type.apiName(),
        title = title.title,
        year = title.year,
        releaseDate = title.releaseDate?.toString(),
        posterUrl = title.posterPath?.let(::posterUrl),
        url = if (title.type == MediaType.movie) "/movie/${title.tmdbId}" else "/tv/${title.tmdbId}",
        tmdbVoteAverage = title.voteAverage,
        upcoming = ReleaseRules.isUpcoming(title.releaseDate, today),
        because = ForYouBecauseDto(because.tmdbId, because.type.apiName(), because.title),
    )

    private fun failure(error: GetForYouError): ApiException = when (error) {
        GetForYouError.TmdbNotConfigured ->
            ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB API key is not configured (Admin > Configuration)")
        GetForYouError.TmdbUnavailable -> ApiException(HttpStatus.BAD_GATEWAY, "tmdb_error", "TMDB lookup failed")
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 60
    }
}
