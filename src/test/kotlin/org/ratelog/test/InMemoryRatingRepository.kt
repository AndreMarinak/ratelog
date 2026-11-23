package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.Rank
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
    ): List<Pair<Rank, Rating>> =
        store.values
            .filter { it.userId == userId }
            .sortedByDescending {
                when (ratingCategory) {
                    "directing" -> it.directing.value
                    "cinematography" -> it.cinematography.value
                    "acting" -> it.acting.value
                    "soundtrack" -> it.soundtrack.value
                    "screenplay" -> it.screenplay.value
                    else -> it.score?.value ?: 0.0
                }
            }
            .mapIndexed { index, rating -> Pair(Rank(index + 1), rating) }
            // Like the SQL: ranks stay overall ranks, the tag filter only narrows the list.
            .filter { (_, rating) -> tagId == null || tags?.isTaggedForUser(tagId, userId, MediaType.movie, rating.movieId.value) == true }
            .take(limit)

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
