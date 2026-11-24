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
import org.ratelog.watchlist.seen.SeenState
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
    /** The effective status ([WatchStatus.effective]): the progress status, or Want to watch when only on the watchlist. */
    val status: WatchStatus,
    /** The user's own score, if they rated it. */
    val score: Double?,
    /** When the status changed; for a title only on the watchlist (and on the To watch tab), when it was added. */
    val updatedAtEpochMs: Long,
    /** Release date (a show's first air date), for the Upcoming badge. */
    val releaseDate: LocalDate? = null,
    /** Fork (2026-11-24): on the watchlist (independent of [status]). */
    val onWatchlist: Boolean = status == WatchStatus.WANT_TO_WATCH,
    /** Seen (SeenState's rule: Watched, or rated). On the To watch tab that makes it a rewatch. */
    val seen: Boolean = false,
    /** Rated (a show: a rated season or a whole-show score). */
    val rated: Boolean = score != null,
) {
    /** The progress status alone (Watching / Watched / Dropped), null when it only is on the watchlist. */
    val progress: WatchStatus? get() = status.takeIf { it != WatchStatus.WANT_TO_WATCH }

    /** On the watchlist and already seen. */
    val rewatch: Boolean get() = onWatchlist && seen
}

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
    /**
     * Fork (2026-11-24): Want to watch (the To watch tab) is every title on the watchlist, seen ones (rewatches) included,
     * newest first. The other statuses come from the status table and say whether each title is on the watchlist too.
     * Without a status filter (the API) every title is listed once, under its effective status.
     */
    @Transactional(readOnly = true)
    fun handle(query: GetWatchlist): Watchlist {
        val entries = mediaStatusRepository.findWatchlist(query.userId, query.mediaType)
        val listed = entries.map { it.mediaType to it.mediaId }.toSet()
        val items = when (query.status) {
            WatchStatus.WANT_TO_WATCH -> entries.mapNotNull { entry ->
                val progress = mediaStatusRepository.find(query.userId, entry.mediaType, entry.mediaId)
                toItem(
                    MediaStatus(entry.userId, entry.mediaType, entry.mediaId, progress?.status ?: WatchStatus.WANT_TO_WATCH, entry.addedAtEpochMs),
                    query.lang,
                    onWatchlist = true,
                )
            }

            null -> {
                val withStatus = mediaStatusRepository.findByUser(query.userId, null, query.mediaType)
                val statused = withStatus.map { it.mediaType to it.mediaId }.toSet()
                val onlyListed = entries.filter { (it.mediaType to it.mediaId) !in statused }
                    .map { MediaStatus(it.userId, it.mediaType, it.mediaId, WatchStatus.WANT_TO_WATCH, it.addedAtEpochMs) }
                (withStatus + onlyListed)
                    .sortedByDescending { it.updatedAtEpochMs }
                    .mapNotNull { toItem(it, query.lang, onWatchlist = (it.mediaType to it.mediaId) in listed) }
            }

            else -> mediaStatusRepository.findByUser(query.userId, query.status, query.mediaType)
                .mapNotNull { toItem(it, query.lang, onWatchlist = (it.mediaType to it.mediaId) in listed) }
        }
        val counts = mediaStatusRepository.countByStatus(query.userId, query.mediaType) +
            (WatchStatus.WANT_TO_WATCH to entries.size)

        return Watchlist(
            items = items,
            counts = WatchStatus.entries.associateWith { counts[it] ?: 0 },
        )
    }

    private fun toItem(status: MediaStatus, lang: Lang, onWatchlist: Boolean): WatchlistItem? = when (status.mediaType) {
        MediaType.movie -> movieRepository.findById(Movie.Id(status.mediaId))?.let { movie ->
            val rating = ratingRepository.findByMovieIdAndUserId(movie.id!!, status.userId)
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
                score = rating?.score?.value,
                updatedAtEpochMs = status.updatedAtEpochMs,
                releaseDate = movie.releaseDate,
                onWatchlist = onWatchlist,
                seen = SeenState(status.status.takeIf { it != WatchStatus.WANT_TO_WATCH }, rating != null).seen,
                rated = rating != null,
            )
        }

        MediaType.tvshow -> tvShowRepository.findById(TvShow.Id(status.mediaId))?.let { show ->
            val rating = tvRatingRepository.findByTvShowIdAndUserId(show.id!!, status.userId)
            val rated = rating?.isRated == true
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
                score = rating?.score?.value,
                updatedAtEpochMs = status.updatedAtEpochMs,
                releaseDate = show.firstAirDate,
                onWatchlist = onWatchlist,
                seen = SeenState(status.status.takeIf { it != WatchStatus.WANT_TO_WATCH }, rated, isTv = true).seen,
                rated = rated,
            )
        }
    }
}
