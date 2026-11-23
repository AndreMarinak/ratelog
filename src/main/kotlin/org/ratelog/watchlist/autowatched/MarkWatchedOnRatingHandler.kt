package org.ratelog.watchlist.autowatched

import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

data class MarkWatchedOnRating(
    val userId: User.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id). */
    val mediaId: Long,
)

/**
 * Rating a title means it was seen: no status, Want to watch and Watching become Watched.
 * Watched and Dropped are left as they are.
 */
@Component
class MarkWatchedOnRatingHandler(
    private val mediaStatusRepository: MediaStatusRepository,
) {
    /** @return true if the status changed to Watched. */
    @Transactional
    fun handle(command: MarkWatchedOnRating): Boolean {
        val current = mediaStatusRepository.find(command.userId, command.mediaType, command.mediaId)?.status
        if (current != null && !current.isUnfinished) return false

        MediaStatus(
            userId = command.userId,
            mediaType = command.mediaType,
            mediaId = command.mediaId,
            status = WatchStatus.WATCHED,
            updatedAtEpochMs = System.currentTimeMillis(),
        ).let(mediaStatusRepository::save)
        return true
    }
}
