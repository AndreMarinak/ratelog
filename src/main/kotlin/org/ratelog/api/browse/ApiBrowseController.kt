package org.ratelog.api.browse

import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.ApiException
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.posterUrl
import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowseGenres
import org.ratelog.browse.BrowseParamParser
import org.ratelog.browse.BrowseParams
import org.ratelog.browse.discover.BrowseError
import org.ratelog.browse.discover.BrowseHandler
import org.ratelog.browse.discover.BrowseQuery
import org.ratelog.browse.discover.BrowseTile
import org.ratelog.user.User
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

data class BrowseResponse(
    val type: String,
    /** The filters as understood (normalized), e.g. `genre` as a slug. */
    val filters: BrowseFiltersDto,
    val page: Int,
    /** Pass as `page` for more; null at the end. Can skip numbers when hidden titles were topped up. */
    val nextPage: Int?,
    val totalPages: Int,
    val totalResults: Int,
    /** Parameters that were valid but not applied (e.g. `provider` with `popular=week`, `sort` next to `popular`). */
    val ignored: List<String>,
    val results: List<BrowseTitleDto>,
)

data class BrowseFiltersDto(
    val decade: String?,
    val year: String?,
    val minRating: Double?,
    val genre: String?,
    val genreId: Int?,
    /** The first of [providers] (kept for clients written when only one service could be chosen). */
    val provider: Int?,
    /** Every chosen streaming service (TMDB provider ids, sorted); a title on any of them matches. */
    val providers: List<Int>,
    val language: String?,
    val runtime: String?,
    val popular: String?,
    val sort: String?,
    val hideRated: Boolean,
    val hideWatchlist: Boolean,
    val hideSeen: Boolean,
)

data class BrowseTitleDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val tmdbVoteAverage: Double?,
    val url: String,
    /** Your own score, null when unrated. */
    val userScore: Double?,
    /** Your watch status (`want_to_watch`, `watching`, `watched`, `dropped`) or null. */
    val status: String?,
)

/**
 * TMDB discover / trending with the Discover page's filters. `/api/v1/discover` is the same endpoint under the page's
 * new name. Unlike the page, seen titles are only hidden with `hideSeen=true`. See docs/api.md#browse--discover.
 */
@RestController
@RequestMapping("/api/v1/browse", "/api/v1/discover")
class ApiBrowseController(
    private val handler: BrowseHandler,
    private val clock: Clock = Clock.systemUTC(),
) {
    @GetMapping
    fun browse(@CurrentUser user: User, params: BrowseParams): BrowseResponse {
        val parsed = BrowseParamParser.parse(params, LocalDate.now(clock), hideSeenByDefault = false)
        parsed.invalid.firstOrNull()?.let { bad ->
            throw badRequest(if (bad.name == "type") "invalid_type" else "invalid_parameter", "${bad.name}: ${bad.reason} (got \"${bad.value}\")")
        }
        val filters = parsed.filters
        val result = handler.handle(
            BrowseQuery(
                userId = user.id!!,
                lang = user.metadataLang,
                region = WatchRegion.fromLang(user.metadataLang),
                filters = filters,
                page = parsed.page,
            ),
        ).fold({ throw it.toApiException() }, { it })

        return BrowseResponse(
            type = filters.type.apiName(),
            filters = filters.toDto(),
            page = result.page,
            nextPage = result.nextPage,
            totalPages = result.totalPages,
            totalResults = result.totalResults,
            ignored = (parsed.dropped + result.ignored).distinct(),
            results = result.tiles.map { it.toDto() },
        )
    }

    private fun BrowseFilters.toDto() = BrowseFiltersDto(
        decade = decade?.param,
        year = year?.param,
        minRating = minRating,
        genre = genre?.let(BrowseGenres::slug),
        genreId = genre?.tmdbId,
        provider = providers.firstOrNull(),
        providers = providers,
        language = language,
        runtime = runtime?.param,
        popular = popular?.param,
        sort = sort?.param,
        hideRated = hideRated,
        hideWatchlist = hideWatchlist,
        hideSeen = hideSeen,
    )

    private fun BrowseTile.toDto() = BrowseTitleDto(
        tmdbId = title.tmdbId,
        type = title.type.apiName(),
        title = title.title,
        year = title.year,
        posterUrl = title.posterPath?.let(::posterUrl),
        tmdbVoteAverage = title.voteAverage,
        url = if (title.type == MediaType.movie) "/movie/${title.tmdbId}" else "/tv/${title.tmdbId}",
        userScore = mark?.score,
        status = mark?.status?.apiName,
    )

    private fun BrowseError.toApiException() = when (this) {
        BrowseError.TmdbNotConfigured -> ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB API key is not configured (Admin > Configuration)")
        BrowseError.TmdbKeyRejected -> ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB rejected the API key (Admin > Configuration)")
        BrowseError.TmdbUnavailable -> ApiException(HttpStatus.BAD_GATEWAY, "tmdb_error", "TMDB lookup failed")
    }
}
