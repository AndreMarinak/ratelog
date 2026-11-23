package org.ratelog.movie.rating.add

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.RatingInput
import org.ratelog.RatingInputError
import org.ratelog.Review
import org.ratelog.movie.Movie
import org.ratelog.movie.rating.Rating
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.user.User
import org.springframework.stereotype.Component
import java.time.Instant
import org.springframework.transaction.annotation.Transactional

/** Either all five categories (detailed) or only [score] (quick rating). */
data class AddRating(
    val movieId: Movie.Id,
    val userId: User.Id,
    val directing: Double?,
    val cinematography: Double?,
    val acting: Double?,
    val soundtrack: Double?,
    val screenplay: Double?,
    val reviewTitle: String?,
    val reviewText: String?,
    val score: Double? = null,
)

@Component
class AddRatingHandler(
    private val ratingRepository: RatingRepository,
) {
    @Transactional
    fun handle(command: AddRating): Either<AddRatingHandlerError, Unit> = either {
        val input = RatingInput.parse(
            command.score,
            command.directing,
            command.cinematography,
            command.acting,
            command.soundtrack,
            command.screenplay,
        ).mapLeft { error ->
            when (error) {
                RatingInputError.OutOfRange -> AddRatingHandlerError.InvalidRatingValue
                RatingInputError.InvalidCombination -> AddRatingHandlerError.InvalidRatingCombination
            }
        }.bind()

        val existingRating = ratingRepository.findByMovieIdAndUserId(command.movieId, command.userId)
        ensure(existingRating == null) { AddRatingHandlerError.RatingAlreadyExists }

        val review = parseReview(command.reviewTitle, command.reviewText).bind()

        Rating.create(command.movieId, command.userId, input, review, Instant.now())
            .let(ratingRepository::save)
    }

    private fun parseReview(title: String?, text: String?): Either<AddRatingHandlerError, Review?> =
        Review.parse(title, text)
            .mapLeft { error ->
                when (error) {
                    org.ratelog.ParseError.InvalidReviewTitle -> AddRatingHandlerError.InvalidReviewTitle
                    org.ratelog.ParseError.InvalidReviewText -> AddRatingHandlerError.InvalidReviewText
                    else -> AddRatingHandlerError.InvalidReviewText
                }
            }
}
