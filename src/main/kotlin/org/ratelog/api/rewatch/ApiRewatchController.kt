package org.ratelog.api.rewatch

import org.ratelog.annotations.CurrentUser
import org.ratelog.api.ApiException
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.notFound
import org.ratelog.api.parseMediaType
import org.ratelog.api.posterUrl
import org.ratelog.rewatch.delete.DeleteRewatch
import org.ratelog.rewatch.delete.DeleteRewatchHandler
import org.ratelog.rewatch.log.LogRewatch
import org.ratelog.rewatch.log.LogRewatchError
import org.ratelog.rewatch.log.LogRewatchHandler
import org.ratelog.user.User
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Optional body of POST /api/v1/rewatches/{type}/{tmdbId}. */
data class LogRewatchRequest(
    /** `2026-11-20` (noon that day, in the server's time zone) or an ISO instant; not in the future. Default: now. */
    val watchedAt: String? = null,
)

/** One logged rewatch, and the title's count after the call. */
data class RewatchDto(
    /** The rewatch's id (DELETE /api/v1/rewatches/{id} removes it). */
    val id: Long,
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val watchedAt: String,
    /** 1 (the first watch) + rewatches. */
    val watchCount: Int,
    /** The latest rewatch. */
    val lastWatchedAt: String?,
)

/**
 * Fork (2026-11-24, rewatch log): log a rewatch of a title you've seen, or remove one (see docs/api.md, "Rewatches").
 * The first watch is the seen state (status `watched`, or rated); these are the times after it.
 */
@RestController
@RequestMapping("/api/v1/rewatches")
class ApiRewatchController(
    private val logHandler: LogRewatchHandler,
    private val deleteHandler: DeleteRewatchHandler,
    private val mediaImporter: MediaImporter,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @PostMapping("/{type}/{tmdbId}")
    fun log(
        @CurrentUser user: User,
        @PathVariable type: String,
        @PathVariable tmdbId: Int,
        @RequestBody(required = false) body: LogRewatchRequest?,
    ): ResponseEntity<RewatchDto> {
        val mediaType = parseMediaType(type) ?: throw badRequest("invalid_type", "type must be \"movie\" or \"tv\"")
        if (tmdbId <= 0) throw badRequest("invalid_parameter", "tmdbId must be a positive integer")
        val at = body?.watchedAt?.takeIf { it.isNotBlank() }?.let(::parseWatchedAt)
        // A seen title is always stored already: one that isn't has never been seen.
        val media = mediaImporter.findLocal(tmdbId, mediaType, user.metadataLang) ?: throw notSeen()
        val result = logHandler.handle(LogRewatch(user.id!!, mediaType, media.id, at)).fold(
            { error ->
                throw when (error) {
                    LogRewatchError.MediaNotFound -> notFound("Title not found")
                    LogRewatchError.NotSeen -> notSeen()
                    LogRewatchError.NotReleased -> ApiException(HttpStatus.CONFLICT, "not_released", "That title isn't out yet")
                    LogRewatchError.InvalidDate -> badRequest("invalid_date", "watchedAt can't be in the future")
                }
            },
            { it },
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(
            RewatchDto(
                id = result.rewatch.id!!,
                tmdbId = media.tmdbId,
                type = mediaType.apiName(),
                title = media.title,
                year = media.year,
                posterUrl = media.posterPath?.let(::posterUrl),
                watchedAt = Instant.ofEpochMilli(result.rewatch.watchedAtEpochMs).toString(),
                watchCount = result.summary.watchCount,
                lastWatchedAt = result.summary.lastAtEpochMs?.let { Instant.ofEpochMilli(it).toString() },
            ),
        )
    }

    @DeleteMapping("/{id}")
    fun delete(@CurrentUser user: User, @PathVariable id: Long): ResponseEntity<Void> {
        deleteHandler.handle(DeleteRewatch(user.id!!, id)).onLeft { throw notFound("No rewatch $id") }
        return ResponseEntity.noContent().build()
    }

    private fun notSeen() =
        ApiException(HttpStatus.CONFLICT, "not_seen", "Mark the title seen first (status watched, or rate it): rewatches are the times after the first")

    private fun parseWatchedAt(value: String): Long {
        val v = value.trim()
        return try {
            if (v.length == 10) LocalDate.parse(v).atTime(LocalTime.NOON).atZone(clock.zone).toInstant().toEpochMilli()
            else Instant.parse(v).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw badRequest("invalid_date", "watchedAt must be a date (2026-11-20) or an ISO instant (2026-11-20T21:30:00Z)")
        }
    }
}
