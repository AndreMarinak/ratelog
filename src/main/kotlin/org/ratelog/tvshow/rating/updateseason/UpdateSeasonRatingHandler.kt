package org.ratelog.tvshow.rating.updateseason

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.RatingInput
import org.ratelog.RatingInputError
import org.ratelog.Review
import org.ratelog.SeasonNumber
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.user.User
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Either all five categories (detailed) or only [score] (quick rating); the season switches mode to match. */
data class UpdateSeasonRating(
    val tvShowId: TvShow.Id,
    val seasonNumber: SeasonNumber,
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
class UpdateSeasonRatingHandler(
    private val tvRatingRepository: TvRatingRepository,
) {
    @Transactional
    fun handle(command: UpdateSeasonRating): Either<UpdateSeasonRatingHandlerError, Unit> = either {
        val input = RatingInput.parse(
            command.score,
            command.directing,
            command.cinematography,
            command.acting,
            command.soundtrack,
            command.screenplay,
        ).mapLeft { error ->
            when (error) {
                RatingInputError.OutOfRange -> UpdateSeasonRatingHandlerError.InvalidRatingValue
                RatingInputError.InvalidCombination -> UpdateSeasonRatingHandlerError.InvalidRatingCombination
            }
        }.bind()

        val tvRating = tvRatingRepository.findByTvShowIdAndUserId(command.tvShowId, command.userId)
            ?: raise(UpdateSeasonRatingHandlerError.RatingNotFound)

        ensure(tvRating.seasonRatings.any { it.seasonNumber == command.seasonNumber }) {
            UpdateSeasonRatingHandlerError.SeasonRatingNotFound
        }

        val review = parseReview(command.reviewTitle, command.reviewText).bind()

        tvRating.updateSeasonRating(
            seasonNumber = command.seasonNumber,
            input = input,
            review = review,
        ).let(tvRatingRepository::save)
    }

    private fun parseReview(title: String?, text: String?): Either<UpdateSeasonRatingHandlerError, Review?> =
        Review.parse(title, text)
            .mapLeft { error ->
                when (error) {
                    org.ratelog.ParseError.InvalidReviewTitle -> UpdateSeasonRatingHandlerError.InvalidReviewTitle
                    org.ratelog.ParseError.InvalidReviewText -> UpdateSeasonRatingHandlerError.InvalidReviewText
                    else -> UpdateSeasonRatingHandlerError.InvalidReviewText
                }
            }
}
