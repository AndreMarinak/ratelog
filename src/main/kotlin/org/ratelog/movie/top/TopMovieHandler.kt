package org.ratelog.movie.top

import org.ratelog.Lang
import org.ratelog.Rank
import org.ratelog.RatingCategory
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieDescriptionRepository
import org.ratelog.movie.MovieRepository
import org.ratelog.movie.rating.Rating
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.tag.Tag
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class TopMovie(
    val userId: User.Id,
    val genreId: String?,
    val ratingCategory: String?,
    val limit: Int = 10,
    val name: String?,
    val lang: Lang,
    val tagId: Tag.Id? = null,
)

data class TopMovieItem(
    val rank: Rank,
    val rating: Rating,
    val movie: Movie,
    val title: String,
)

@Service
class TopMovieHandler(
    private val ratingRepository: RatingRepository,
    private val movieRepository: MovieRepository,
    private val movieDescriptionRepository: MovieDescriptionRepository,
) {
    /**
     * Ranks by overall score (quick and detailed ratings alike), or by one category, which leaves
     * quick ratings out since they have no categories. Unknown categories fall back to the overall score.
     */
    @Transactional
    fun handle(query: TopMovie): List<TopMovieItem> =
        ratingRepository.findRankedByUserIdWithFilters(query.userId, query.genreId, query.limit, query.name, RatingCategory.parse(query.ratingCategory)?.name, query.tagId)
            .mapNotNull { toItem(it, query.lang) }

    private fun toItem(item: Pair<Rank, Rating>, lang: Lang) =
        movieRepository
            .findById(item.second.movieId)
            ?.let { movie ->
                val description = movieDescriptionRepository.findByTmdbIdAndLang(movie.tmdbId, lang)
                val title = description?.title?.value ?: movie.originalTitle.value
                TopMovieItem(rank = item.first, rating = item.second, movie = movie, title = title)
            }
}
