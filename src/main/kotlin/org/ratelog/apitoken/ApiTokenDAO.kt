package org.ratelog.apitoken

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Table("api_tokens")
data class ApiTokenEntity(
    @Id val id: Long? = null,
    @Column("user_id") val userId: Long,
    val name: String,
    @Column("token_hash") val tokenHash: String,
    @Column("token_prefix") val tokenPrefix: String,
    @Column("created_at_epoch_ms") val createdAtEpochMs: Long,
    @Column("last_used_at_epoch_ms") val lastUsedAtEpochMs: Long?,
)

@Repository
interface ApiTokenDAO : CrudRepository<ApiTokenEntity, Long> {
    @Query("SELECT * FROM api_tokens WHERE user_id = :userId ORDER BY created_at_epoch_ms DESC")
    fun findByUserId(userId: Long): List<ApiTokenEntity>

    fun findByTokenHash(tokenHash: String): Optional<ApiTokenEntity>

    @Modifying
    @Query("UPDATE api_tokens SET last_used_at_epoch_ms = :epochMs WHERE id = :id")
    fun touch(id: Long, epochMs: Long)
}
