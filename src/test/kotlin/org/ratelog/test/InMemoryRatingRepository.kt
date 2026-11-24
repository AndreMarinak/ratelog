package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.Rank
import org.ratelog.RatingCategory
import org.ratelog.movie.Movie
import org.ratelog.movie.rating.Rating
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.tag.Tag
import org.ratelog.user.User
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Pass [tags] to make the ranking honor the tag filter. */
class InMemoryRatingRepository(private val tags: InMemoryTagRepository? = null) : RatingRepository {
    private val store = ConcurrentHashMap<Rating.Id, Rating>()
    private val idGenerator = AtomicLong(1)

    override fun findByMovieIdAndUserId(movieId: Movie.Id, userId: User.Id): Rating? =
        store.values.find { it.movieId == movieId && it.userId == userId }

    override fun findRankedByUserIdWithFilters(
        userId: User.Id,
        genreId: String?,
        limit: Int,
        name: String?,
        ratingCategory: String?,
        tagId: Tag.Id?,
        offset: Int,
    ): List<Pair<Rank, Rating>> {
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
            .filter { (_, rating) -> tagId == null || tags?.isTaggedForUser(tagId, userId, MediaType.movie, rating.movieId.value) == true }
            .drop(offset.coerceAtLeast(0))
            .take(limit)
    }

    override fun save(rating: Rating) {
        val ratingToSave = if (rating.id == null) {
            rating.copy(id = Rating.Id(idGenerator.getAndIncrement()))
        } else {
            rating
        }
        store[ratingToSave.id!!] = ratingToSave
    }

    override fun deleteById(ratingId: Rating.Id) {
        store.remove(ratingId)
    }
}
