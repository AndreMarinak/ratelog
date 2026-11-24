package org.ratelog.ratings.unrated

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class UnratedSeenRow(
    @Column("tmdb_id") val tmdbId: Int,
    val title: String,
    @Column("release_year") val releaseYear: Int?,
    @Column("release_date") val releaseDate: String?,
    @Column("poster_path") val posterPath: String?,
    @Column("sort_key") val sortKey: Long,
)

class UnratedSeenMarker

/*
 * "Seen, not rated" on the Ratings tab: status Watched and no rating at all (a show: no rated season). Newest Seen
 * first (the status's update time). Titles use the user's metadata language, then English, then the original title,
 * like the home rows. Scalar subqueries keep one row per title.
 */
@SpringRepository
interface UnratedSeenDAO : Repository<UnratedSeenMarker, Long> {

    @Query(
        """
        SELECT m.tmdb_id AS tmdb_id,
               COALESCE(
                   (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = :lang LIMIT 1),
                   (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = 'en' LIMIT 1),
                   m.original_title) AS title,
               m.release_year AS release_year,
               m.release_date AS release_date,
               m.poster_path AS poster_path,
               ms.updated_at_epoch_ms AS sort_key
        FROM media_statuses ms
        JOIN movies m ON m.id = ms.media_id
        WHERE ms.user_id = :userId AND ms.media_type = 'movie' AND ms.status = 'WATCHED'
          AND NOT EXISTS (SELECT 1 FROM movie_ratings r WHERE r.user_id = ms.user_id AND r.movie_id = m.id)
        ORDER BY ms.updated_at_epoch_ms DESC, m.tmdb_id DESC
        LIMIT :limit
        """
    )
    fun findMovies(userId: Long, lang: String, limit: Int): List<UnratedSeenRow>

    @Query(
        """
        SELECT t.tmdb_id AS tmdb_id,
               COALESCE(
                   (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = :lang LIMIT 1),
                   (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = 'en' LIMIT 1),
                   t.original_name) AS title,
               t.first_air_year AS release_year,
               t.first_air_date AS release_date,
               t.poster_path AS poster_path,
               ms.updated_at_epoch_ms AS sort_key
        FROM media_statuses ms
        JOIN tv t ON t.id = ms.media_id
        WHERE ms.user_id = :userId AND ms.media_type = 'tvshow' AND ms.status = 'WATCHED'
          AND NOT (EXISTS (SELECT 1 FROM season_ratings sr WHERE sr.tv_show_id = t.id AND sr.user_id = ms.user_id) OR EXISTS (SELECT 1 FROM tv_ratings tr WHERE tr.tv_show_id = t.id AND tr.user_id = ms.user_id AND tr.show_score IS NOT NULL))
        ORDER BY ms.updated_at_epoch_ms DESC, t.tmdb_id DESC
        LIMIT :limit
        """
    )
    fun findShows(userId: Long, lang: String, limit: Int): List<UnratedSeenRow>

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM media_statuses ms
                 WHERE ms.user_id = :userId AND ms.media_type = 'movie' AND ms.status = 'WATCHED'
                   AND NOT EXISTS (SELECT 1 FROM movie_ratings r WHERE r.user_id = ms.user_id AND r.movie_id = ms.media_id))
             + (SELECT COUNT(*) FROM media_statuses ms
                 WHERE ms.user_id = :userId AND ms.media_type = 'tvshow' AND ms.status = 'WATCHED'
                   AND NOT (EXISTS (SELECT 1 FROM season_ratings sr WHERE sr.tv_show_id = ms.media_id AND sr.user_id = ms.user_id) OR EXISTS (SELECT 1 FROM tv_ratings tr WHERE tr.tv_show_id = ms.media_id AND tr.user_id = ms.user_id AND tr.show_score IS NOT NULL)))
        """
    )
    fun count(userId: Long): Long

    @Query(
        """
        SELECT COUNT(*) FROM media_statuses ms
        WHERE ms.user_id = :userId AND ms.media_type = 'movie' AND ms.status = 'WATCHED'
          AND NOT EXISTS (SELECT 1 FROM movie_ratings r WHERE r.user_id = ms.user_id AND r.movie_id = ms.media_id)
        """
    )
    fun countMovies(userId: Long): Long
}
