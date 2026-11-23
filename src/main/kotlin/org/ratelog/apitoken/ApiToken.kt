package org.ratelog.apitoken

import org.ratelog.user.User
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class ApiToken(
    val id: Id?,
    val userId: User.Id,
    val name: String,
    val tokenHash: String,
    val tokenPrefix: String,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long?,
) {
    data class Id(val value: Long)
}

interface ApiTokenRepository {
    fun findByUserId(userId: User.Id): List<ApiToken>
    fun findByTokenHash(tokenHash: String): ApiToken?
    fun findById(id: ApiToken.Id): ApiToken?
    fun save(token: ApiToken): ApiToken
    fun delete(id: ApiToken.Id)
    fun touch(id: ApiToken.Id, epochMs: Long)
}

/**
 * Tokens look like `rl_<43 url-safe chars>`. Only the SHA-256 hash is stored,
 * so a token is shown to the user exactly once, when it is created.
 */
object ApiTokens {
    const val PREFIX = "rl_"
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Short, non-secret hint shown in the UI so users can tell tokens apart. */
    fun displayPrefix(token: String): String = token.take(PREFIX.length + 6)
}
