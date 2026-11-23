package org.ratelog.apitoken

import arrow.core.getOrElse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.apitoken.create.CreateApiTokenCommand
import org.ratelog.apitoken.create.CreateApiTokenError
import org.ratelog.apitoken.create.CreateApiTokenHandler
import org.ratelog.apitoken.revoke.RevokeApiTokenCommand
import org.ratelog.apitoken.revoke.RevokeApiTokenError
import org.ratelog.apitoken.revoke.RevokeApiTokenHandler
import org.ratelog.test.InMemoryApiTokenRepository
import org.ratelog.user.User

class ApiTokenHandlersTest {

    private val repository = InMemoryApiTokenRepository()
    private val create = CreateApiTokenHandler(repository)
    private val revoke = RevokeApiTokenHandler(repository)

    @Test
    fun `creates a token and stores only its hash`() {
        val created = create.handle(CreateApiTokenCommand(User.Id(1), "  Claude  ")).getOrElse { error("unexpected $it") }

        assertTrue(created.plainToken.startsWith(ApiTokens.PREFIX))
        assertEquals("Claude", created.token.name)
        assertNotEquals(created.plainToken, created.token.tokenHash)
        assertEquals(ApiTokens.hash(created.plainToken), created.token.tokenHash)
        assertEquals(created.token, repository.findByTokenHash(ApiTokens.hash(created.plainToken)))
        assertTrue(created.plainToken.startsWith(created.token.tokenPrefix))
    }

    @Test
    fun `generated tokens are unique`() {
        val tokens = (1..50).map { ApiTokens.generate() }.toSet()
        assertEquals(50, tokens.size)
    }

    @Test
    fun `rejects blank or too long names`() {
        assertEquals(CreateApiTokenError.InvalidName, create.handle(CreateApiTokenCommand(User.Id(1), "   ")).leftOrNull())
        assertEquals(CreateApiTokenError.InvalidName, create.handle(CreateApiTokenCommand(User.Id(1), "x".repeat(101))).leftOrNull())
    }

    @Test
    fun `enforces the per-user token limit`() {
        repeat(CreateApiTokenHandler.MAX_TOKENS_PER_USER) { create.handle(CreateApiTokenCommand(User.Id(1), "t$it")) }
        assertEquals(CreateApiTokenError.TokenLimitExceeded, create.handle(CreateApiTokenCommand(User.Id(1), "one more")).leftOrNull())
        assertTrue(create.handle(CreateApiTokenCommand(User.Id(2), "other user")).isRight())
    }

    @Test
    fun `revokes own token`() {
        val created = create.handle(CreateApiTokenCommand(User.Id(1), "Claude")).getOrElse { error("unexpected") }
        assertTrue(revoke.handle(RevokeApiTokenCommand(User.Id(1), created.token.id!!)).isRight())
        assertNull(repository.findById(created.token.id))
    }

    @Test
    fun `cannot revoke someone else's token`() {
        val created = create.handle(CreateApiTokenCommand(User.Id(1), "Claude")).getOrElse { error("unexpected") }
        assertEquals(RevokeApiTokenError.NotFound, revoke.handle(RevokeApiTokenCommand(User.Id(2), created.token.id!!)).leftOrNull())
        assertEquals(created.token, repository.findById(created.token.id))
    }
}
