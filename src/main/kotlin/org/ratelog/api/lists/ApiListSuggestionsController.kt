package org.ratelog.api.lists

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.ApiException
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.forbidden
import org.ratelog.api.notFound
import org.ratelog.api.posterUrl
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.official.OfficialListRepository
import org.ratelog.customlist.suggest.GetListSuggestions
import org.ratelog.customlist.suggest.GetListSuggestionsError
import org.ratelog.customlist.suggest.GetListSuggestionsHandler
import org.ratelog.customlist.suggest.ListSuggestion
import org.ratelog.customlist.suggest.SuggestionReason
import org.ratelog.customlist.suggest.TitleKey
import org.ratelog.customlist.suggest.web.ListSuggestionsController
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

data class ListSuggestionsResponse(
    val listId: Long,
    /** Titles in the list. */
    val listSize: Int,
    /** How many of them the suggestions come from (at most 10). */
    val basedOn: Int,
    /** Suggestions left after these (send these back in `skip` to get them). */
    val remaining: Int,
    val suggestions: List<ListSuggestionDto>,
)

data class ListSuggestionDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val releaseDate: String?,
    val posterUrl: String?,
    val url: String,
    val tmdbVoteAverage: Double?,
    val upcoming: Boolean,
    /** The tiles' Seen rule: a movie rated or Watched; a show Watched, or rated with no status. */
    val seen: Boolean,
    val status: String?,
    val userScore: Double?,
    /** `series` (an entry of a series the list has titles of) or `recommended`. */
    val reason: String,
    /** How many of the sampled list titles recommend it on TMDB. */
    val recommendedBy: Int,
)

/**
 * `GET /api/v1/lists/{id}/suggestions[?limit=20&skip=movie:862,tv:1396]`: what "Suggested for this list" shows on
 * the list page, best first. Your own lists only. See docs/api.md#list-suggestions.
 */
@RestController
@RequestMapping("/api/v1/lists")
class ApiListSuggestionsController(
    private val handler: GetListSuggestionsHandler,
    private val officialLists: OfficialListRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @GetMapping("/{id}/suggestions")
    fun suggestions(
        @CurrentUser user: User,
        @PathVariable id: Long,
        @RequestParam("limit", required = false) limit: Int?,
        @RequestParam("skip", required = false) skip: String?,
    ): ListSuggestionsResponse {
        val size = limit ?: GetListSuggestionsHandler.BATCH_SIZE
        if (size !in 1..GetListSuggestionsHandler.MAX_LIMIT) {
            throw badRequest("invalid_parameter", "limit must be between 1 and ${GetListSuggestionsHandler.MAX_LIMIT}")
        }
        val listId = CustomList.Id(id)
        val result = handler.handle(GetListSuggestions(listId, user.id!!, user.metadataLang, TitleKey.parseList(skip, ListSuggestionsController.MAX_SKIP), size))
            .fold({ throw failure(it, listId) }, { it })
        val today = LocalDate.now(clock)
        return ListSuggestionsResponse(
            listId = id,
            listSize = result.listSize,
            basedOn = result.basedOn,
            remaining = result.remaining,
            suggestions = result.suggestions.map { it.toDto(today) },
        )
    }

    private fun ListSuggestion.toDto(today: LocalDate) = ListSuggestionDto(
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
        status = mark?.status?.apiName,
        userScore = mark?.score,
        reason = if (reason == SuggestionReason.SERIES) "series" else "recommended",
        recommendedBy = recommendedBy,
    )

    /** Like the other list endpoints: someone else's list is 404, unless it is official (readable by all), then 403. */
    private fun failure(error: GetListSuggestionsError, listId: CustomList.Id): ApiException = when (error) {
        GetListSuggestionsError.ListNotFound -> notFound("List ${listId.value} not found")
        GetListSuggestionsError.NotOwner ->
            if (officialLists.isOfficial(listId)) forbidden("Suggestions are only for your own lists")
            else notFound("List ${listId.value} not found")
        GetListSuggestionsError.TmdbNotConfigured ->
            ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB API key is not configured (Admin > Configuration)")
        GetListSuggestionsError.TmdbUnavailable -> ApiException(HttpStatus.BAD_GATEWAY, "tmdb_error", "TMDB lookup failed")
    }
}
