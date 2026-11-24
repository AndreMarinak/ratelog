package org.ratelog.customlist.saved.intitle

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class OfficialTitleListRow(
    @Column("list_id") val listId: Long,
    val name: String,
    @Column("owner_id") val ownerId: Long,
    /** Null on a plain (unnumbered) list, which shows no ranks. */
    val rank: Long?,
    /** When the viewer saved the list, null when they haven't. */
    @Column("saved_at") val savedAt: Long?,
)

class OfficialTitleListMarker

/*
 * Every public official list that contains one title, in one query: the title page's "In official lists" chips and the
 * add-to-list sheet's "Also in your saved lists" line. The rank is computed like ListMembershipDAO (items before it in
 * list order: position, then id), NULL on a plain list (custom_list_options.numbered = false; no row = numbered).
 * A private official list is hidden, so it is left out for everyone, its owner included (they see it as their own list).
 */
@SpringRepository
interface OfficialTitleListsDAO : Repository<OfficialTitleListMarker, Long> {

    @Query(
        """
        SELECT l.id AS list_id, l.name AS name, l.user_id AS owner_id,
               CASE WHEN COALESCE(opt.numbered, TRUE) THEN
                   (SELECT COUNT(*) FROM custom_list_items o
                     WHERE o.list_id = i.list_id
                       AND (o.position < i.position OR (o.position = i.position AND o.id <= i.id)))
               END AS rank,
               s.saved_at_epoch_ms AS saved_at
        FROM custom_list_items i
        JOIN official_lists ol ON ol.list_id = i.list_id
        JOIN custom_lists l ON l.id = i.list_id
        LEFT JOIN custom_list_options opt ON opt.list_id = l.id
        LEFT JOIN saved_lists s ON s.list_id = l.id AND s.user_id = :viewerId
        WHERE l.is_public = true AND i.media_type = :mediaType AND i.media_id = :mediaId
        ORDER BY l.id
        """
    )
    fun findContaining(viewerId: Long, mediaType: String, mediaId: Long): List<OfficialTitleListRow>
}
