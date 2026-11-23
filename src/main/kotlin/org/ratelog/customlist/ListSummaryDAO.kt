package org.ratelog.customlist

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class ListSummaryRow(
    val id: Long,
    @Column("user_id") val userId: Long,
    val username: String,
    val name: String,
    @Column("is_public") val isPublic: Boolean,
    @Column("item_count") val itemCount: Long,
    @Column("last_activity") val lastActivity: Long,
    @Column("is_official") val isOfficial: Boolean,
)

data class ListCoverRow(
    @Column("list_id") val listId: Long,
    @Column("poster_path") val posterPath: String,
)

class ListSummaryMarker

private const val SUMMARY_COLUMNS = """
    cl.id AS id,
    cl.user_id AS user_id,
    u.username AS username,
    cl.name AS name,
    cl.is_public AS is_public,
    (SELECT COUNT(*) FROM custom_list_items i WHERE i.list_id = cl.id) AS item_count,
    GREATEST(cl.created_at_epoch_ms,
             COALESCE((SELECT MAX(i.added_at_epoch_ms) FROM custom_list_items i WHERE i.list_id = cl.id), 0)) AS last_activity,
    EXISTS (SELECT 1 FROM official_lists o WHERE o.list_id = cl.id) AS is_official
"""

/* Official lists have their own section on /lists, so the "followed" and "others" sections leave them out. */
private const val NOT_OFFICIAL = "NOT EXISTS (SELECT 1 FROM official_lists o WHERE o.list_id = cl.id)"

@SpringRepository
interface ListSummaryDAO : Repository<ListSummaryMarker, Long> {

    @Query(
        """
        SELECT $SUMMARY_COLUMNS
        FROM custom_lists cl
        JOIN users u ON u.id = cl.user_id
        WHERE cl.user_id = :ownerId
        ORDER BY last_activity DESC, id DESC
        """
    )
    fun findByOwner(ownerId: Long): List<ListSummaryRow>

    @Query(
        """
        SELECT $SUMMARY_COLUMNS
        FROM custom_lists cl
        JOIN users u ON u.id = cl.user_id
        WHERE cl.is_public = true
          AND cl.user_id IN (:ownerIds)
          AND EXISTS (SELECT 1 FROM custom_list_items i WHERE i.list_id = cl.id)
          AND $NOT_OFFICIAL
        ORDER BY last_activity DESC, id DESC
        LIMIT :limit
        """
    )
    fun findPublicByOwners(ownerIds: List<Long>, limit: Int): List<ListSummaryRow>

    @Query(
        """
        SELECT $SUMMARY_COLUMNS
        FROM custom_lists cl
        JOIN users u ON u.id = cl.user_id
        WHERE cl.is_public = true
          AND cl.user_id NOT IN (:excludedOwnerIds)
          AND EXISTS (SELECT 1 FROM custom_list_items i WHERE i.list_id = cl.id)
          AND $NOT_OFFICIAL
        ORDER BY last_activity DESC, id DESC
        LIMIT :limit
        """
    )
    fun findPublicExcludingOwners(excludedOwnerIds: List<Long>, limit: Int): List<ListSummaryRow>

    /** Every public official list, any owner. A private official list is hidden (its owner's draft) and listed nowhere else. */
    @Query(
        """
        SELECT $SUMMARY_COLUMNS
        FROM official_lists ol
        JOIN custom_lists cl ON cl.id = ol.list_id
        JOIN users u ON u.id = cl.user_id
        WHERE cl.is_public = true
        ORDER BY last_activity DESC, id DESC
        """
    )
    fun findOfficial(): List<ListSummaryRow>

    /** The first posters of each list, in list order, skipping titles without one. */
    @Query(
        """
        SELECT p.list_id AS list_id, p.poster_path AS poster_path FROM (
            SELECT i.list_id AS list_id,
                   COALESCE(m.poster_path, t.poster_path) AS poster_path,
                   ROW_NUMBER() OVER (PARTITION BY i.list_id ORDER BY i.position, i.id) AS rn
            FROM custom_list_items i
            LEFT JOIN movies m ON i.media_type = 'movie' AND m.id = i.media_id
            LEFT JOIN tv t ON i.media_type = 'tvshow' AND t.id = i.media_id
            WHERE i.list_id IN (:listIds)
              AND COALESCE(m.poster_path, t.poster_path) IS NOT NULL
        ) p
        WHERE p.rn <= :perList
        ORDER BY p.list_id, p.rn
        """
    )
    fun findCovers(listIds: List<Long>, perList: Int): List<ListCoverRow>
}
