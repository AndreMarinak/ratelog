package org.ratelog.movie.rating.tile

import org.ratelog.Score

sealed interface TileRateHandlerError {
    data object MovieNotFound : TileRateHandlerError

    /** Released after today (or TMDB says it isn't out): tiles offer no ★ there, and the endpoint refuses too. */
    data object NotReleased : TileRateHandlerError

    /** Missing, or outside 0-10. */
    data object InvalidScore : TileRateHandlerError

    /**
     * The rating has all five categories. A tile only edits quick ratings: one score can't replace five without
     * losing them, so the picker links to the title page's rate modal instead. Carries the current score.
     */
    data class DetailedRating(val score: Score?) : TileRateHandlerError

    /**
     * Fork: a show with rated seasons. Its score is their average, so a whole-show score would not be used: the picker
     * shows the average and links to the seasons instead. Carries the current score.
     */
    data class RatedBySeason(val score: Score?) : TileRateHandlerError

    /** Delete: there is no rating. */
    data object RatingNotFound : TileRateHandlerError

    /** An upstream rating handler refused for another reason (e.g. a review stored before a limit changed). */
    data object SaveFailed : TileRateHandlerError
}
