package org.ratelog.movie.rating.add

sealed interface AddRatingHandlerError {
    data object InvalidRatingValue : AddRatingHandlerError
    /** Neither "all five categories" nor "only a score". */
    data object InvalidRatingCombination : AddRatingHandlerError
    data object RatingAlreadyExists : AddRatingHandlerError
    data object MovieNotFound : AddRatingHandlerError
    data object InvalidReviewTitle : AddRatingHandlerError
    data object InvalidReviewText : AddRatingHandlerError
}
