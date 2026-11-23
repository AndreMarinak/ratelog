package org.ratelog.api.status

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.api.ApiException
import org.ratelog.api.media.ImportError
import org.ratelog.api.media.ImportException
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.media.MatchResult
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.media.TitleResolver
import org.ratelog.api.notFound
import org.ratelog.api.parseMediaType
import org.ratelog.api.toDto
import org.ratelog.tmdb.TmdbError
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatusError
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/**
 * Sets statuses on titles given by TMDB id or by title + year, saving titles locally first if needed
 * (same matching and import as adding list items). One bad item never fails the batch;
 * it is reported in [BulkStatusResult.unresolved].
 */
@Service
class BulkStatusService(
    private val titleResolver: TitleResolver,
    private val mediaImporter: MediaImporter,
    private val setWatchStatusHandler: SetWatchStatusHandler,
) {
    fun apply(userId: User.Id, lang: Lang, defaultStatus: WatchStatus?, items: List<StatusItemInput>): BulkStatusResult {
        val updated = mutableListOf<StatusChangeDto>()
        val unchanged = mutableListOf<StatusChangeDto>()
        val unresolved = mutableListOf<UnresolvedStatusItem>()

        for (input in items) {
            val status = when {
                input.status.isNullOrBlank() -> defaultStatus
                else -> WatchStatus.parse(input.status)
            }
            if (status == null) {
                val message = if (input.status.isNullOrBlank()) "status is required (on the item or the request)"
                else "Unknown status \"${input.status}\"; use ${WatchStatus.entries.joinToString { it.apiName }}"
                unresolved += UnresolvedStatusItem(input, "invalid", message)
                continue
            }

            val target = try {
                resolveTarget(input)
            } catch (e: IllegalArgumentException) {
                unresolved += UnresolvedStatusItem(input, "invalid", e.message ?: "Invalid item")
                continue
            }

            when (target) {
                is Target.Unresolved -> unresolved += target.item
                is Target.Found -> {
                    val media = mediaImporter.ensure(target.tmdbId, target.type, lang).getOrElse { e ->
                        val notConfigured = (e as? ImportException)?.error == ImportError.TmdbNotConfigured
                        unresolved += UnresolvedStatusItem(
                            input,
                            if (notConfigured) "tmdb_error" else "not_found",
                            if (notConfigured) "TMDB API key is not configured"
                            else "TMDB has no ${target.type.name} with id ${target.tmdbId}",
                        )
                        continue
                    }
                    setWatchStatusHandler.handle(SetWatchStatus(userId, media.type, media.id, status)).fold(
                        { unresolved += UnresolvedStatusItem(input, "not_found", "Title is no longer available") },
                        { result ->
                            val dto = media.toStatusChange(status, result.previous)
                            if (result.changed) updated += dto else unchanged += dto
                        }
                    )
                }
            }
        }
        return BulkStatusResult(updated, unchanged, unresolved)
    }

    /** One title by TMDB id. Throws [ApiException] if it can't be found or saved. */
    fun setOne(userId: User.Id, lang: Lang, tmdbId: Int, type: MediaType, status: WatchStatus): StatusChangeDto {
        val media: LocalMedia = mediaImporter.ensure(tmdbId, type, lang).getOrElse { e ->
            throw if ((e as? ImportException)?.error == ImportError.TmdbNotConfigured) {
                ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB API key is not configured (Admin > Configuration)")
            } else notFound("TMDB has no ${type.name} with id $tmdbId")
        }
        val result = setWatchStatusHandler.handle(SetWatchStatus(userId, media.type, media.id, status)).fold(
            { error -> throw when (error) { SetWatchStatusError.MediaNotFound -> notFound("Title not found") } },
            { it }
        )
        return media.toStatusChange(status, result.previous)
    }

    private sealed interface Target {
        data class Found(val tmdbId: Int, val type: MediaType) : Target
        data class Unresolved(val item: UnresolvedStatusItem) : Target
    }

    /** Same rules as list items: `tmdbId` needs a `type`; titles must match exactly (see docs/api.md). */
    private fun resolveTarget(input: StatusItemInput): Target {
        val type = try {
            parseMediaType(input.type)
        } catch (e: Exception) {
            throw IllegalArgumentException(e.message)
        }

        if (input.tmdbId != null) {
            // Movie and TV ids are separate namespaces on TMDB, so the id alone is ambiguous.
            require(type != null) { "type is required when tmdbId is given (\"movie\" or \"tv\")" }
            require(input.tmdbId > 0) { "tmdbId must be positive" }
            return Target.Found(input.tmdbId, type)
        }

        val title = input.title?.trim()
        require(!title.isNullOrEmpty()) { "Each item needs either tmdbId + type, or a title" }

        return titleResolver.resolve(title, input.year, type).fold(
            { error ->
                Target.Unresolved(
                    UnresolvedStatusItem(
                        input, "tmdb_error",
                        if (error == TmdbError.ApiKeyMissing) "TMDB API key is not configured" else "TMDB lookup failed",
                    )
                )
            },
            { match ->
                when (match) {
                    is MatchResult.Matched -> Target.Found(match.candidate.tmdbId, match.candidate.type)
                    is MatchResult.Ambiguous -> Target.Unresolved(
                        UnresolvedStatusItem(
                            input, "ambiguous",
                            "Several titles match; retry with year, type, or tmdbId from candidates",
                            match.candidates.map { it.toDto() },
                        )
                    )
                    is MatchResult.NotFound -> Target.Unresolved(
                        UnresolvedStatusItem(
                            input, "not_found",
                            "No exact title match; retry with tmdbId from candidates or a corrected title",
                            match.candidates.map { it.toDto() },
                        )
                    )
                }
            }
        )
    }
}
