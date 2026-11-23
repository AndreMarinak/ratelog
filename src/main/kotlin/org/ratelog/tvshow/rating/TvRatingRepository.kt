package org.ratelog.tvshow.rating

import org.ratelog.CategoryScores
import org.ratelog.Review
import org.ratelog.Rank
import org.ratelog.RatingCategory
import org.ratelog.RatingInput
import org.ratelog.Score
import org.ratelog.SeasonNumber
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User
import org.springframework.stereotype.Repository
import java.time.Instant

data class TvRating(
    val id: Id?,
    val tvShowId: TvShow.Id,
    val seasonRatings: List<SeasonRating>,
    val userId: User.Id,
    val createdAt: Instant,
    val score: Score? = null,
) {
    data class Id(val value: Long)

    fun deleteSeasonRating(seasonNumber: SeasonNumber) =
        copy(seasonRatings = seasonRatings - (seasonRatings.filter { it.seasonNumber == seasonNumber }).toSet())
            .updateScore()

    fun addSeasonRating(
        seasonNumber: SeasonNumber,
        input: RatingInput,
        createdAt: Instant,
        review: Review?,
    ) =
        copy(seasonRatings = seasonRatings + SeasonRating(
            id = null,
            tvShowId = tvShowId,
            seasonNumber = seasonNumber,
            userId = userId,
            directing = input.categories?.directing,
            cinematography = input.categories?.cinematography,
            acting = input.categories?.acting,
            soundtrack = input.categories?.soundtrack,
            screenplay = input.categories?.screenplay,
            score = input.score,
            createdAt = createdAt,
            review = review,
        )).updateScore()

    /** Replaces a season's scores (switching between quick and detailed if needed) and review. */
    fun updateSeasonRating(
        seasonNumber: SeasonNumber,
        input: RatingInput,
        review: Review?,
    ) =
        copy(seasonRatings = seasonRatings.map { seasonRating ->
            if (seasonRating.seasonNumber == seasonNumber) seasonRating.withScores(input).copy(review = review)
            else seasonRating
        }).updateScore()

    /** One category averaged over the seasons rated in detail; null when every season is a quick rating. */
    fun categoryAverage(category: RatingCategory): Score? =
        seasonRatings.mapNotNull { it.categories?.get(category)?.value }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.let(::Score)

    /** What the top list ranks by: the overall score, or one category averaged over the detailed seasons. */
    fun rankingScore(category: RatingCategory?): Score? = if (category == null) score else categoryAverage(category)

    private fun updateScore() = if (seasonRatings.isEmpty()) copy(score = null)
        else copy(score = seasonRatings.map { it.score.value }.average().let(::Score))

    companion object {
        fun create(tvShowId: TvShow.Id, userId: User.Id, now: Instant) = TvRating(
            id = null,
            tvShowId = tvShowId,
            seasonRatings = emptyList(),
            userId = userId,
            createdAt = now,
        )
    }
}

/**
 * A detailed season rating has all five category scores and its score is their average.
 * A quick one has no categories, only the score.
 */
data class SeasonRating(
    val id: Id?,
    val tvShowId: TvShow.Id,
    val seasonNumber: SeasonNumber,
    val userId: User.Id,
    val directing: Score?,
    val cinematography: Score?,
    val acting: Score?,
    val soundtrack: Score?,
    val screenplay: Score?,
    val score: Score,
    val createdAt: Instant,
    val review: Review? = null,
) {
    data class Id(val value: Long)

    init {
        val present = listOfNotNull(directing, cinematography, acting, soundtrack, screenplay).size
        require(present == 0 || present == 5) { "A season rating has all five category scores or none" }
    }

    /** The five category scores, or null for a quick rating. */
    val categories: CategoryScores? get() = CategoryScores.of(directing, cinematography, acting, soundtrack, screenplay)

    val isQuick: Boolean get() = categories == null

    fun withScores(input: RatingInput) = copy(
        directing = input.categories?.directing,
        cinematography = input.categories?.cinematography,
        acting = input.categories?.acting,
        soundtrack = input.categories?.soundtrack,
        screenplay = input.categories?.screenplay,
        score = input.score,
    )
}

@Repository
interface TvRatingRepository {
    fun findByTvShowIdAndUserId(tvShowId: TvShow.Id, userId: User.Id): TvRating?
    fun findRankedByUserIdWithFilters(userId: User.Id, genreId: String?, limit: Int, name: String?, ratingCategory: String?): List<Pair<Rank, TvRating>>

    fun save(rating: TvRating)
    fun deleteById(tvRatingId: TvRating.Id)
}
