package org.ratelog.watchlist

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Table("media_statuses")
data class MediaStatusEntity(
    @Id val id: Long? = null,
    @Column("user_id") val userId: Long,
    @Column("media_type") val mediaType: String,
    @Column("media_id") val mediaId: Long,
    val status: String,
    @Column("updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

data class StatusCountRow(
    val status: String,
    val total: Long,
)

@Repository
interface MediaStatusDAO : CrudRepository<MediaStatusEntity, Long> {
    @Query("SELECT * FROM media_statuses WHERE user_id = :userId AND media_type = :mediaType AND media_id = :mediaId")
    fun findOne(userId: Long, mediaType: String, mediaId: Long): Optional<MediaStatusEntity>

    @Query(
        """
        SELECT * FROM media_statuses
        WHERE user_id = :userId
          AND (CAST(:status AS VARCHAR) IS NULL OR status = :status)
          AND (CAST(:mediaType AS VARCHAR) IS NULL OR media_type = :mediaType)
        ORDER BY updated_at_epoch_ms DESC, id DESC
        """
    )
    fun findByUser(userId: Long, status: String?, mediaType: String?): List<MediaStatusEntity>

    @Query(
        """
        SELECT status, COUNT(*) AS total FROM media_statuses
        WHERE user_id = :userId
          AND (CAST(:mediaType AS VARCHAR) IS NULL OR media_type = :mediaType)
        GROUP BY status
        """
    )
    fun countByStatus(userId: Long, mediaType: String?): List<StatusCountRow>

    /** Insert, or replace the status of the existing row for that user + title. */
    @Modifying
    @Query(
        """
        INSERT INTO media_statuses (user_id, media_type, media_id, status, updated_at_epoch_ms)
        VALUES (:userId, :mediaType, :mediaId, :status, :updatedAtEpochMs)
        ON CONFLICT (user_id, media_type, media_id)
        DO UPDATE SET status = EXCLUDED.status, updated_at_epoch_ms = EXCLUDED.updated_at_epoch_ms
        """
    )
    fun upsert(userId: Long, mediaType: String, mediaId: Long, status: String, updatedAtEpochMs: Long)

    @Modifying
    @Query("DELETE FROM media_statuses WHERE user_id = :userId AND media_type = :mediaType AND media_id = :mediaId")
    fun deleteOne(userId: Long, mediaType: String, mediaId: Long): Int
}
