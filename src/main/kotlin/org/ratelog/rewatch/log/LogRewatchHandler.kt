package org.ratelog.rewatch.log

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.MediaType
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieRepository
import org.ratelog.rewatch.Rewatch
import org.ratelog.rewatch.RewatchRepository
import org.ratelog.rewatch.RewatchSummary
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.user.User
import org.ratelog.watchlist.seen.ReleaseRules
import org.ratelog.watchlist.seen.SeenStateReader
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

data class LogRewatch(
    val userId: User.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id), not the TMDB id. */
    val mediaId: Long,
    /** When it was watched again; null = now. Never after now. */
    val watchedAtEpochMs: Long? = null,
)

sealed interface LogRewatchError {
    data object MediaNotFound : LogRewatchError

    /** Not seen yet: the first watch is "Seen" (the eye, or a rating), not a rewatch. */
    data object NotSeen : LogRewatchError

    /** Not out yet (released after today). */
    data object NotReleased : LogRewatchError

    /** A [LogRewatch.watchedAtEpochMs] in the future. */
    data object InvalidDate : LogRewatchError
}

data class LogRewatchResult(
    val rewatch: Rewatch,
    /** The title's rewatches after this one. */
    val summary: RewatchSummary,
    /** Rated already (the toast says "Update your rating?" rather than "Rate it?"). */
    val rated: Boolean,
)

/**
 * Fork (2026-11-24, user: "logging a rewatch must be one click"): "Watched again" on a title you've seen adds one row to
 * the rewatch log. Seen, status and rating are left alone (the toast offers "Update your rating?"). Refused on a title
 * that isn't seen (the first watch is Seen) or isn't out.
 */
@Component
class LogRewatchHandler(
    private val rewatchRepository: RewatchRepository,
    private val movieRepository: MovieRepository,
    private val tvShowRepository: TvShowRepository,
    private val seenStateReader: SeenStateReader,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @Transactional
    fun handle(command: LogRewatch): Either<LogRewatchError, LogRewatchResult> = either {
        val release = when (command.mediaType) {
            MediaType.movie -> movieRepository.findById(Movie.Id(command.mediaId))?.let { it.releaseDate to it.status?.value }
            MediaType.tvshow -> tvShowRepository.findById(TvShow.Id(command.mediaId))?.let { it.firstAirDate to it.status?.value }
        } ?: raise(LogRewatchError.MediaNotFound)

        val now = clock.millis()
        val at = command.watchedAtEpochMs ?: now
        ensure(at <= now) { LogRewatchError.InvalidDate }
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), clock.zone)
        ensure(!ReleaseRules.isUpcoming(command.mediaType, release.first, release.second, today)) { LogRewatchError.NotReleased }
        val state = seenStateReader.read(command.userId, command.mediaType, command.mediaId)
        ensure(state.seen) { LogRewatchError.NotSeen }

        val saved = rewatchRepository.add(Rewatch(null, command.userId, command.mediaType, command.mediaId, at))
        LogRewatchResult(saved, rewatchRepository.summary(command.userId, command.mediaType, command.mediaId), state.rated)
    }
}
