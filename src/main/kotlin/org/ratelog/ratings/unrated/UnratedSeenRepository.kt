package org.ratelog.ratings.unrated

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.toLocalDate
import org.ratelog.user.User
import org.springframework.stereotype.Repository
import java.time.LocalDate

/** A title the user marked seen (status Watched) and never rated (a show: no rated season). */
data class UnratedSeenTitle(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val releaseDate: LocalDate?,
    /** When it was marked seen (the status's update time), for "newest first". */
    val seenAtEpochMs: Long,
    /** Fork (2026-11-24): on the watchlist too (a rewatch). */
    val onWatchlist: Boolean = false,
)

interface UnratedSeenRepository {
    /** Newest Seen first, at most [limit]. */
    fun find(userId: User.Id, type: MediaType, lang: Lang, limit: Int): List<UnratedSeenTitle>

    fun count(userId: User.Id): UnratedSeenCount
}

data class UnratedSeenCount(val movies: Int, val shows: Int) {
    val total: Int get() = movies + shows
}

@Repository
class UnratedSeenRepositoryImpl(
    private val dao: UnratedSeenDAO,
) : UnratedSeenRepository {
    override fun find(userId: User.Id, type: MediaType, lang: Lang, limit: Int): List<UnratedSeenTitle> {
        val rows = when (type) {
            MediaType.movie -> dao.findMovies(userId.value, lang.name, limit)
            MediaType.tvshow -> dao.findShows(userId.value, lang.name, limit)
        }
        return rows.map {
            UnratedSeenTitle(
                tmdbId = it.tmdbId,
                type = type,
                title = it.title,
                year = it.releaseYear,
                posterPath = it.posterPath,
                releaseDate = it.releaseDate?.take(10)?.let { date -> runCatching { date.toLocalDate() }.getOrNull() },
                seenAtEpochMs = it.sortKey,
                onWatchlist = it.onWatchlist,
            )
        }
    }

    override fun count(userId: User.Id): UnratedSeenCount {
        val movies = dao.countMovies(userId.value).toInt()
        return UnratedSeenCount(movies = movies, shows = dao.count(userId.value).toInt() - movies)
    }
}
