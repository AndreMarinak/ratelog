package org.ratelog.tag

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Table("tags")
data class TagEntity(
    @Id val id: Long? = null,
    @Column("user_id") val userId: Long,
    val name: String,
    val color: String?,
    @Column("created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Table("tag_assignments")
data class TagAssignmentEntity(
    @Id val id: Long? = null,
    @Column("tag_id") val tagId: Long,
    @Column("media_type") val mediaType: String,
    @Column("media_id") val mediaId: Long,
    @Column("created_at_epoch_ms") val createdAtEpochMs: Long,
)

data class TagCountRow(val tagId: Long, val titleCount: Long)

@Repository
interface TagDAO : CrudRepository<TagEntity, Long> {
    @Query("SELECT * FROM tags WHERE user_id = :userId ORDER BY LOWER(name)")
    fun findByUserId(userId: Long): List<TagEntity>

    @Query("SELECT * FROM tags WHERE user_id = :userId AND LOWER(name) = LOWER(:name)")
    fun findByUserIdAndNameIgnoreCase(userId: Long, name: String): Optional<TagEntity>

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN tag_assignments ta ON ta.tag_id = t.id
        WHERE t.user_id = :userId AND ta.media_type = :mediaType AND ta.media_id = :mediaId
        ORDER BY LOWER(t.name)
        """
    )
    fun findForTitle(userId: Long, mediaType: String, mediaId: Long): List<TagEntity>
}

@Repository
interface TagAssignmentDAO : CrudRepository<TagAssignmentEntity, Long> {
    @Query("SELECT * FROM tag_assignments WHERE tag_id = :tagId ORDER BY created_at_epoch_ms DESC, id DESC")
    fun findByTagId(tagId: Long): List<TagAssignmentEntity>

    @Query(
        """
        SELECT ta.tag_id, COUNT(*) AS title_count
        FROM tag_assignments ta
        INNER JOIN tags t ON t.id = ta.tag_id
        WHERE t.user_id = :userId
        GROUP BY ta.tag_id
        """
    )
    fun countByUserId(userId: Long): List<TagCountRow>

    @Modifying
    @Query(
        """
        INSERT INTO tag_assignments (tag_id, media_type, media_id, created_at_epoch_ms)
        VALUES (:tagId, :mediaType, :mediaId, :createdAtEpochMs)
        ON CONFLICT (tag_id, media_type, media_id) DO NOTHING
        """
    )
    fun insertIfAbsent(tagId: Long, mediaType: String, mediaId: Long, createdAtEpochMs: Long)

    @Modifying
    @Query("DELETE FROM tag_assignments WHERE tag_id = :tagId AND media_type = :mediaType AND media_id = :mediaId")
    fun deleteOne(tagId: Long, mediaType: String, mediaId: Long)

    @Modifying
    @Query("DELETE FROM tag_assignments WHERE tag_id = :tagId")
    fun deleteByTagId(tagId: Long)
}
