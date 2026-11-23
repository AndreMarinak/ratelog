package org.ratelog.watchlist.getwatchlist

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieDescriptionRepository
import org.ratelog.movie.MovieRepository
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.tvshow.TvDescriptionRepository
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

data class GetWatchlist(
    val userId: User.Id,
    /** null = every status. */
    val status: WatchStatus?,
    /** null = movies and TV. */
    val mediaType: MediaType?,
    val lang: Lang,
)

data class WatchlistItem(
    val mediaType: MediaType,
    val mediaId: Long,
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val status: WatchStatus,
    /** The user's own score, if they rated it. */
    val score: Double?,
    val updatedAtEpochMs: Long,
    /** Release date (a show's first air date), for the Upcoming badge. */
    val releaseDate: LocalDate? = null,
)

data class Watchlist(
    val items: List<WatchlistItem>,
    /** Titles per status for the same media type filter; every status is present. */
    val counts: Map<WatchStatus, Int>,
)

@Service
class GetWatchlistHandler(
    private val mediaStatusRepository: MediaStatusRepository,
    private val movieRepository: MovieRepository,
    private val movieDescriptionRepository: MovieDescriptionRepository,
    private val ratingRepository: RatingRepository,
    private val tvShowRepository: TvShowRepository,
    private val tvDescriptionRepository: TvDescriptionRepository,
    private val tvRatingRepository: TvRatingRepository,
) {
    @Transactional(readOnly = true)
    fun handle(query: GetWatchlist): Watchlist {
        val items = mediaStatusRepository.findByUser(query.userId, query.status, query.mediaType)
            .mapNotNull { toItem(it, query.lang) }
        val counts = mediaStatusRepository.countByStatus(query.userId, query.mediaType)

        return Watchlist(
            items = items,
            counts = WatchStatus.entries.associateWith { counts[it] ?: 0 },
        )
    }

    private fun toItem(status: MediaStatus, lang: Lang): WatchlistItem? = when (status.mediaType) {
        MediaType.movie -> movieRepository.findById(Movie.Id(status.mediaId))?.let { movie ->
            val description = movieDescriptionRepository.findByTmdbIdAndLang(movie.tmdbId, lang)
                ?: movieDescriptionRepository.findByTmdbIdAndLang(movie.tmdbId, Lang.en)
            WatchlistItem(
                mediaType = MediaType.movie,
                mediaId = status.mediaId,
                tmdbId = movie.tmdbId.value,
                title = description?.title?.value ?: movie.originalTitle.value,
                year = movie.releaseYear,
                posterPath = movie.posterPath?.value,
                status = status.status,
                score = ratingRepository.findByMovieIdAndUserId(movie.id!!, status.userId)?.score?.value,
                updatedAtEpochMs = status.updatedAtEpochMs,
                releaseDate = movie.releaseDate,
            )
        }

        MediaType.tvshow -> tvShowRepository.findById(TvShow.Id(status.mediaId))?.let { show ->
            val description = tvDescriptionRepository.findByTmdbIdAndLang(show.tmdbId, lang)
                ?: tvDescriptionRepository.findByTmdbIdAndLang(show.tmdbId, Lang.en)
            WatchlistItem(
                mediaType = MediaType.tvshow,
                mediaId = status.mediaId,
                tmdbId = show.tmdbId.value,
                title = description?.name?.value ?: show.originalName.value,
                year = show.firstAirYear,
                posterPath = show.posterPath?.value,
                status = status.status,
                score = tvRatingRepository.findByTvShowIdAndUserId(show.id!!, status.userId)?.score?.value,
                updatedAtEpochMs = status.updatedAtEpochMs,
                releaseDate = show.firstAirDate,
            )
        }
    }
}
