package org.ratelog.tvshow.rating.show

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.TvRating
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.user.User
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

data class DeleteShowRating(
    val tvShowId: TvShow.Id,
    val userId: User.Id,
)

sealed interface DeleteShowRatingHandlerError {
    /** There is no whole-show score (season ratings are deleted one by one, on the show's page). */
    data object RatingNotFound : DeleteShowRatingHandlerError
}

/**
 * Deletes the whole-show score. Season ratings stay (the show's score is still their average); with none left the
 * rating row goes. Like every delete, the status (Watched) is left alone.
 */
@Component
class DeleteShowRatingHandler(
    private val tvRatingRepository: TvRatingRepository,
) {
    /** @return the rating left, or null when nothing is left. */
    @Transactional
    fun handle(command: DeleteShowRating): Either<DeleteShowRatingHandlerError, TvRating?> = either {
        val existing = tvRatingRepository.findByTvShowIdAndUserId(command.tvShowId, command.userId)
        if (existing?.showScore == null) raise(DeleteShowRatingHandlerError.RatingNotFound)
        val updated = existing.withoutShowScore()
        if (updated.isEmpty) {
            tvRatingRepository.deleteById(existing.id!!)
            null
        } else {
            tvRatingRepository.save(updated)
            updated
        }
    }
}
