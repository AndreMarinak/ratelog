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
    /** null clears the status. */
    val status: WatchStatus?,
)

data class SetWatchStatusResult(
    val previous: WatchStatus?,
    val current: WatchStatus?,
) {
    val changed: Boolean get() = previous != current
}

/** Sets, changes or clears a user's status on a title. Setting the same status again is a no-op. */
@Component
class SetWatchStatusHandler(
    private val mediaStatusRepository: MediaStatusRepository,
    private val movieRepository: MovieRepository,
    private val tvShowRepository: TvShowRepository,
) {
    @Transactional
    fun handle(command: SetWatchStatus): Either<SetWatchStatusError, SetWatchStatusResult> = either {
        val exists = when (command.mediaType) {
            MediaType.movie -> movieRepository.findById(Movie.Id(command.mediaId)) != null
            MediaType.tvshow -> tvShowRepository.findById(TvShow.Id(command.mediaId)) != null
        }
        ensure(exists) { SetWatchStatusError.MediaNotFound }

        val previous = mediaStatusRepository.find(command.userId, command.mediaType, command.mediaId)?.status

        when {
            command.status == previous -> Unit
            command.status == null -> mediaStatusRepository.delete(command.userId, command.mediaType, command.mediaId)
            else -> MediaStatus(
                userId = command.userId,
                mediaType = command.mediaType,
                mediaId = command.mediaId,
                status = command.status,
                updatedAtEpochMs = System.currentTimeMillis(),
            ).let(mediaStatusRepository::save)
        }

        SetWatchStatusResult(previous = previous, current = command.status)
    }
}
