package org.ratelog.movie.rating.tile

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import org.ratelog.MediaType
import org.ratelog.Score
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieRepository
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.movie.rating.add.AddRating
import org.ratelog.movie.rating.add.AddRatingHandler
import org.ratelog.movie.rating.add.AddRatingHandlerError
import org.ratelog.movie.rating.update.UpdateRating
import org.ratelog.movie.rating.update.UpdateRatingHandler
import org.ratelog.movie.rating.update.UpdateRatingHandlerError
import org.ratelog.user.User
import org.ratelog.watchlist.seen.ReleaseRules
import org.ratelog.watchlist.seen.SeenState
import org.ratelog.watchlist.seen.SeenStateReader
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/** A quick rating (one 0-10 score) from a poster tile's ★ picker. */
data class TileRate(
    val userId: User.Id,
    /** Local id (movies.id): the controller saves the title locally first, like the tile's other actions. */
    val movieId: Movie.Id,
    val score: Double?,
)

enum class TileRateOutcome {
    /** No rating before: a new quick rating. */
    RATED,

    /** A quick rating got a new score (its review, if any, is kept). */
    CHANGED,

    /** The rating was deleted. */
    DELETED,
}

data class TileRateResult(
    val outcome: TileRateOutcome,
    val before: SeenState,
    val after: SeenState,
    /** The score now stored; null after a delete. */
    val score: Score?,
) {
    /** +1 / -1 / 0: how "seen" changed, for list pages' "You've seen 9 of 25". */
    val seenDelta: Int get() = (if (after.seen) 1 else 0) - (if (before.seen) 1 else 0)
}

/**
 * Saves a tile's quick rating through upstream's own rating handlers, so validation, the DB rules and the
 * auto-Watched repository decorator (rating a movie marks it Watched) all apply exactly as in the rate modal:
 * - no rating yet: [AddRatingHandler] with only a score;
 * - a quick rating: [UpdateRatingHandler] with the new score and the existing review passed back unchanged;
 * - a detailed rating: refused ([TileRateHandlerError.DetailedRating]); one score would flatten the five categories.
 * Upcoming movies are refused, like the Seen toggle.
 */
@Component
class TileRateHandler(
    private val movieRepository: MovieRepository,
    private val ratingRepository: RatingRepository,
    private val addRatingHandler: AddRatingHandler,
    private val updateRatingHandler: UpdateRatingHandler,
    private val seenStateReader: SeenStateReader,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @Transactional
    fun handle(command: TileRate): Either<TileRateHandlerError, TileRateResult> = either {
        val score = command.score?.takeIf { it in 0.0..10.0 } ?: raise(TileRateHandlerError.InvalidScore)
        val movie = movieRepository.findById(command.movieId) ?: raise(TileRateHandlerError.MovieNotFound)
        if (ReleaseRules.isUpcoming(MediaType.movie, movie.releaseDate, movie.status?.value, LocalDate.now(clock))) {
            raise(TileRateHandlerError.NotReleased)
        }

        val before = seenStateReader.read(command.userId, MediaType.movie, command.movieId.value)
        val existing = ratingRepository.findByMovieIdAndUserId(command.movieId, command.userId)
        val outcome = when {
            existing == null -> {
                add(command, score)
                TileRateOutcome.RATED
            }
            existing.isQuick -> {
                update(command, score, existing.review?.title?.value, existing.review?.text?.value)
                TileRateOutcome.CHANGED
            }
            else -> raise(TileRateHandlerError.DetailedRating(existing.score))
        }
        val after = seenStateReader.read(command.userId, MediaType.movie, command.movieId.value)
        TileRateResult(outcome, before, after, Score(score))
    }

    private fun Raise<TileRateHandlerError>.add(command: TileRate, score: Double) {
        addRatingHandler.handle(
            AddRating(
                movieId = command.movieId,
                userId = command.userId,
                directing = null,
                cinematography = null,
                acting = null,
                soundtrack = null,
                screenplay = null,
                reviewTitle = null,
                reviewText = null,
                score = score,
            ),
        ).mapLeft { error ->
            when (error) {
                AddRatingHandlerError.InvalidRatingValue, AddRatingHandlerError.InvalidRatingCombination -> TileRateHandlerError.InvalidScore
                AddRatingHandlerError.MovieNotFound -> TileRateHandlerError.MovieNotFound
                AddRatingHandlerError.RatingAlreadyExists,
                AddRatingHandlerError.InvalidReviewTitle,
                AddRatingHandlerError.InvalidReviewText -> TileRateHandlerError.SaveFailed
            }
        }.bind()
    }

    private fun Raise<TileRateHandlerError>.update(command: TileRate, score: Double, reviewTitle: String?, reviewText: String?) {
        updateRatingHandler.handle(
            UpdateRating(
                movieId = command.movieId,
                userId = command.userId,
                directing = null,
                cinematography = null,
                acting = null,
                soundtrack = null,
                screenplay = null,
                reviewTitle = reviewTitle,
                reviewText = reviewText,
                score = score,
            ),
        ).mapLeft { error ->
            when (error) {
                UpdateRatingHandlerError.InvalidRatingValue, UpdateRatingHandlerError.InvalidRatingCombination -> TileRateHandlerError.InvalidScore
                UpdateRatingHandlerError.RatingNotFound -> TileRateHandlerError.RatingNotFound
                UpdateRatingHandlerError.InvalidReviewTitle, UpdateRatingHandlerError.InvalidReviewText -> TileRateHandlerError.SaveFailed
            }
        }.bind()
    }
}
