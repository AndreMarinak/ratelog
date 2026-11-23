package org.ratelog.customlist.order

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

class ListOrderMarker

@SpringRepository
interface ListOrderDAO : Repository<ListOrderMarker, Long> {

    @Query("SELECT id FROM custom_lists WHERE id = :listId FOR UPDATE")
    fun lockList(listId: Long): List<Long>

    /**
     * `order` is a JSON array of item ids (`[12, 5, 7]`): the n-th id gets position n. One statement however long
     * the list is; rows already at their place are left alone.
     */
    @Modifying
    @Query(
        """
        UPDATE custom_list_items AS c
           SET position = o.place::int
          FROM jsonb_array_elements_text(CAST(:order AS jsonb)) WITH ORDINALITY AS o(item_id, place)
         WHERE c.list_id = :listId
           AND c.id = o.item_id::bigint
           AND c.position IS DISTINCT FROM o.place::int
        """
    )
    fun renumber(listId: Long, order: String): Int
}

class ListOptionsMarker

@SpringRepository
interface ListOptionsDAO : Repository<ListOptionsMarker, Long> {

    @Query("SELECT numbered FROM custom_list_options WHERE list_id = :listId")
    fun findNumbered(listId: Long): List<Boolean>

    @Query("SELECT list_id FROM custom_list_options WHERE numbered = false AND list_id IN (:listIds)")
    fun findPlainIn(listIds: List<Long>): List<Long>

    @Modifying
    @Query(
        """
        INSERT INTO custom_list_options (list_id, numbered) VALUES (:listId, :numbered)
        ON CONFLICT (list_id) DO UPDATE SET numbered = EXCLUDED.numbered
        """
    )
    fun upsertNumbered(listId: Long, numbered: Boolean): Int
}
