package org.ratelog.browse

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class LibraryMarkRow(
    val tmdbId: Int,
    val score: Double?,
    val rated: Boolean,
    val status: String?,
    /** Fork (2026-11-24): on the watchlist (independent of the status). */
    @org.springframework.data.relational.core.mapping.Column("on_watchlist") val onWatchlist: Boolean = false,
)

class BrowseLibraryMarker

/** Read-only lookups of the user's ratings and statuses by TMDB id (media_statuses.media_id is the local id). */
@SpringRepository
interface BrowseLibraryDAO : Repository<BrowseLibraryMarker, Long> {

    @Query(
        """
        SELECT m.tmdb_id AS tmdb_id,
               (SELECT r.score FROM movie_ratings r
                 WHERE r.movie_id = m.id AND r.user_id = :userId ORDER BY r.id DESC LIMIT 1) AS score,
               EXISTS (SELECT 1 FROM movie_ratings r WHERE r.movie_id = m.id AND r.user_id = :userId) AS rated,
               (SELECT s.status FROM media_statuses s
                 WHERE s.user_id = :userId AND s.media_type = 'movie' AND s.media_id = m.id) AS status,
               EXISTS (SELECT 1 FROM watchlist_entries w WHERE w.user_id = :userId AND w.media_type = 'movie' AND w.media_id = m.id) AS on_watchlist
        FROM movies m
        WHERE m.tmdb_id IN (:tmdbIds)
        """
    )
    fun findMovieMarks(userId: Long, tmdbIds: List<Int>): List<LibraryMarkRow>

    @Query(
        """
        SELECT t.tmdb_id AS tmdb_id,
               (SELECT r.score FROM tv_ratings r
                 WHERE r.tv_show_id = t.id AND r.user_id = :userId ORDER BY r.id DESC LIMIT 1) AS score,
               (EXISTS (SELECT 1 FROM season_ratings sr WHERE sr.tv_show_id = t.id AND sr.user_id = :userId) OR EXISTS (SELECT 1 FROM tv_ratings tr WHERE tr.tv_show_id = t.id AND tr.user_id = :userId AND tr.show_score IS NOT NULL)) AS rated,
               (SELECT s.status FROM media_statuses s
                 WHERE s.user_id = :userId AND s.media_type = 'tvshow' AND s.media_id = t.id) AS status,
               EXISTS (SELECT 1 FROM watchlist_entries w WHERE w.user_id = :userId AND w.media_type = 'tvshow' AND w.media_id = t.id) AS on_watchlist
        FROM tv t
        WHERE t.tmdb_id IN (:tmdbIds)
        """
    )
    fun findTvMarks(userId: Long, tmdbIds: List<Int>): List<LibraryMarkRow>
}
