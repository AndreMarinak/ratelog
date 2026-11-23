package org.ratelog.test

import org.ratelog.apitoken.ApiToken
import org.ratelog.apitoken.ApiTokenRepository
import org.ratelog.user.User
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class InMemoryApiTokenRepository : ApiTokenRepository {
    private val store = ConcurrentHashMap<ApiToken.Id, ApiToken>()
    private val idGenerator = AtomicLong(1)

    override fun findByUserId(userId: User.Id): List<ApiToken> =
        store.values.filter { it.userId == userId }.sortedByDescending { it.createdAtEpochMs }

    override fun findByTokenHash(tokenHash: String): ApiToken? = store.values.find { it.tokenHash == tokenHash }

    override fun findById(id: ApiToken.Id): ApiToken? = store[id]

    override fun save(token: ApiToken): ApiToken {
        val saved = if (token.id == null) token.copy(id = ApiToken.Id(idGenerator.getAndIncrement())) else token
        store[saved.id!!] = saved
        return saved
    }

    override fun delete(id: ApiToken.Id) {
        store.remove(id)
    }

    override fun touch(id: ApiToken.Id, epochMs: Long) {
        store[id]?.let { store[id] = it.copy(lastUsedAtEpochMs = epochMs) }
    }
}
