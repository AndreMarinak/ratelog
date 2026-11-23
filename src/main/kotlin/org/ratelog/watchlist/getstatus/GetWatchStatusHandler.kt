package org.ratelog.watchlist.getstatus

import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.movie.MovieRepository
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Component

data class GetWatchStatus(
    val userId: User.Id,
    val mediaType: MediaType,
    val tmdbId: TmdbId,
)

/** The user's status on a title, by TMDB id. Null when there is none (or the title isn't saved locally yet). */
@Component
class GetWatchStatusHandler(
    private val mediaStatusRepository: MediaStatusRepository,
    private val movieRepository: MovieRepository,
    private val tvShowRepository: TvShowRepository,
) {
    fun handle(query: GetWatchStatus): WatchStatus? {
        val mediaId = when (query.mediaType) {
            MediaType.movie -> movieRepository.findByTmdbId(query.tmdbId)?.id?.value
            MediaType.tvshow -> tvShowRepository.findByTmdbId(query.tmdbId)?.id?.value
        } ?: return null

        return mediaStatusRepository.find(query.userId, query.mediaType, mediaId)?.status
    }
}
