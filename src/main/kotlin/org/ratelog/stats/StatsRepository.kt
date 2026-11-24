package org.ratelog.stats

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.toLocalDate
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Repository
import java.time.LocalDate

/** A movie you rated, or marked Watched without rating it (score null). */
data class StatsMovie(
    val tmdbId: Int,
    val score: Double?,
    val rated: Boolean,
    /** The rating's time, or the Watched status's time when unrated. */
    val atMs: Long,
    val genreIds: List<Int>,
    val releaseYear: Int?,
    val runtimeMinutes: Int?,
    /** The runtime isn't cached yet and a TMDB lookup is due (never tried, or a failed try may be repeated). */
    val runtimePending: Boolean,
)

/** A show you rated (by season or as a whole) or marked Watched. */
data class StatsShow(
    val tmdbId: Int,
    val genreIds: List<Int>,
    val releaseYear: Int?,
    /** The whole-show score (V1140); only used while no season is rated. */
    val showScore: Double?,
    val showRatedAtMs: Long?,
    /** When it was marked Watched (null unless the status is Watched). */
    val watchedAtMs: Long?,
)

data class StatsSeason(val tmdbId: Int, val seasonNumber: Int, val score: Double, val atMs: Long)

/** Everything one user has rated or marked seen. */
data class StatsSource(
    val movies: List<StatsMovie>,
    val shows: List<StatsShow>,
    val seasons: List<StatsSeason>,
)

/** What a "Highest rated" tile needs besides the score. */
data class StatsTitle(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val year: Int?,
    val releaseDate: LocalDate?,
    val posterPath: String?,
    val status: WatchStatus?,
    /** Fork (2026-11-24): on the watchlist (independent of [status]). */
    val onWatchlist: Boolean = false,
)

interface StatsRepository {
    /** [nowMs] decides whether a failed runtime lookup is due again. */
    fun load(userId: User.Id, nowMs: Long): StatsSource

    fun titles(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>, lang: Lang): List<StatsTitle>
}

@Repository
class StatsRepositoryImpl(
    private val dao: StatsDAO,
) : StatsRepository {

    override fun load(userId: User.Id, nowMs: Long): StatsSource = StatsSource(
        movies = dao.findMovies(userId.value).map {
            StatsMovie(
                tmdbId = it.tmdbId,
                score = it.score,
                rated = it.rated,
                atMs = it.atMs,
                genreIds = parseGenres(it.genres),
                releaseYear = it.releaseYear,
                runtimeMinutes = it.runtimeMinutes?.takeIf { minutes -> minutes > 0 },
                runtimePending = !it.extrasFetched && (it.extrasRetryAtMs == null || it.extrasRetryAtMs <= nowMs),
            )
        },
        shows = dao.findShows(userId.value).distinctBy { it.tmdbId }.map {
            StatsShow(it.tmdbId, parseGenres(it.genres), it.releaseYear, it.showScore, it.showRatedAtMs, it.watchedAtMs)
        },
        seasons = dao.findSeasons(userId.value).map { StatsSeason(it.tmdbId, it.seasonNumber, it.score, it.atMs) },
    )

    override fun titles(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>, lang: Lang): List<StatsTitle> {
        if (tmdbIds.isEmpty()) return emptyList()
        val rows = when (type) {
            MediaType.movie -> dao.findMovieTitles(userId.value, tmdbIds, lang.name)
            MediaType.tvshow -> dao.findShowTitles(userId.value, tmdbIds, lang.name)
        }
        return rows.map {
            StatsTitle(
                tmdbId = it.tmdbId,
                type = type,
                title = it.title,
                year = it.releaseYear,
                releaseDate = it.releaseDate?.take(10)?.let { date -> runCatching { date.toLocalDate() }.getOrNull() },
                posterPath = it.posterPath,
                status = it.status?.let { status -> WatchStatus.entries.find { s -> s.name == status } },
                onWatchlist = it.onWatchlist,
            )
        }
    }

    companion object {
        /** `movies.genres` / `tv.genres` hold TMDB genre ids, comma separated (V16); anything else is skipped. */
        fun parseGenres(value: String?): List<Int> =
            value.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.distinct()
    }
}
