package org.ratelog.test

import org.ratelog.Rank
import org.ratelog.RatingCategory
import org.ratelog.movie.Movie
import org.ratelog.movie.rating.Rating
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.user.User
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class InMemoryRatingRepository : RatingRepository {
    private val store = ConcurrentHashMap<Rating.Id, Rating>()
    private val idGenerator = AtomicLong(1)

    override fun findByMovieIdAndUserId(movieId: Movie.Id, userId: User.Id): Rating? =
        store.values.find { it.movieId == movieId && it.userId == userId }

    override fun findRankedByUserIdWithFilters(
        userId: User.Id,
        genreId: String?,
        limit: Int,
        name: String?,
        ratingCategory: String?
    ): List<Pair<Rank, Rating>> {
        // Mirrors RatingDAO.findRankedRows: a category ranking skips quick ratings, nulls sort last.
        val category = RatingCategory.parse(ratingCategory)
        return store.values
            .filter { it.userId == userId }
            .filter { category == null || it.rankingScore(category) != null }
            .sortedByDescending { it.rankingScore(category)?.value ?: Double.NEGATIVE_INFINITY }
            .take(limit)
            .mapIndexed { index, rating -> Pair(Rank(index + 1), rating) }
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
