package org.ratelog.stats

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

/** A movie you rated (`rated`) or marked Watched without rating, with what the stats need about it. */
data class StatsMovieDbRow(
    @Column("tmdb_id") val tmdbId: Int,
    val rated: Boolean,
    val score: Double?,
    /** The rating's time, or the Watched status's time for an unrated movie. */
    @Column("at_ms") val atMs: Long,
    val genres: String?,
    @Column("release_year") val releaseYear: Int?,
    @Column("runtime_minutes") val runtimeMinutes: Int?,
    /** TMDB extras were fetched at least once (`title_extras.tmdb_fetched_at_epoch_ms`). */
    @Column("extras_fetched") val extrasFetched: Boolean,
    /** When a failed lookup may be tried again (`title_extras.tmdb_next_refresh_at_epoch_ms`). */
    @Column("extras_retry_at_ms") val extrasRetryAtMs: Long?,
)

/** A show you rated (seasons or as a whole) or marked Watched. Season ratings come from [StatsDAO.findSeasons]. */
data class StatsShowDbRow(
    @Column("tmdb_id") val tmdbId: Int,
    val genres: String?,
    @Column("release_year") val releaseYear: Int?,
    @Column("show_score") val showScore: Double?,
    @Column("show_rated_at_ms") val showRatedAtMs: Long?,
    /** Set when the status is Watched. */
    @Column("watched_at_ms") val watchedAtMs: Long?,
)

data class StatsSeasonDbRow(
    @Column("tmdb_id") val tmdbId: Int,
    @Column("season_number") val seasonNumber: Int,
    val score: Double,
    @Column("at_ms") val atMs: Long,
)

/** Title, poster and status for the "Highest rated" tiles (at most ten per type). */
data class StatsTitleDbRow(
    @Column("tmdb_id") val tmdbId: Int,
    val title: String,
    @Column("release_year") val releaseYear: Int?,
    @Column("release_date") val releaseDate: String?,
    @Column("poster_path") val posterPath: String?,
    val status: String?,
)

class StatsMarker

/*
 * Fork: the Stats page (docs/design-decisions.md "Stats: your year in movies"). Three queries read everything one user
 * has rated or marked seen (one row per movie, per show and per rated season); the period is chosen and counted in
 * StatsCalculator, so a year boundary follows the app's time zone and the TV rule is applied in one place.
 * Runtimes come from the title_extras cache (filled in the background by StatsRuntimeFiller), never from TMDB here.
 */
@SpringRepository
interface StatsDAO : Repository<StatsMarker, Long> {

    @Query(
        """
        SELECT m.tmdb_id AS tmdb_id, TRUE AS rated, r.score AS score, r.created_at_epoch_ms AS at_ms,
               m.genres AS genres, m.release_year AS release_year, te.runtime_minutes AS runtime_minutes,
               (te.tmdb_fetched_at_epoch_ms IS NOT NULL) AS extras_fetched,
               te.tmdb_next_refresh_at_epoch_ms AS extras_retry_at_ms
        FROM movie_ratings r
        JOIN movies m ON m.id = r.movie_id
        LEFT JOIN title_extras te ON te.media_type = 'movie' AND te.tmdb_id = m.tmdb_id
        WHERE r.user_id = :userId
        UNION ALL
        SELECT m.tmdb_id, FALSE, NULL, ms.updated_at_epoch_ms,
               m.genres, m.release_year, te.runtime_minutes,
               (te.tmdb_fetched_at_epoch_ms IS NOT NULL),
               te.tmdb_next_refresh_at_epoch_ms
        FROM media_statuses ms
        JOIN movies m ON m.id = ms.media_id
        LEFT JOIN title_extras te ON te.media_type = 'movie' AND te.tmdb_id = m.tmdb_id
        WHERE ms.user_id = :userId AND ms.media_type = 'movie' AND ms.status = 'WATCHED'
          AND NOT EXISTS (SELECT 1 FROM movie_ratings r2 WHERE r2.user_id = ms.user_id AND r2.movie_id = m.id)
        """
    )
    fun findMovies(userId: Long): List<StatsMovieDbRow>

    @Query(
        """
        WITH mine AS (
            SELECT tv_show_id AS id FROM tv_ratings WHERE user_id = :userId
            UNION
            SELECT media_id FROM media_statuses WHERE user_id = :userId AND media_type = 'tvshow' AND status = 'WATCHED'
        )
        SELECT t.tmdb_id AS tmdb_id, t.genres AS genres, t.first_air_year AS release_year,
               (SELECT tr.show_score FROM tv_ratings tr WHERE tr.tv_show_id = t.id AND tr.user_id = :userId
                 ORDER BY tr.id LIMIT 1) AS show_score,
               (SELECT COALESCE(tr.show_rated_at_epoch_ms, tr.created_at_epoch_ms) FROM tv_ratings tr
                 WHERE tr.tv_show_id = t.id AND tr.user_id = :userId ORDER BY tr.id LIMIT 1) AS show_rated_at_ms,
               (SELECT ms.updated_at_epoch_ms FROM media_statuses ms WHERE ms.user_id = :userId
                 AND ms.media_type = 'tvshow' AND ms.media_id = t.id AND ms.status = 'WATCHED') AS watched_at_ms
        FROM mine
        JOIN tv t ON t.id = mine.id
        """
    )
    fun findShows(userId: Long): List<StatsShowDbRow>

    @Query(
        """
        SELECT t.tmdb_id AS tmdb_id, sr.season_number AS season_number, sr.score AS score,
               sr.created_at_epoch_ms AS at_ms
        FROM season_ratings sr
        JOIN tv t ON t.id = sr.tv_show_id
        WHERE sr.user_id = :userId
        """
    )
    fun findSeasons(userId: Long): List<StatsSeasonDbRow>

    @Query(
        """
        SELECT m.tmdb_id AS tmdb_id,
               COALESCE(
                   (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = :lang LIMIT 1),
                   (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = 'en' LIMIT 1),
                   m.original_title) AS title,
               m.release_year AS release_year, m.release_date AS release_date, m.poster_path AS poster_path,
               (SELECT ms.status FROM media_statuses ms WHERE ms.user_id = :userId AND ms.media_type = 'movie'
                 AND ms.media_id = m.id) AS status
        FROM movies m
        WHERE m.tmdb_id IN (:tmdbIds)
        """
    )
    fun findMovieTitles(userId: Long, tmdbIds: Collection<Int>, lang: String): List<StatsTitleDbRow>

    @Query(
        """
        SELECT t.tmdb_id AS tmdb_id,
               COALESCE(
                   (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = :lang LIMIT 1),
                   (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = 'en' LIMIT 1),
                   t.original_name) AS title,
               t.first_air_year AS release_year, t.first_air_date AS release_date, t.poster_path AS poster_path,
               (SELECT ms.status FROM media_statuses ms WHERE ms.user_id = :userId AND ms.media_type = 'tvshow'
                 AND ms.media_id = t.id) AS status
        FROM tv t
        WHERE t.tmdb_id IN (:tmdbIds)
        """
    )
    fun findShowTitles(userId: Long, tmdbIds: Collection<Int>, lang: String): List<StatsTitleDbRow>
}
