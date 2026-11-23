package org.ratelog.tvshow.rating.addseason

sealed interface AddSeasonRatingHandlerError {
    data object InvalidRatingValue : AddSeasonRatingHandlerError
    /** Neither "all five categories" nor "only a score". */
    data object InvalidRatingCombination : AddSeasonRatingHandlerError
    data object RatingAlreadyExists : AddSeasonRatingHandlerError
    data object TvShowNotFound : AddSeasonRatingHandlerError
    data object InvalidReviewTitle : AddSeasonRatingHandlerError
    data object InvalidReviewText : AddSeasonRatingHandlerError
}
