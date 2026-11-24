package org.ratelog.rewatch.delete

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.rewatch.Rewatch
import org.ratelog.rewatch.RewatchRepository
import org.ratelog.rewatch.RewatchSummary
import org.ratelog.user.User
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

data class DeleteRewatch(val userId: User.Id, val id: Long)

sealed interface DeleteRewatchError {
    /** No such row, or another user's. */
    data object NotFound : DeleteRewatchError
}

data class DeleteRewatchResult(
    val deleted: Rewatch,
    /** The title's rewatches left. */
    val summary: RewatchSummary,
)

/** Removes one logged rewatch (the toast's Undo, the watchlist's Undo, the API). Only your own. */
@Component
class DeleteRewatchHandler(
    private val rewatchRepository: RewatchRepository,
) {
    /** The user's own row, to check what it belongs to before deleting it (the watchlist's Undo). */
    fun find(userId: User.Id, id: Long): Rewatch? = rewatchRepository.find(userId, id)

    @Transactional
    fun handle(command: DeleteRewatch): Either<DeleteRewatchError, DeleteRewatchResult> = either {
        val row = rewatchRepository.find(command.userId, command.id) ?: raise(DeleteRewatchError.NotFound)
        rewatchRepository.delete(command.userId, command.id)
        DeleteRewatchResult(row, rewatchRepository.summary(command.userId, row.mediaType, row.mediaId))
    }
}
