package org.ratelog.movie.rating.update

sealed interface UpdateRatingHandlerError {
    data object InvalidRatingValue : UpdateRatingHandlerError
    /** Neither "all five categories" nor "only a score". */
    data object InvalidRatingCombination : UpdateRatingHandlerError
    data object RatingNotFound : UpdateRatingHandlerError
    data object InvalidReviewTitle : UpdateRatingHandlerError
    data object InvalidReviewText : UpdateRatingHandlerError
}
