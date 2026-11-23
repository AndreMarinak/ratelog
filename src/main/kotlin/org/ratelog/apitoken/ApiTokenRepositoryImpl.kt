package org.ratelog.apitoken

import org.ratelog.user.User
import org.springframework.stereotype.Repository
import kotlin.jvm.optionals.getOrNull

@Repository
class ApiTokenRepositoryImpl(
    private val dao: ApiTokenDAO,
) : ApiTokenRepository {

    override fun findByUserId(userId: User.Id): List<ApiToken> =
        dao.findByUserId(userId.value).map { it.toDomain() }

    override fun findByTokenHash(tokenHash: String): ApiToken? =
        dao.findByTokenHash(tokenHash).getOrNull()?.toDomain()

    override fun findById(id: ApiToken.Id): ApiToken? =
        dao.findById(id.value).getOrNull()?.toDomain()

    override fun save(token: ApiToken): ApiToken =
        dao.save(token.toEntity()).toDomain()

    override fun delete(id: ApiToken.Id) =
        dao.deleteById(id.value)

    override fun touch(id: ApiToken.Id, epochMs: Long) =
        dao.touch(id.value, epochMs)

    private fun ApiTokenEntity.toDomain() = ApiToken(
        id = id?.let(ApiToken::Id),
        userId = User.Id(userId),
        name = name,
        tokenHash = tokenHash,
        tokenPrefix = tokenPrefix,
        createdAtEpochMs = createdAtEpochMs,
        lastUsedAtEpochMs = lastUsedAtEpochMs,
    )

    private fun ApiToken.toEntity() = ApiTokenEntity(
        id = id?.value,
        userId = userId.value,
        name = name,
        tokenHash = tokenHash,
        tokenPrefix = tokenPrefix,
        createdAtEpochMs = createdAtEpochMs,
        lastUsedAtEpochMs = lastUsedAtEpochMs,
    )
}
