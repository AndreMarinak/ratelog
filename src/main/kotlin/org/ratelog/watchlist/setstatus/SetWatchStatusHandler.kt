package org.ratelog.watchlist.setstatus

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.MediaType
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieRepository
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.entry.SetWatchlist
import org.ratelog.watchlist.entry.SetWatchlistHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

sealed interface SetWatchStatusError {
    data object MediaNotFound : SetWatchStatusError
}

data class SetWatchStatus(
    val userId: User.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id), not the TMDB id. */
    val mediaId: Long,
    /** null clears the status and takes the title off the watchlist. */
    val status: WatchStatus?,
)

/**
 * [previous] and [current] are the effective statuses ([WatchStatus.effective]: Want to watch for a title only on the
 * watchlist), so callers that knew one status keep working; [wasOnWatchlist] / [onWatchlist] say the rest.
 */
data class SetWatchStatusResult(
    val previous: WatchStatus?,
    val current: WatchStatus?,
    val wasOnWatchlist: Boolean = previous == WatchStatus.WANT_TO_WATCH,
    val onWatchlist: Boolean = current == WatchStatus.WANT_TO_WATCH,
) {
    val changed: Boolean get() = previous != current || wasOnWatchlist != onWatchlist
}

/**
 * Sets, changes or clears a user's status on a title. Setting the same status again is a no-op.
 * Fork (2026-11-24), with the watchlist in its own table:
 * - `want_to_watch` puts it on the watchlist ([SetWatchlistHandler]): Watched and the rating stay (a rewatch), Watching
 *   and Dropped are replaced.
 * - `watched` keeps a rewatch on the watchlist; a title added while unseen leaves it (you've seen it now).
 * - `watching` / `dropped` take it off the watchlist (it moves to that tab, as before).
 * - null (no status) clears both, like the one status it used to be.
 */
@Component
class SetWatchStatusHandler(
    private val mediaStatusRepository: MediaStatusRepository,
    private val movieRepository: MovieRepository,
    private val tvShowRepository: TvShowRepository,
    private val setWatchlistHandler: SetWatchlistHandler,
) {
    @Transactional
    fun handle(command: SetWatchStatus): Either<SetWatchStatusError, SetWatchStatusResult> = either {
        val exists = when (command.mediaType) {
            MediaType.movie -> movieRepository.findById(Movie.Id(command.mediaId)) != null
            MediaType.tvshow -> tvShowRepository.findById(TvShow.Id(command.mediaId)) != null
        }
        ensure(exists) { SetWatchStatusError.MediaNotFound }

        val previous = mediaStatusRepository.find(command.userId, command.mediaType, command.mediaId)?.status
        val entry = mediaStatusRepository.findWatchlistEntry(command.userId, command.mediaType, command.mediaId)
        val wasOnWatchlist = entry != null

        when (command.status) {
            WatchStatus.WANT_TO_WATCH ->
                setWatchlistHandler.handle(SetWatchlist(command.userId, command.mediaType, command.mediaId, on = true))
                    .mapLeft { SetWatchStatusError.MediaNotFound }.bind()

            null -> {
                if (previous != null) mediaStatusRepository.delete(command.userId, command.mediaType, command.mediaId)
                if (wasOnWatchlist) mediaStatusRepository.removeFromWatchlist(command.userId, command.mediaType, command.mediaId)
            }

            else -> {
                if (command.status != previous) {
                    MediaStatus(
                        userId = command.userId,
                        mediaType = command.mediaType,
                        mediaId = command.mediaId,
                        status = command.status,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ).let(mediaStatusRepository::save)
                }
                val keep = command.status == WatchStatus.WATCHED && entry?.rewatch == true
                if (wasOnWatchlist && !keep) mediaStatusRepository.removeFromWatchlist(command.userId, command.mediaType, command.mediaId)
            }
        }

        val current = mediaStatusRepository.find(command.userId, command.mediaType, command.mediaId)?.status
        val onWatchlist = mediaStatusRepository.findWatchlistEntry(command.userId, command.mediaType, command.mediaId) != null
        SetWatchStatusResult(
            previous = WatchStatus.effective(previous, wasOnWatchlist),
            current = WatchStatus.effective(current, onWatchlist),
            wasOnWatchlist = wasOnWatchlist,
            onWatchlist = onWatchlist,
        )
    }
}
