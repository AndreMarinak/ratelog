package org.ratelog.watchlist.autowatched

import org.ratelog.MediaType
import org.ratelog.movie.rating.Rating
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.tvshow.rating.TvRating
import org.ratelog.tvshow.rating.TvRatingRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/*
 * "Rating a title marks it Watched" (a show: Watching, until every aired season is rated) is hooked in at the
 * rating repositories instead of in each upstream
 * rating handler: these decorators run [MarkWatchedOnRatingHandler] right after every successful rating save,
 * inside the same transaction. That covers adding and editing ratings, the Letterboxd import and any future
 * rating path without editing upstream code. Deleting a rating never touches the status.
 */

class AutoWatchedRatingRepository(
    private val delegate: RatingRepository,
    private val markWatched: MarkWatchedOnRatingHandler,
) : RatingRepository by delegate {
    override fun save(rating: Rating) {
        delegate.save(rating)
        markWatched.handle(MarkWatchedOnRating(rating.userId, MediaType.movie, rating.movieId.value))
    }
}

class AutoWatchedTvRatingRepository(
    private val delegate: TvRatingRepository,
    private val markWatched: MarkWatchedOnRatingHandler,
) : TvRatingRepository by delegate {
    override fun save(rating: TvRating) {
        // Deleting a season is also a save (with fewer seasons); only adding or editing one counts as a rating.
        val seasonsBefore = delegate.findByTvShowIdAndUserId(rating.tvShowId, rating.userId)?.seasonRatings?.size ?: 0
        delegate.save(rating)
        if (rating.seasonRatings.isNotEmpty() && rating.seasonRatings.size >= seasonsBefore) {
            val ratedSeasons = rating.seasonRatings.map { it.seasonNumber.value }.toSet()
            markWatched.handle(MarkWatchedOnRating(rating.userId, MediaType.tvshow, rating.tvShowId.value, ratedSeasons))
        }
    }
}

/** Makes every `RatingRepository` / `TvRatingRepository` injection point get the decorated repository. */
@Configuration
class AutoWatchedConfig {
    @Bean
    @Primary
    fun autoWatchedRatingRepository(
        @Qualifier("ratingRepositoryImpl") delegate: RatingRepository,
        markWatched: MarkWatchedOnRatingHandler,
    ): RatingRepository = AutoWatchedRatingRepository(delegate, markWatched)

    @Bean
    @Primary
    fun autoWatchedTvRatingRepository(
        @Qualifier("tvRatingRepositoryImpl") delegate: TvRatingRepository,
        markWatched: MarkWatchedOnRatingHandler,
    ): TvRatingRepository = AutoWatchedTvRatingRepository(delegate, markWatched)
}
