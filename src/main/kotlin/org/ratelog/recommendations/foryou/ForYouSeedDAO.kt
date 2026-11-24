package org.ratelog.recommendations.foryou

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class ForYouSeedRow(
    @Column("tmdb_id") val tmdbId: Int,
    val title: String,
    val score: Double,
    @Column("rated_at") val ratedAt: Long?,
)

class ForYouSeedMarker

/*
 * The viewer's rated titles (same title fallback as the home rows: metadata language, English, original title).
 * A show is rated when its show row has a score; it was rated at its latest season rating (like "Recently rated").
 */
@SpringRepository
interface ForYouSeedDAO : Repository<ForYouSeedMarker, Long> {

    @Query(
        """
        SELECT m.tmdb_id AS tmdb_id,
               COALESCE(
                   (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = :lang LIMIT 1),
                   (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = 'en' LIMIT 1),
                   m.original_title) AS title,
               r.score AS score,
               r.created_at_epoch_ms AS rated_at
        FROM movie_ratings r
        JOIN movies m ON m.id = r.movie_id
        WHERE r.user_id = :userId AND r.score IS NOT NULL AND r.score >= :minScore
        ORDER BY rated_at DESC, m.tmdb_id DESC
        LIMIT :limit
        """
    )
    fun findRecentMovies(userId: Long, minScore: Double, lang: String, limit: Int): List<ForYouSeedRow>

    @Query(
        """
        SELECT m.tmdb_id AS tmdb_id,
               COALESCE(
                   (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = :lang LIMIT 1),
                   (SELECT d.title FROM movie_descriptions d WHERE d.tmdb_id = m.tmdb_id AND d.lang = 'en' LIMIT 1),
                   m.original_title) AS title,
               r.score AS score,
               r.created_at_epoch_ms AS rated_at
        FROM movie_ratings r
        JOIN movies m ON m.id = r.movie_id
        WHERE r.user_id = :userId AND r.score IS NOT NULL
        ORDER BY score DESC, rated_at DESC, m.tmdb_id DESC
        LIMIT :limit
        """
    )
    fun findTopMovies(userId: Long, lang: String, limit: Int): List<ForYouSeedRow>

    @Query(
        """
        SELECT * FROM (
            SELECT t.tmdb_id AS tmdb_id,
                   COALESCE(
                       (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = :lang LIMIT 1),
                       (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = 'en' LIMIT 1),
                       t.original_name) AS title,
                   r.score AS score,
                   COALESCE(
                       (SELECT MAX(sr.created_at_epoch_ms) FROM season_ratings sr
                         WHERE sr.tv_show_id = r.tv_show_id AND sr.user_id = r.user_id),
                       r.created_at_epoch_ms) AS rated_at
            FROM tv_ratings r
            JOIN tv t ON t.id = r.tv_show_id
            WHERE r.user_id = :userId AND r.score IS NOT NULL AND r.score >= :minScore
        ) shows
        ORDER BY rated_at DESC, tmdb_id DESC
        LIMIT :limit
        """
    )
    fun findRecentShows(userId: Long, minScore: Double, lang: String, limit: Int): List<ForYouSeedRow>

    @Query(
        """
        SELECT * FROM (
            SELECT t.tmdb_id AS tmdb_id,
                   COALESCE(
                       (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = :lang LIMIT 1),
                       (SELECT d.name FROM tv_descriptions d WHERE d.tmdb_id = t.tmdb_id AND d.lang = 'en' LIMIT 1),
                       t.original_name) AS title,
                   r.score AS score,
                   COALESCE(
                       (SELECT MAX(sr.created_at_epoch_ms) FROM season_ratings sr
                         WHERE sr.tv_show_id = r.tv_show_id AND sr.user_id = r.user_id),
                       r.created_at_epoch_ms) AS rated_at
            FROM tv_ratings r
            JOIN tv t ON t.id = r.tv_show_id
            WHERE r.user_id = :userId AND r.score IS NOT NULL
        ) shows
        ORDER BY score DESC, rated_at DESC, tmdb_id DESC
        LIMIT :limit
        """
    )
    fun findTopShows(userId: Long, lang: String, limit: Int): List<ForYouSeedRow>

    @Query("SELECT EXISTS (SELECT 1 FROM movie_ratings WHERE user_id = :userId AND score IS NOT NULL)")
    fun hasMovieRatings(userId: Long): Boolean

    @Query("SELECT EXISTS (SELECT 1 FROM tv_ratings WHERE user_id = :userId AND score IS NOT NULL)")
    fun hasShowRatings(userId: Long): Boolean
}
