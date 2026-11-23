package org.ratelog.api

import org.ratelog.MediaType
import org.ratelog.api.media.Candidate
import org.ratelog.api.media.LocalMedia

/** Accepts the friendly spellings people (and Claude) actually use. */
fun parseMediaType(value: String?): MediaType? = when (value?.trim()?.lowercase()) {
    null, "" -> null
    "movie", "movies", "film" -> MediaType.movie
    "tv", "tvshow", "tv_show", "show", "series" -> MediaType.tvshow
    else -> throw badRequest("invalid_type", "type must be \"movie\" or \"tv\" (got \"$value\")")
}

fun MediaType.apiName(): String = when (this) {
    MediaType.movie -> "movie"
    MediaType.tvshow -> "tv"
}

data class TitleDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
)

data class CandidateDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val tmdbVoteAverage: Double?,
    val overview: String?,
)

fun LocalMedia.toDto() = TitleDto(tmdbId, type.apiName(), title, year, posterPath?.let(::posterUrl))

fun Candidate.toDto() = CandidateDto(tmdbId, type.apiName(), title, originalTitle, year, tmdbVoteAverage, overview?.take(300))

fun posterUrl(path: String) = "https://image.tmdb.org/t/p/w342$path"

/**
 * One title to add. Either `tmdbId` + `type`, or `title` (+ `year`, + `type` if known).
 * Extra fields are ignored, so a researched list can carry notes like `rank` or `source`.
 */
data class ItemInput(
    val tmdbId: Int? = null,
    val type: String? = null,
    val title: String? = null,
    val year: Int? = null,
    /** Optional tag names applied to the title once it is in the list (missing tags are created). */
    val tags: List<String>? = null,
    /** Fork: optional 1-based place to insert the title at (the rest shift down); omitted = appended. Past the end = last. */
    val position: Int? = null,
)

data class AddItemsRequest(val items: List<ItemInput> = emptyList())

data class CreateListRequest(
    val name: String? = null,
    /** Omitted = private, official or not. A private official list is hidden: only its owner sees it until it is public. */
    val isPublic: Boolean? = null,
    val items: List<ItemInput> = emptyList(),
    /** Admin tokens only (others get 403). Makes the list official; it is shown to everyone only if it is also public. */
    val isOfficial: Boolean? = null,
    /** Fork: show ranks on the list page (default true); false = a plain collection. */
    val numbered: Boolean? = null,
)

data class UpdateListRequest(
    val name: String? = null,
    val isPublic: Boolean? = null,
    /** Admin tokens only, on any list (others get 403). Never changes `isPublic`; send both to publish an official list. */
    val isOfficial: Boolean? = null,
    /** Fork: owner only, like name and visibility. */
    val numbered: Boolean? = null,
)

/** Fork: `PATCH /api/v1/lists/{id}/items/{type}/{tmdbId}`: move one title to a 1-based place. */
data class MoveItemRequest(val position: Int? = null)

/** Fork: one title of a list, by TMDB id + type (no lookups, the title must already be in the list). */
data class TitleRefInput(val tmdbId: Int? = null, val type: String? = null)

/** Fork: `PUT /api/v1/lists/{id}/order`: these titles first, in this order; the rest follow in their current order. */
data class SetOrderRequest(val items: List<TitleRefInput> = emptyList())

data class ResolveRequest(
    val title: String? = null,
    val year: Int? = null,
    val type: String? = null,
)
