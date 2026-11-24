package org.ratelog.customlist.saved

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class SavedListRow(
    @Column("list_id") val listId: Long,
    @Column("saved_at_epoch_ms") val savedAtEpochMs: Long,
)

class SavedListMarker

/** `saved_lists` (V1142): primary key (user_id, list_id), so a list is saved at most once per user. */
@SpringRepository
interface SavedListDAO : Repository<SavedListMarker, Long> {

    @Query("SELECT list_id, saved_at_epoch_ms FROM saved_lists WHERE user_id = :userId ORDER BY saved_at_epoch_ms DESC, list_id DESC")
    fun findByUser(userId: Long): List<SavedListRow>

    @Query("SELECT EXISTS (SELECT 1 FROM saved_lists WHERE user_id = :userId AND list_id = :listId)")
    fun exists(userId: Long, listId: Long): Boolean

    /** 1 when the row was added, 0 when the list was already saved (the first save time is kept). */
    @Modifying
    @Query(
        """
        INSERT INTO saved_lists (user_id, list_id, saved_at_epoch_ms) VALUES (:userId, :listId, :atEpochMs)
        ON CONFLICT (user_id, list_id) DO NOTHING
        """
    )
    fun insert(userId: Long, listId: Long, atEpochMs: Long): Int

    @Modifying
    @Query("DELETE FROM saved_lists WHERE user_id = :userId AND list_id = :listId")
    fun delete(userId: Long, listId: Long): Int
}
