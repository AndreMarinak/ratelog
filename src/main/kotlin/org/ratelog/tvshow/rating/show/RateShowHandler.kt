package org.ratelog.tvshow.rating.show

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import org.ratelog.Score
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.tvshow.rating.TvRating
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.user.User
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** One score (0-10, like a quick rating) for the whole show, without rating seasons. */
data class RateShow(
    val tvShowId: TvShow.Id,
    val userId: User.Id,
    val score: Double?,
)

enum class RateShowOutcome {
    /** No whole-show score before. */
    RATED,

    /** The whole-show score was changed. */
    CHANGED,
}

data class RateShowResult(
    val outcome: RateShowOutcome,
    /** The rating after the save: its `score` is the season average when seasons are rated, else this score. */
    val rating: TvRating,
)

sealed interface RateShowHandlerError {
    data object TvShowNotFound : RateShowHandlerError

    /** Missing, or outside 0-10. */
    data object InvalidScore : RateShowHandlerError
}

/**
 * Sets the show's whole-show score (fork, 2026-11-23: "I should be able to rate show as a whole and then if u rate any
 * season at all then the overall rating becomes the average"). It is the show's score while no season is rated; once a
 * season is rated their average wins and this score is kept, unused, so deleting every season rating brings it back.
 * Saved through [TvRatingRepository], so the auto-Watched decorator applies (a whole-show rating marks the show Watched,
 * like a movie).
 */
@Component
class RateShowHandler(
    private val tvShowRepository: TvShowRepository,
    private val tvRatingRepository: TvRatingRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @Transactional
    fun handle(command: RateShow): Either<RateShowHandlerError, RateShowResult> = either {
        val score = command.score?.takeIf { it in 0.0..10.0 }?.let(::Score) ?: raise(RateShowHandlerError.InvalidScore)
        val show = tvShowRepository.findById(command.tvShowId)
        ensureNotNull(show?.id) { RateShowHandlerError.TvShowNotFound }

        val now = clock.instant()
        val existing = tvRatingRepository.findByTvShowIdAndUserId(command.tvShowId, command.userId)
        val outcome = if (existing?.showScore == null) RateShowOutcome.RATED else RateShowOutcome.CHANGED
        val updated = (existing ?: TvRating.create(command.tvShowId, command.userId, now)).withShowScore(score, now)
        tvRatingRepository.save(updated)
        RateShowResult(outcome, updated)
    }
}
