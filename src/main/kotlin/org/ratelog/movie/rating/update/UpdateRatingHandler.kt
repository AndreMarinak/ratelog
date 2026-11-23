package org.ratelog.movie.rating.update

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.RatingInput
import org.ratelog.RatingInputError
import org.ratelog.Review
import org.ratelog.movie.Movie
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.user.User
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Either all five categories (detailed) or only [score] (quick rating); the rating switches mode to match. */
data class UpdateRating(
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
class UpdateRatingHandler(
    private val ratingRepository: RatingRepository,
) {
    @Transactional
    fun handle(command: UpdateRating): Either<UpdateRatingHandlerError, Unit> = either {
        val input = RatingInput.parse(
            command.score,
            command.directing,
            command.cinematography,
            command.acting,
            command.soundtrack,
            command.screenplay,
        ).mapLeft { error ->
            when (error) {
                RatingInputError.OutOfRange -> UpdateRatingHandlerError.InvalidRatingValue
                RatingInputError.InvalidCombination -> UpdateRatingHandlerError.InvalidRatingCombination
            }
        }.bind()

        val rating = ratingRepository.findByMovieIdAndUserId(command.movieId, command.userId)
            ?: raise(UpdateRatingHandlerError.RatingNotFound)

        val review = parseReview(command.reviewTitle, command.reviewText).bind()

        rating.withScores(input)
            .copy(review = review)
            .let(ratingRepository::save)
    }

    private fun parseReview(title: String?, text: String?): Either<UpdateRatingHandlerError, Review?> =
        Review.parse(title, text)
            .mapLeft { error ->
                when (error) {
                    org.ratelog.ParseError.InvalidReviewTitle -> UpdateRatingHandlerError.InvalidReviewTitle
                    org.ratelog.ParseError.InvalidReviewText -> UpdateRatingHandlerError.InvalidReviewText
                    else -> UpdateRatingHandlerError.InvalidReviewText
                }
            }
}
