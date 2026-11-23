package org.ratelog.test

import org.ratelog.Rank
import org.ratelog.RatingCategory
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.TvRating
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.user.User
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class InMemoryTvRatingRepository: TvRatingRepository {
    private val store = ConcurrentHashMap<TvRating.Id, TvRating>()
    private val idGenerator = AtomicLong(1)

    override fun findByTvShowIdAndUserId(tvShowId: TvShow.Id, userId: User.Id): TvRating? =
        store.values.find { it.tvShowId == tvShowId && it.userId == userId }

    override fun findRankedByUserIdWithFilters(
        userId: User.Id,
        genreId: String?,
        limit: Int,
        name: String?,
        ratingCategory: String?
    ): List<Pair<Rank, TvRating>> {
        // Mirrors TvRatingDAO.findRankedRows: a category is averaged over detailed seasons,
        // shows with only quick seasons are skipped for a category ranking, nulls sort last.
        val category = RatingCategory.parse(ratingCategory)
        return store.values
            .filter { it.userId == userId }
            .filter { category == null || it.rankingScore(category) != null }
            .sortedByDescending { it.rankingScore(category)?.value ?: Double.NEGATIVE_INFINITY }
            .take(limit)
            .mapIndexed { index, rating -> Pair(Rank(index + 1), rating) }
    }

    override fun save(rating: TvRating) {
        val ratingToSave = if (rating.id == null) {
            rating.copy(id = TvRating.Id(idGenerator.getAndIncrement()))
        } else {
            rating
        }
        store[ratingToSave.id!!] = ratingToSave
    }

    override fun deleteById(tvRatingId: TvRating.Id) {
        store.remove(tvRatingId)
    }
}
