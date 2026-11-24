package org.ratelog.rewatch

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Table("rewatches")
data class RewatchEntity(
    @Id val id: Long? = null,
    @Column("user_id") val userId: Long,
    @Column("media_type") val mediaType: String,
    @Column("media_id") val mediaId: Long,
    @Column("watched_at_epoch_ms") val watchedAtEpochMs: Long,
)

/** Per title: how many rewatches and the latest one. */
data class RewatchSummaryRow(
    @Column("media_id") val mediaId: Long,
    val total: Long,
    @Column("last_at_ms") val lastAtMs: Long?,
)

@Repository
interface RewatchDAO : CrudRepository<RewatchEntity, Long> {
    @Query("SELECT * FROM rewatches WHERE id = :id AND user_id = :userId")
    fun findOne(userId: Long, id: Long): Optional<RewatchEntity>

    @Query(
        """
        SELECT media_id, COUNT(*) AS total, MAX(watched_at_epoch_ms) AS last_at_ms FROM rewatches
        WHERE user_id = :userId AND media_type = :mediaType AND media_id IN (:mediaIds)
        GROUP BY media_id
        """
    )
    fun summaries(userId: Long, mediaType: String, mediaIds: Collection<Long>): List<RewatchSummaryRow>

    @Query("SELECT * FROM rewatches WHERE user_id = :userId ORDER BY watched_at_epoch_ms DESC, id DESC")
    fun findByUser(userId: Long): List<RewatchEntity>

    @Modifying
    @Query("DELETE FROM rewatches WHERE id = :id AND user_id = :userId")
    fun deleteOne(userId: Long, id: Long): Int
}
