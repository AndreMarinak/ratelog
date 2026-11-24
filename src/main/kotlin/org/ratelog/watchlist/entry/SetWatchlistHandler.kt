package org.ratelog.watchlist.entry

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.MediaType
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieRepository
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.WatchlistEntry
import org.ratelog.watchlist.seen.SeenState
import org.ratelog.watchlist.seen.SeenStateReader
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

sealed interface SetWatchlistError {
    data object MediaNotFound : SetWatchlistError
}

data class SetWatchlist(
    val userId: User.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id), not the TMDB id. */
    val mediaId: Long,
    /** true adds it to the watchlist, false takes it off. */
    val on: Boolean,
)

data class SetWatchlistResult(
    val before: SeenState,
    val after: SeenState,
) {
    val changed: Boolean get() = before != after

    /** +1 / -1 / 0: how "seen" changed (only when adding clears a Watching / Dropped status). */
    val seenDelta: Int get() = (if (after.seen) 1 else 0) - (if (before.seen) 1 else 0)
}

/**
 * Fork (2026-11-24, user: "on movies I've already seen I should still be able to add it to the general watchlist"):
 * puts a title on the watchlist or takes it off, independent of its status.
 * - **Adding** keeps Watched and the rating, so a seen title stays seen (a rewatch, [WatchlistEntry.rewatch]). Watching and
 *   Dropped are replaced, as when Want to watch was a status (a show moves to To watch). Already on it: nothing changes.
 * - **Removing** only takes it off: a seen title stays seen, a Watched status stays.
 */
@Component
class SetWatchlistHandler(
    private val mediaStatusRepository: MediaStatusRepository,
    private val movieRepository: MovieRepository,
    private val tvShowRepository: TvShowRepository,
    private val seenStateReader: SeenStateReader,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @Transactional
    fun handle(command: SetWatchlist): Either<SetWatchlistError, SetWatchlistResult> = either {
        val exists = when (command.mediaType) {
            MediaType.movie -> movieRepository.findById(Movie.Id(command.mediaId)) != null
            MediaType.tvshow -> tvShowRepository.findById(TvShow.Id(command.mediaId)) != null
        }
        ensure(exists) { SetWatchlistError.MediaNotFound }

        val before = seenStateReader.read(command.userId, command.mediaType, command.mediaId)
        when {
            command.on && before.onWatchlist -> Unit
            command.on -> {
                if (before.status == WatchStatus.WATCHING || before.status == WatchStatus.DROPPED) {
                    mediaStatusRepository.delete(command.userId, command.mediaType, command.mediaId)
                }
                val seenNow = before.copy(status = before.status?.takeIf { it == WatchStatus.WATCHED }).seen
                mediaStatusRepository.addToWatchlist(
                    WatchlistEntry(command.userId, command.mediaType, command.mediaId, clock.millis(), rewatch = seenNow),
                )
            }
            else -> mediaStatusRepository.removeFromWatchlist(command.userId, command.mediaType, command.mediaId)
        }
        val after = if (command.on == before.onWatchlist) before else seenStateReader.read(command.userId, command.mediaType, command.mediaId)
        SetWatchlistResult(before, after)
    }
}
