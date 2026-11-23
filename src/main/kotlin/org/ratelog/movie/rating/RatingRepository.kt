package org.ratelog.movie.rating

import org.ratelog.CategoryScores
import org.ratelog.Rank
import org.ratelog.RatingCategory
import org.ratelog.RatingInput
import org.ratelog.Review
import org.ratelog.Score
import org.ratelog.movie.Movie
import org.ratelog.tag.Tag
import org.ratelog.user.User
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * A detailed rating has all five category scores and its score is their average.
 * A quick rating has no categories, only the score.
 */
data class Rating(
    val id: Id?,
    val movieId: Movie.Id,
    val userId: User.Id,
    val directing: Score?,
    val cinematography: Score?,
    val acting: Score?,
    val soundtrack: Score?,
    val screenplay: Score?,
    val createdAt: Instant,
    val score: Score? = null,
    val review: Review?,
) {
    data class Id(val value: Long)

    init {
        val present = listOfNotNull(directing, cinematography, acting, soundtrack, screenplay).size
        require(present == 0 || present == 5) { "A rating has all five category scores or none" }
        require(present == 5 || score != null) { "A quick rating needs a score" }
    }

    /** The five category scores, or null for a quick rating. */
    val categories: CategoryScores? get() = CategoryScores.of(directing, cinematography, acting, soundtrack, screenplay)

    val isQuick: Boolean get() = categories == null

    /** Detailed ratings recompute the score from their categories; a quick rating keeps its score. */
    fun updateScore() = categories?.let { copy(score = it.average) } ?: this

    /** Replaces the scores, switching between quick and detailed if needed. */
    fun withScores(input: RatingInput) = copy(
        directing = input.categories?.directing,
        cinematography = input.categories?.cinematography,
        acting = input.categories?.acting,
        soundtrack = input.categories?.soundtrack,
        screenplay = input.categories?.screenplay,
        score = input.score,
    )

    /** What the top list ranks by: the overall score, or one category (null for a quick rating). */
    fun rankingScore(category: RatingCategory?): Score? = if (category == null) score else categories?.get(category)

    companion object {
        fun create(movieId: Movie.Id, userId: User.Id, input: RatingInput, review: Review?, now: Instant) = Rating(
            id = null,
            movieId = movieId,
            userId = userId,
            directing = input.categories?.directing,
            cinematography = input.categories?.cinematography,
            acting = input.categories?.acting,
            soundtrack = input.categories?.soundtrack,
            screenplay = input.categories?.screenplay,
            createdAt = now,
            score = input.score,
            review = review,
        )
    }
}

@Repository
interface RatingRepository {
    fun findByMovieIdAndUserId(movieId: Movie.Id, userId: User.Id): Rating?
    fun findRankedByUserIdWithFilters(userId: User.Id, genreId: String?, limit: Int, name: String?, ratingCategory: String?, tagId: Tag.Id? = null): List<Pair<Rank, Rating>>

    fun save(rating: Rating)
    fun deleteById(ratingId: Rating.Id)
}
