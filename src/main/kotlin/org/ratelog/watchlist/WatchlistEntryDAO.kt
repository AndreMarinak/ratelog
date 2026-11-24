package org.ratelog.watchlist

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Table("watchlist_entries")
data class WatchlistEntryEntity(
    @Id val id: Long? = null,
    @Column("user_id") val userId: Long,
    @Column("media_type") val mediaType: String,
    @Column("media_id") val mediaId: Long,
    @Column("added_at_epoch_ms") val addedAtEpochMs: Long,
    val rewatch: Boolean,
)

@Repository
interface WatchlistEntryDAO : CrudRepository<WatchlistEntryEntity, Long> {
    @Query("SELECT * FROM watchlist_entries WHERE user_id = :userId AND media_type = :mediaType AND media_id = :mediaId")
    fun findOne(userId: Long, mediaType: String, mediaId: Long): Optional<WatchlistEntryEntity>

    @Query(
        """
        SELECT * FROM watchlist_entries
        WHERE user_id = :userId
          AND (CAST(:mediaType AS VARCHAR) IS NULL OR media_type = :mediaType)
        ORDER BY added_at_epoch_ms DESC, id DESC
        """
    )
    fun findByUser(userId: Long, mediaType: String?): List<WatchlistEntryEntity>

    @Query(
        """
        SELECT COUNT(*) FROM watchlist_entries
        WHERE user_id = :userId
          AND (CAST(:mediaType AS VARCHAR) IS NULL OR media_type = :mediaType)
        """
    )
    fun countByUser(userId: Long, mediaType: String?): Long

    /** Insert; a title already on the watchlist keeps its date and only gets the new rewatch flag. */
    @Modifying
    @Query(
        """
        INSERT INTO watchlist_entries (user_id, media_type, media_id, added_at_epoch_ms, rewatch)
        VALUES (:userId, :mediaType, :mediaId, :addedAtEpochMs, :rewatch)
        ON CONFLICT (user_id, media_type, media_id) DO UPDATE SET rewatch = EXCLUDED.rewatch
        """
    )
    fun upsert(userId: Long, mediaType: String, mediaId: Long, addedAtEpochMs: Long, rewatch: Boolean)

    @Modifying
    @Query("DELETE FROM watchlist_entries WHERE user_id = :userId AND media_type = :mediaType AND media_id = :mediaId")
    fun deleteOne(userId: Long, mediaType: String, mediaId: Long): Int
}
