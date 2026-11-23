package org.ratelog.customlist.membership

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class TitleInListRow(
    @Column("list_id") val listId: Long,
    val name: String,
    @Column("item_id") val itemId: Long,
    /** Null on a plain (unnumbered) list, which shows no ranks. */
    val rank: Long?,
)

class ListMembershipMarker

/*
 * custom_list_items is UNIQUE(list_id, media_id, media_type), so a list appears at most once.
 * The rank counts the items before it in list order (position, then id), like ListProgress on the list page; it is NULL
 * on a plain list (custom_list_options.numbered = false, V1121), which shows no ranks. No options row = numbered.
 */
@SpringRepository
interface ListMembershipDAO : Repository<ListMembershipMarker, Long> {

    @Query(
        """
        SELECT l.id AS list_id, l.name AS name, i.id AS item_id,
               CASE WHEN COALESCE(opt.numbered, TRUE) THEN
                   (SELECT COUNT(*) FROM custom_list_items o
                     WHERE o.list_id = i.list_id
                       AND (o.position < i.position OR (o.position = i.position AND o.id <= i.id)))
               END AS rank
        FROM custom_list_items i
        JOIN custom_lists l ON l.id = i.list_id
        LEFT JOIN custom_list_options opt ON opt.list_id = l.id
        WHERE l.user_id = :userId AND i.media_type = :mediaType AND i.media_id = :mediaId
        ORDER BY l.created_at_epoch_ms DESC, l.id DESC
        """
    )
    fun findListsContaining(userId: Long, mediaType: String, mediaId: Long): List<TitleInListRow>
}
