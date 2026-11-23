package org.ratelog.customlist.official

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

class OfficialListMarker

@SpringRepository
interface OfficialListDAO : Repository<OfficialListMarker, Long> {

    @Query("SELECT EXISTS (SELECT 1 FROM official_lists WHERE list_id = :listId)")
    fun exists(listId: Long): Boolean

    @Query("SELECT list_id FROM official_lists WHERE list_id IN (:listIds)")
    fun findIn(listIds: List<Long>): List<Long>

    /** 1 when the row was added, 0 when the list was already official. */
    @Modifying
    @Query("INSERT INTO official_lists (list_id, created_at_epoch_ms) VALUES (:listId, :atEpochMs) ON CONFLICT (list_id) DO NOTHING")
    fun insert(listId: Long, atEpochMs: Long): Int

    @Modifying
    @Query("DELETE FROM official_lists WHERE list_id = :listId")
    fun delete(listId: Long): Int
}
