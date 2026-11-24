package org.ratelog.home

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class HomeTitleRow(
    @Column("media_type") val mediaType: String,
    @Column("tmdb_id") val tmdbId: Int,
    val title: String,
    @Column("release_year") val releaseYear: Int?,
    @Column("poster_path") val posterPath: String?,
    val status: String?,
    val score: Double?,
    @Column("sort_key") val sortKey: Long?,
    /** Fork: release date (a show's first air date), for the Upcoming badge. */
    @Column("release_date") val releaseDate: String? = null,
    /** Fork (2026-11-24): on the watchlist (independent of the status). */
    @Column("on_watchlist") val onWatchlist: Boolean = false,
)

data class TitleMarkRow(
    @Column("tmdb_id") val tmdbId: Int,
    val status: String?,
    val score: Double?,
    @Column("on_watchlist") val onWatchlist: Boolean = false,
)

class HomeMarker

/*
 * Titles: the user's metadata language, then English, then the original title (same fallback as the watchlist).
 * Scalar subqueries keep one row per title even if a description or rating row is duplicated.
 */
@SpringRepository
interface HomeDAO : Repository<HomeMarker, Long> {

    @Query(
        """
        SELECT * FROM (
            SELECT 'movie' AS media_type,
                   m.tmdb_id AS tmdb_id,
                   COALESCE(
                       (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = :lang LIMIT 1),
                       (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = 'en' LIMIT 1),
                       m.original_title) AS title,
                   m.release_year AS release_year,
                   m.release_date AS release_date,
                   m.poster_path AS poster_path,
                   ms.status AS status,
                   (SELECT r.score FROM movie_ratings r WHERE r.movie_id = m.id AND r.user_id = ms.user_id LIMIT 1) AS score,
                   ms.updated_at_epoch_ms AS sort_key,
                   EXISTS (SELECT 1 FROM watchlist_entries w WHERE w.user_id = ms.user_id AND w.media_type = 'movie' AND w.media_id = m.id) AS on_watchlist
            FROM media_statuses ms
            JOIN movies m ON m.id = ms.media_id
            WHERE ms.user_id = :userId AND ms.status = :status AND ms.media_type = 'movie'

            UNION ALL

            SELECT 'tvshow' AS media_type,
                   t.tmdb_id AS tmdb_id,
                   COALESCE(
                       (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = :lang LIMIT 1),
                       (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = 'en' LIMIT 1),
                       t.original_name) AS title,
                   t.first_air_year AS release_year,
                   t.first_air_date AS release_date,
                   t.poster_path AS poster_path,
                   ms.status AS status,
                   (SELECT r.score FROM tv_ratings r WHERE r.tv_show_id = t.id AND r.user_id = ms.user_id LIMIT 1) AS score,
                   ms.updated_at_epoch_ms AS sort_key,
                   EXISTS (SELECT 1 FROM watchlist_entries w WHERE w.user_id = ms.user_id AND w.media_type = 'tvshow' AND w.media_id = t.id) AS on_watchlist
            FROM media_statuses ms
            JOIN tv t ON t.id = ms.media_id
            WHERE ms.user_id = :userId AND ms.status = :status AND ms.media_type = 'tvshow'
        ) titles
        ORDER BY sort_key DESC, tmdb_id DESC
        LIMIT :limit
        """
    )
    fun findByStatus(userId: Long, status: String, lang: String, limit: Int): List<HomeTitleRow>

    /** Fork (2026-11-24): the titles on the watchlist (seen ones too), most recently added first, with their status. */
    @Query(
        """
        SELECT * FROM (
            SELECT 'movie' AS media_type,
                   m.tmdb_id AS tmdb_id,
                   COALESCE(
                       (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = :lang LIMIT 1),
                       (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = 'en' LIMIT 1),
                       m.original_title) AS title,
                   m.release_year AS release_year,
                   m.release_date AS release_date,
                   m.poster_path AS poster_path,
                   (SELECT s.status FROM media_statuses s
                     WHERE s.user_id = w.user_id AND s.media_type = 'movie' AND s.media_id = m.id) AS status,
                   (SELECT r.score FROM movie_ratings r WHERE r.movie_id = m.id AND r.user_id = w.user_id LIMIT 1) AS score,
                   w.added_at_epoch_ms AS sort_key,
                   TRUE AS on_watchlist
            FROM watchlist_entries w
            JOIN movies m ON m.id = w.media_id
            WHERE w.user_id = :userId AND w.media_type = 'movie'

            UNION ALL

            SELECT 'tvshow' AS media_type,
                   t.tmdb_id AS tmdb_id,
                   COALESCE(
                       (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = :lang LIMIT 1),
                       (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = 'en' LIMIT 1),
                       t.original_name) AS title,
                   t.first_air_year AS release_year,
                   t.first_air_date AS release_date,
                   t.poster_path AS poster_path,
                   (SELECT s.status FROM media_statuses s
                     WHERE s.user_id = w.user_id AND s.media_type = 'tvshow' AND s.media_id = t.id) AS status,
                   (SELECT r.score FROM tv_ratings r WHERE r.tv_show_id = t.id AND r.user_id = w.user_id LIMIT 1) AS score,
                   w.added_at_epoch_ms AS sort_key,
                   TRUE AS on_watchlist
            FROM watchlist_entries w
            JOIN tv t ON t.id = w.media_id
            WHERE w.user_id = :userId AND w.media_type = 'tvshow'
        ) titles
        ORDER BY sort_key DESC, tmdb_id DESC
        LIMIT :limit
        """
    )
    fun findWatchlist(userId: Long, lang: String, limit: Int): List<HomeTitleRow>

    @Query(
        """
        SELECT * FROM (
            SELECT 'movie' AS media_type,
                   m.tmdb_id AS tmdb_id,
                   COALESCE(
                       (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = :lang LIMIT 1),
                       (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = 'en' LIMIT 1),
                       m.original_title) AS title,
                   m.release_year AS release_year,
                   m.release_date AS release_date,
                   m.poster_path AS poster_path,
                   (SELECT s.status FROM media_statuses s
                     WHERE s.user_id = r.user_id AND s.media_type = 'movie' AND s.media_id = m.id) AS status,
                   r.score AS score,
                   r.created_at_epoch_ms AS sort_key,
                   EXISTS (SELECT 1 FROM watchlist_entries w WHERE w.user_id = r.user_id AND w.media_type = 'movie' AND w.media_id = m.id) AS on_watchlist
            FROM movie_ratings r
            JOIN movies m ON m.id = r.movie_id
            WHERE r.user_id = :userId AND r.score IS NOT NULL

            UNION ALL

            SELECT 'tvshow' AS media_type,
                   t.tmdb_id AS tmdb_id,
                   COALESCE(
                       (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = :lang LIMIT 1),
                       (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = 'en' LIMIT 1),
                       t.original_name) AS title,
                   t.first_air_year AS release_year,
                   t.first_air_date AS release_date,
                   t.poster_path AS poster_path,
                   (SELECT s.status FROM media_statuses s
                     WHERE s.user_id = r.user_id AND s.media_type = 'tvshow' AND s.media_id = t.id) AS status,
                   r.score AS score,
                   COALESCE(
                       (SELECT MAX(sr.created_at_epoch_ms) FROM season_ratings sr
                         WHERE sr.tv_show_id = r.tv_show_id AND sr.user_id = r.user_id),
                       r.show_rated_at_epoch_ms,
                       r.created_at_epoch_ms) AS sort_key,
                   EXISTS (SELECT 1 FROM watchlist_entries w WHERE w.user_id = r.user_id AND w.media_type = 'tvshow' AND w.media_id = t.id) AS on_watchlist
            FROM tv_ratings r
            JOIN tv t ON t.id = r.tv_show_id
            WHERE r.user_id = :userId AND r.score IS NOT NULL
        ) titles
        ORDER BY sort_key DESC, tmdb_id DESC
        LIMIT :limit
        """
    )
    fun findRecentlyRated(userId: Long, lang: String, limit: Int): List<HomeTitleRow>

    @Query(
        """
        SELECT m.tmdb_id AS tmdb_id,
               (SELECT s.status FROM media_statuses s
                 WHERE s.user_id = :userId AND s.media_type = 'movie' AND s.media_id = m.id) AS status,
               (SELECT r.score FROM movie_ratings r WHERE r.user_id = :userId AND r.movie_id = m.id LIMIT 1) AS score,
               EXISTS (SELECT 1 FROM watchlist_entries w WHERE w.user_id = :userId AND w.media_type = 'movie' AND w.media_id = m.id) AS on_watchlist
        FROM movies m
        WHERE m.tmdb_id IN (:tmdbIds)
        """
    )
    fun findMovieMarks(userId: Long, tmdbIds: List<Int>): List<TitleMarkRow>

    @Query(
        """
        SELECT t.tmdb_id AS tmdb_id,
               (SELECT s.status FROM media_statuses s
                 WHERE s.user_id = :userId AND s.media_type = 'tvshow' AND s.media_id = t.id) AS status,
               (SELECT r.score FROM tv_ratings r WHERE r.user_id = :userId AND r.tv_show_id = t.id LIMIT 1) AS score,
               EXISTS (SELECT 1 FROM watchlist_entries w WHERE w.user_id = :userId AND w.media_type = 'tvshow' AND w.media_id = t.id) AS on_watchlist
        FROM tv t
        WHERE t.tmdb_id IN (:tmdbIds)
        """
    )
    fun findTvMarks(userId: Long, tmdbIds: List<Int>): List<TitleMarkRow>
}
