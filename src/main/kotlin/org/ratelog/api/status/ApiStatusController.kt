package org.ratelog.api.status

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.ApiException
import org.ratelog.api.badRequest
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.notFound
import org.ratelog.api.parseMediaType
import org.ratelog.rewatch.RewatchRepository
import org.ratelog.user.User
import org.ratelog.watchlist.getwatchlist.GetWatchlist
import org.ratelog.watchlist.getwatchlist.GetWatchlistHandler
import org.ratelog.watchlist.setstatus.SetWatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Watch statuses (watchlist). See docs/api.md. */
@RestController
@RequestMapping("/api/v1/statuses")
class ApiStatusController(
    private val getWatchlistHandler: GetWatchlistHandler,
    private val setWatchStatusHandler: SetWatchStatusHandler,
    private val bulkStatusService: BulkStatusService,
    private val mediaImporter: MediaImporter,
    /** Fork (2026-11-24, rewatch log): `watchCount` / `lastWatchedAt` per title. */
    private val rewatchRepository: RewatchRepository? = null,
) {
    /** Optional filters: `status` (want_to_watch | watching | watched | dropped) and `type` (movie | tv). */
    @GetMapping
    fun list(
        @CurrentUser user: User,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) type: String?,
    ): StatusListResponse {
        val statusFilter = status?.takeIf { it.isNotBlank() }?.let(::parseStatus)
        val watchlist = getWatchlistHandler.handle(GetWatchlist(user.id!!, statusFilter, parseMediaType(type), user.metadataLang))
        // Two queries at most (one per type), only for the seen titles.
        val rewatches = MediaType.entries.associateWith { t ->
            val ids = watchlist.items.filter { it.mediaType == t && it.seen }.map { it.mediaId }
            rewatchRepository?.summaries(user.id, t, ids).orEmpty()
        }
        return StatusListResponse(
            items = watchlist.items.map { it.toDto(rewatches[it.mediaType]?.get(it.mediaId)) },
            counts = watchlist.counts.entries.associate { it.key.apiName to it.value },
        )
    }

    /** Set or change one title's status. The title is saved locally first if it never was. */
    @PutMapping("/{type}/{tmdbId}")
    fun set(
        @CurrentUser user: User,
        @PathVariable type: String,
        @PathVariable tmdbId: Int,
        @RequestBody body: SetStatusRequest,
    ): StatusChangeDto {
        val mediaType = parseMediaType(type)!!
        checkTmdbId(tmdbId)
        return bulkStatusService.setOne(user.id!!, user.metadataLang, tmdbId, mediaType, parseStatus(body.status))
    }

    @DeleteMapping("/{type}/{tmdbId}")
    fun clear(
        @CurrentUser user: User,
        @PathVariable type: String,
        @PathVariable tmdbId: Int,
    ): ResponseEntity<Unit> {
        val mediaType = parseMediaType(type)!!
        checkTmdbId(tmdbId)
        val local = mediaImporter.findLocal(tmdbId, mediaType, user.metadataLang)
            ?: throw notFound("That title has no status")
        val result = setWatchStatusHandler.handle(SetWatchStatus(user.id!!, mediaType, local.id, null))
            .fold({ throw notFound("That title has no status") }, { it })
        if (result.previous == null) throw notFound("That title has no status")
        return ResponseEntity.noContent().build()
    }

    /** Bulk set. Each item: `{tmdbId, type, status}` or `{title, year?, type?, status}`; top-level `status` is the default. */
    @PostMapping
    fun bulk(@CurrentUser user: User, @RequestBody body: BulkStatusRequest): BulkStatusResult {
        if (body.items.isEmpty()) throw badRequest("invalid_parameter", "items must not be empty")
        if (body.items.size > MAX_ITEMS_PER_REQUEST) {
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_items", "Send at most $MAX_ITEMS_PER_REQUEST items per request")
        }
        val defaultStatus = body.status?.takeIf { it.isNotBlank() }?.let(::parseStatus)
        return bulkStatusService.apply(user.id!!, user.metadataLang, defaultStatus, body.items)
    }

    private fun checkTmdbId(tmdbId: Int) {
        if (tmdbId <= 0) throw badRequest("invalid_parameter", "tmdbId must be positive")
    }

    companion object {
        const val MAX_ITEMS_PER_REQUEST = 100
    }
}
