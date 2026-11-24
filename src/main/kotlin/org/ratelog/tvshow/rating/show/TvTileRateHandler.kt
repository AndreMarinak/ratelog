package org.ratelog.tvshow.rating.show

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.MediaType
import org.ratelog.Score
import org.ratelog.movie.rating.tile.TileRateHandlerError
import org.ratelog.movie.rating.tile.TileRateOutcome
import org.ratelog.movie.rating.tile.TileRateResult
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.user.User
import org.ratelog.watchlist.seen.ReleaseRules
import org.ratelog.watchlist.seen.SeenStateReader
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * The poster ★ picker on a show (fork, 2026-11-23): one tap sets the whole-show score, like a movie's quick rating,
 * through [RateShowHandler] / [DeleteShowRatingHandler]. A show with rated seasons is refused
 * ([TileRateHandlerError.RatedBySeason]): its score is their average and a whole-show score would not be used, so the
 * picker offers "Rate seasons" instead. Shows that haven't aired yet are refused, like the Seen toggle.
 */
@Component
class TvTileRateHandler(
    private val tvShowRepository: TvShowRepository,
    private val tvRatingRepository: TvRatingRepository,
    private val rateShowHandler: RateShowHandler,
    private val deleteShowRatingHandler: DeleteShowRatingHandler,
    private val seenStateReader: SeenStateReader,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @Transactional
    fun rate(userId: User.Id, tvShowId: TvShow.Id, score: Double?): Either<TileRateHandlerError, TileRateResult> = either {
        val value = score?.takeIf { it in 0.0..10.0 } ?: raise(TileRateHandlerError.InvalidScore)
        val show = tvShowRepository.findById(tvShowId) ?: raise(TileRateHandlerError.MovieNotFound)
        if (ReleaseRules.isUpcoming(MediaType.tvshow, show.firstAirDate, show.status?.value, LocalDate.now(clock))) {
            raise(TileRateHandlerError.NotReleased)
        }
        val existing = tvRatingRepository.findByTvShowIdAndUserId(tvShowId, userId)
        if (existing != null && existing.ratedBySeason) raise(TileRateHandlerError.RatedBySeason(existing.score))

        val before = seenStateReader.read(userId, MediaType.tvshow, tvShowId.value)
        val result = rateShowHandler.handle(RateShow(tvShowId, userId, value)).mapLeft { error ->
            when (error) {
                RateShowHandlerError.InvalidScore -> TileRateHandlerError.InvalidScore
                RateShowHandlerError.TvShowNotFound -> TileRateHandlerError.MovieNotFound
            }
        }.bind()
        val after = seenStateReader.read(userId, MediaType.tvshow, tvShowId.value)
        val outcome = if (result.outcome == RateShowOutcome.RATED) TileRateOutcome.RATED else TileRateOutcome.CHANGED
        TileRateResult(outcome, before, after, Score(value))
    }

    /** Deletes the whole-show score (a show rated by season is deleted season by season, on its page). */
    @Transactional
    fun delete(userId: User.Id, tvShowId: TvShow.Id): Either<TileRateHandlerError, TileRateResult> = either {
        val existing = tvRatingRepository.findByTvShowIdAndUserId(tvShowId, userId)
        if (existing != null && existing.ratedBySeason) raise(TileRateHandlerError.RatedBySeason(existing.score))
        if (existing?.showScore == null) raise(TileRateHandlerError.RatingNotFound)

        val before = seenStateReader.read(userId, MediaType.tvshow, tvShowId.value)
        deleteShowRatingHandler.handle(DeleteShowRating(tvShowId, userId))
            .mapLeft { TileRateHandlerError.RatingNotFound }
            .bind()
        val after = seenStateReader.read(userId, MediaType.tvshow, tvShowId.value)
        TileRateResult(TileRateOutcome.DELETED, before, after, score = null)
    }
}
