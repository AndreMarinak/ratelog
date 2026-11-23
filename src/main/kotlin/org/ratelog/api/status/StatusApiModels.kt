package org.ratelog.api.status

import org.ratelog.api.CandidateDto
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.posterUrl
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.getwatchlist.WatchlistItem
import java.time.Instant

data class StatusItemDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val status: String,
    /** The user's own score, if rated. */
    val score: Double?,
    val updatedAt: String,
)

data class StatusListResponse(
    val items: List<StatusItemDto>,
    /** Titles per status (for the same `type` filter). */
    val counts: Map<String, Int>,
)

data class StatusChangeDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val status: String,
    val previousStatus: String?,
)

data class SetStatusRequest(val status: String? = null)

/**
 * One title to set a status on. Either `tmdbId` + `type`, or `title` (+ `year`, + `type` if known).
 * `status` may be left out when the request has a top-level default.
 */
data class StatusItemInput(
    val tmdbId: Int? = null,
    val type: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val status: String? = null,
)

data class BulkStatusRequest(
    /** Default for items without their own `status`. */
    val status: String? = null,
    val items: List<StatusItemInput> = emptyList(),
)

data class UnresolvedStatusItem(
    val input: StatusItemInput,
    /** not_found | ambiguous | invalid | tmdb_error */
    val reason: String,
    val message: String,
    val candidates: List<CandidateDto> = emptyList(),
)

data class BulkStatusResult(
    val updated: List<StatusChangeDto>,
    val unchanged: List<StatusChangeDto>,
    val unresolved: List<UnresolvedStatusItem>,
) {
    val summary: String
        get() = "${updated.size} updated, ${unchanged.size} unchanged, ${unresolved.size} unresolved"
}

/** Parses a required status, or throws a 400 `invalid_status`. */
fun parseStatus(value: String?): WatchStatus =
    WatchStatus.parse(value) ?: throw badRequest(
        "invalid_status",
        "status must be one of ${WatchStatus.entries.joinToString { "\"${it.apiName}\"" }} (got ${value?.let { "\"$it\"" } ?: "nothing"})",
    )

fun WatchlistItem.toDto() = StatusItemDto(
    tmdbId = tmdbId,
    type = mediaType.apiName(),
    title = title,
    year = year,
    posterUrl = posterPath?.let(::posterUrl),
    status = status.apiName,
    score = score,
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMs).toString(),
)

fun LocalMedia.toStatusChange(status: WatchStatus, previous: WatchStatus?) = StatusChangeDto(
    tmdbId = tmdbId,
    type = type.apiName(),
    title = title,
    year = year,
    posterUrl = posterPath?.let(::posterUrl),
    status = status.apiName,
    previousStatus = previous?.apiName,
)
