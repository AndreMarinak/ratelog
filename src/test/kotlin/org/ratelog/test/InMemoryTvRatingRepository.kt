package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.Rank
import org.ratelog.tag.Tag
import org.ratelog.RatingCategory
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.TvRating
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.user.User
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Pass [tags] to make the ranking honor the tag filter. */
class InMemoryTvRatingRepository(private val tags: InMemoryTagRepository? = null) : TvRatingRepository {
    private val store = ConcurrentHashMap<TvRating.Id, TvRating>()
    private val idGenerator = AtomicLong(1)

    override fun findByTvShowIdAndUserId(tvShowId: TvShow.Id, userId: User.Id): TvRating? =
        store.values.find { it.tvShowId == tvShowId && it.userId == userId }

    override fun findRankedByUserIdWithFilters(
        userId: User.Id,
        genreId: String?,
        limit: Int,
        name: String?,
        ratingCategory: String?,
        tagId: Tag.Id?,
        offset: Int,
    ): List<Pair<Rank, TvRating>> {
        // Mirrors the SQL: a category ranking skips quick ratings, nulls sort last; ranks are overall
        // ranks and the tag filter only narrows the list afterwards.
        val category = RatingCategory.parse(ratingCategory)
        // Ties by rating id, as the SQL's ROW_NUMBER tiebreaker; then a page of `limit` from `offset`.
        return store.values
            .filter { it.userId == userId }
            .filter { category == null || it.rankingScore(category) != null }
            .sortedBy { it.id?.value }
            .sortedByDescending { it.rankingScore(category)?.value ?: Double.NEGATIVE_INFINITY }
            .mapIndexed { index, rating -> Pair(Rank(index + 1), rating) }
            .filter { (_, rating) -> tagId == null || tags?.isTaggedForUser(tagId, userId, MediaType.tvshow, rating.tvShowId.value) == true }
            .drop(offset.coerceAtLeast(0))
            .take(limit)
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
