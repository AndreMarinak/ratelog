package org.ratelog.customlist.progress

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class ViewerMarkRow(
    @Column("media_id") val mediaId: Long,
    val status: String?,
    val score: Double?,
    val rated: Boolean,
    /** Fork (2026-11-24): on the watchlist (independent of the status). */
    @Column("on_watchlist") val onWatchlist: Boolean = false,
)

data class ListSeenRow(
    @Column("list_id") val listId: Long,
    val seen: Long,
)

class ListProgressMarker

/*
 * "Rated" matches Browse's hide-rated filter: a movie_ratings row for movies, at least one season_ratings row (or, fork, a whole-show score) for TV.
 * "Seen" (SeenState): a movie rated or Watched; a show Watched, or rated with no status at all.
 * The status and score are scalar subqueries, so a title is one row even if a rating row were duplicated.
 */
@SpringRepository
interface ListProgressDAO : Repository<ListProgressMarker, Long> {

    @Query(
        """
        SELECT m.id AS media_id,
               (SELECT s.status FROM media_statuses s
                 WHERE s.user_id = :userId AND s.media_type = 'movie' AND s.media_id = m.id) AS status,
               (SELECT r.score FROM movie_ratings r
                 WHERE r.movie_id = m.id AND r.user_id = :userId ORDER BY r.id DESC LIMIT 1) AS score,
               EXISTS (SELECT 1 FROM movie_ratings r WHERE r.movie_id = m.id AND r.user_id = :userId) AS rated,
               EXISTS (SELECT 1 FROM watchlist_entries w WHERE w.user_id = :userId AND w.media_type = 'movie' AND w.media_id = m.id) AS on_watchlist
        FROM movies m
        WHERE m.id IN (:mediaIds)
        """
    )
    fun findMovieMarks(userId: Long, mediaIds: List<Long>): List<ViewerMarkRow>

    @Query(
        """
        SELECT t.id AS media_id,
               (SELECT s.status FROM media_statuses s
                 WHERE s.user_id = :userId AND s.media_type = 'tvshow' AND s.media_id = t.id) AS status,
               (SELECT r.score FROM tv_ratings r
                 WHERE r.tv_show_id = t.id AND r.user_id = :userId ORDER BY r.id DESC LIMIT 1) AS score,
               (EXISTS (SELECT 1 FROM season_ratings sr WHERE sr.tv_show_id = t.id AND sr.user_id = :userId) OR EXISTS (SELECT 1 FROM tv_ratings tr WHERE tr.tv_show_id = t.id AND tr.user_id = :userId AND tr.show_score IS NOT NULL)) AS rated,
               EXISTS (SELECT 1 FROM watchlist_entries w WHERE w.user_id = :userId AND w.media_type = 'tvshow' AND w.media_id = t.id) AS on_watchlist
        FROM tv t
        WHERE t.id IN (:mediaIds)
        """
    )
    fun findTvMarks(userId: Long, mediaIds: List<Long>): List<ViewerMarkRow>

    /** Seen titles per list (Watched; a rated movie; a rated show with no status). Lists with none are left out. */
    @Query(
        """
        SELECT i.list_id AS list_id, COUNT(*) AS seen
        FROM custom_list_items i
        WHERE i.list_id IN (:listIds)
          AND (
                EXISTS (SELECT 1 FROM media_statuses s
                         WHERE s.user_id = :userId AND s.media_type = i.media_type
                           AND s.media_id = i.media_id AND s.status = 'WATCHED')
             OR (i.media_type = 'movie'
                 AND EXISTS (SELECT 1 FROM movie_ratings r WHERE r.user_id = :userId AND r.movie_id = i.media_id))
             OR (i.media_type = 'tvshow'
                 AND (EXISTS (SELECT 1 FROM season_ratings sr WHERE sr.tv_show_id = i.media_id AND sr.user_id = :userId) OR EXISTS (SELECT 1 FROM tv_ratings tr WHERE tr.tv_show_id = i.media_id AND tr.user_id = :userId AND tr.show_score IS NOT NULL))
                 AND NOT EXISTS (SELECT 1 FROM media_statuses s
                                  WHERE s.user_id = :userId AND s.media_type = 'tvshow' AND s.media_id = i.media_id))
          )
        GROUP BY i.list_id
        """
    )
    fun countSeenByList(userId: Long, listIds: List<Long>): List<ListSeenRow>

    /**
     * Puts these list items on the watchlist in one statement (fork, 2026-11-24: `watchlist_entries`). Titles with any
     * status (Watching / Watched / Dropped, even one set a moment ago), rated titles and titles missing from movies / tv
     * are skipped; `ON CONFLICT DO NOTHING` leaves titles already on the watchlist as they are. Earlier list positions
     * get later timestamps, so the watchlist (newest first) shows them in list order.
     */
    @Modifying
    @Query(
        """
        INSERT INTO watchlist_entries (user_id, media_type, media_id, added_at_epoch_ms, rewatch)
        SELECT :userId, i.media_type, i.media_id,
               :nowEpochMs - ROW_NUMBER() OVER (ORDER BY i.position, i.id) + 1, FALSE
        FROM custom_list_items i
        WHERE i.list_id = :listId
          AND i.id IN (:itemIds)
          AND NOT EXISTS (SELECT 1 FROM media_statuses s
                           WHERE s.user_id = :userId AND s.media_type = i.media_type AND s.media_id = i.media_id)
          AND (
                (i.media_type = 'movie'
                 AND EXISTS (SELECT 1 FROM movies m WHERE m.id = i.media_id)
                 AND NOT EXISTS (SELECT 1 FROM movie_ratings r WHERE r.user_id = :userId AND r.movie_id = i.media_id))
             OR (i.media_type = 'tvshow'
                 AND EXISTS (SELECT 1 FROM tv t WHERE t.id = i.media_id)
                 AND NOT (EXISTS (SELECT 1 FROM season_ratings sr WHERE sr.tv_show_id = i.media_id AND sr.user_id = :userId) OR EXISTS (SELECT 1 FROM tv_ratings tr WHERE tr.tv_show_id = i.media_id AND tr.user_id = :userId AND tr.show_score IS NOT NULL)))
          )
        ON CONFLICT (user_id, media_type, media_id) DO NOTHING
        """
    )
    fun insertWantToWatch(userId: Long, listId: Long, itemIds: List<Long>, nowEpochMs: Long): Int
}
