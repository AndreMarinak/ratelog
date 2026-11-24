package org.ratelog.api.watchlist

import org.ratelog.annotations.CurrentUser
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.ApiException
import org.ratelog.api.media.ImportError
import org.ratelog.api.media.ImportException
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.notFound
import org.ratelog.api.parseMediaType
import org.ratelog.api.posterUrl
import org.ratelog.user.User
import org.ratelog.watchlist.entry.SetWatchlist
import org.ratelog.watchlist.entry.SetWatchlistHandler
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** A title's watchlist state after PUT / DELETE /api/v1/watchlist/{type}/{tmdbId}. */
data class WatchlistStateDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val onWatchlist: Boolean,
    /** Your own status (`watching`, `watched`, `dropped`) or null: being on the watchlist never changes it. */
    val status: String?,
    /** Rated, or status `watched`: adding a seen title keeps it seen (a rewatch). */
    val seen: Boolean,
    /** false when it already was in that state. */
    val changed: Boolean,
)

/**
 * Fork (2026-11-24): the watchlist on its own, independent of the status (see docs/api.md, "Watchlist").
 * PUT adds the title (saved locally first if needed), DELETE takes it off; neither touches Watched or the rating.
 * `/api/v1/statuses` with `want_to_watch` still works and does the same as PUT.
 */
@RestController
@RequestMapping("/api/v1/watchlist")
class ApiWatchlistController(
    private val handler: SetWatchlistHandler,
    private val mediaImporter: MediaImporter,
) {
    @PutMapping("/{type}/{tmdbId}")
    fun add(@CurrentUser user: User, @PathVariable type: String, @PathVariable tmdbId: Int): WatchlistStateDto {
        val mediaType = parseMediaType(type)!!
        if (tmdbId <= 0) throw badRequest("invalid_parameter", "tmdbId must be positive")
        val media = mediaImporter.ensure(tmdbId, mediaType, user.metadataLang).getOrElse { e ->
            throw if ((e as? ImportException)?.error == ImportError.TmdbNotConfigured) {
                ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB API key is not configured (Admin > Configuration)")
            } else notFound("TMDB has no ${mediaType.apiName()} with id $tmdbId")
        }
        return set(user, media, on = true)
    }

    @DeleteMapping("/{type}/{tmdbId}")
    fun remove(@CurrentUser user: User, @PathVariable type: String, @PathVariable tmdbId: Int): ResponseEntity<WatchlistStateDto> {
        val mediaType = parseMediaType(type)!!
        if (tmdbId <= 0) throw badRequest("invalid_parameter", "tmdbId must be positive")
        val media = mediaImporter.findLocal(tmdbId, mediaType, user.metadataLang)
            ?: throw notFound("That title is not on your watchlist")
        val dto = set(user, media, on = false)
        if (!dto.changed) throw notFound("That title is not on your watchlist")
        return ResponseEntity.ok(dto)
    }

    private fun set(user: User, media: org.ratelog.api.media.LocalMedia, on: Boolean): WatchlistStateDto {
        val result = handler.handle(SetWatchlist(user.id!!, media.type, media.id, on)).fold(
            { throw notFound("Title not found") },
            { it },
        )
        return WatchlistStateDto(
            tmdbId = media.tmdbId,
            type = media.type.apiName(),
            title = media.title,
            year = media.year,
            posterUrl = media.posterPath?.let(::posterUrl),
            onWatchlist = result.after.onWatchlist,
            status = result.after.status?.apiName,
            seen = result.after.seen,
            changed = result.changed,
        )
    }
}
