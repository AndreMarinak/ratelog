package org.ratelog.feed

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class FeedItemEntity(
    val tmdbId: Int,
    val title: String,
    val score: Double?,
    val reviewTitle: String?,
    val reviewText: String?,
    val username: String,
    val date: Long,
    val mediaType: String,
    val seasonNumber: Int?,
)

class FeedMarker

@SpringRepository
interface FeedDAO : Repository<FeedMarker, Long> {

    @Query(
        """
        SELECT
            m.tmdb_id AS tmdb_id,
            COALESCE(md.title, m.original_title, '') AS title,
            mr.score AS score,
            mr.review_title AS review_title,
            mr.review_text AS review_text,
            u.username AS username,
            mr.created_at_epoch_ms AS date,
            'movie' AS media_type,
            NULL AS season_number
        FROM movie_ratings mr
        JOIN movies m ON mr.movie_id = m.id
        JOIN users u ON mr.user_id = u.id
        LEFT JOIN movie_descriptions md ON m.tmdb_id = md.tmdb_id AND md.lang = :lang
        WHERE u.id IN (:userIds)

        UNION ALL

        SELECT
            t.tmdb_id AS tmdb_id,
            COALESCE(td.name, t.original_name, '') AS title,
            sr.score AS score,
            sr.review_title AS review_title,
            sr.review_text AS review_text,
            u.username AS username,
            sr.created_at_epoch_ms AS date,
            'tvshow' AS media_type,
            sr.season_number AS season_number
        FROM season_ratings sr
        JOIN tv t ON t.id = sr.tv_show_id
        JOIN users u ON u.id = sr.user_id
        LEFT JOIN tv_descriptions td ON t.tmdb_id = td.tmdb_id AND td.lang = :lang
        WHERE u.id IN (:userIds)

        UNION ALL

        -- Fork: a show rated as a whole (no season rated; once one is, the seasons stand for it).
        SELECT
            t.tmdb_id AS tmdb_id,
            COALESCE(td.name, t.original_name, '') AS title,
            r.show_score AS score,
            NULL AS review_title,
            NULL AS review_text,
            u.username AS username,
            COALESCE(r.show_rated_at_epoch_ms, r.created_at_epoch_ms) AS date,
            'tvshow' AS media_type,
            NULL AS season_number
        FROM tv_ratings r
        JOIN tv t ON t.id = r.tv_show_id
        JOIN users u ON u.id = r.user_id
        LEFT JOIN tv_descriptions td ON t.tmdb_id = td.tmdb_id AND td.lang = :lang
        WHERE u.id IN (:userIds) AND r.show_score IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM season_ratings sr2 WHERE sr2.tv_show_id = r.tv_show_id AND sr2.user_id = r.user_id)

        ORDER BY date DESC
        LIMIT :limit
        """
    )
    fun findAll(userIds: List<Long>, lang: String, limit: Int): List<FeedItemEntity>

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT mr.id
            FROM movie_ratings mr
            JOIN users u ON mr.user_id = u.id
            WHERE u.id IN (:userIds)

            UNION ALL

            SELECT sr.id
            FROM season_ratings sr
            JOIN users u ON u.id = sr.user_id
            WHERE u.id IN (:userIds)

            UNION ALL

            SELECT r.id
            FROM tv_ratings r
            WHERE r.user_id IN (:userIds) AND r.show_score IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM season_ratings sr2 WHERE sr2.tv_show_id = r.tv_show_id AND sr2.user_id = r.user_id)
        ) AS combined
        """
    )
    fun count(userIds: List<Long>): Long
}
