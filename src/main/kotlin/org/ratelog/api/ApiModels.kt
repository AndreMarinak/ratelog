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
)

data class AddItemsRequest(val items: List<ItemInput> = emptyList())

data class CreateListRequest(
    val name: String? = null,
    val isPublic: Boolean = false,
    val items: List<ItemInput> = emptyList(),
)

data class UpdateListRequest(
    val name: String? = null,
    val isPublic: Boolean? = null,
)

data class ResolveRequest(
    val title: String? = null,
    val year: Int? = null,
    val type: String? = null,
)
