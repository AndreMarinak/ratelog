package org.ratelog.movie.rating.tile

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.MediaType
import org.ratelog.movie.Movie
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.movie.rating.delete.DeleteRating
import org.ratelog.movie.rating.delete.DeleteRatingHandler
import org.ratelog.movie.rating.delete.DeleteRatingHandlerError
import org.ratelog.user.User
import org.ratelog.watchlist.seen.SeenStateReader
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

data class TileDeleteRating(
    val userId: User.Id,
    /** Local id (movies.id). */
    val movieId: Movie.Id,
)

/**
 * "Delete rating" in a tile's ★ picker (after its own confirm step), through upstream's [DeleteRatingHandler].
 * Only quick ratings: a detailed rating (five categories, maybe a review) is deleted on the title page, where you can
 * see what goes. Like every other delete, the status is left alone: a movie that rating had marked Watched stays seen.
 */
@Component
class TileDeleteRatingHandler(
    private val ratingRepository: RatingRepository,
    private val deleteRatingHandler: DeleteRatingHandler,
    private val seenStateReader: SeenStateReader,
) {
    @Transactional
    fun handle(command: TileDeleteRating): Either<TileRateHandlerError, TileRateResult> = either {
        val existing = ratingRepository.findByMovieIdAndUserId(command.movieId, command.userId)
            ?: raise(TileRateHandlerError.RatingNotFound)
        if (!existing.isQuick) raise(TileRateHandlerError.DetailedRating(existing.score))

        val before = seenStateReader.read(command.userId, MediaType.movie, command.movieId.value)
        deleteRatingHandler.handle(DeleteRating(command.movieId, command.userId)).mapLeft { error ->
            when (error) {
                DeleteRatingHandlerError.MovieNotFound -> TileRateHandlerError.MovieNotFound
                DeleteRatingHandlerError.RatingNotFound -> TileRateHandlerError.RatingNotFound
            }
        }.bind()
        val after = seenStateReader.read(command.userId, MediaType.movie, command.movieId.value)
        TileRateResult(TileRateOutcome.DELETED, before, after, score = null)
    }
}
