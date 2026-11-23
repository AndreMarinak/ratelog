package org.ratelog.apitoken.create

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.apitoken.ApiToken
import org.ratelog.apitoken.ApiTokenRepository
import org.ratelog.apitoken.ApiTokens
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface CreateApiTokenError {
    data object InvalidName : CreateApiTokenError
    data object TokenLimitExceeded : CreateApiTokenError
}

data class CreateApiTokenCommand(
    val userId: User.Id,
    val name: String,
)

/** The plain token is only ever available here, right after creation. */
data class CreatedApiToken(
    val token: ApiToken,
    val plainToken: String,
)

@Service
class CreateApiTokenHandler(
    private val apiTokenRepository: ApiTokenRepository,
) {
    @Transactional
    fun handle(command: CreateApiTokenCommand): Either<CreateApiTokenError, CreatedApiToken> = either {
        val name = command.name.trim()
        ensure(name.length in 1..MAX_NAME_LENGTH) { CreateApiTokenError.InvalidName }
        ensure(apiTokenRepository.findByUserId(command.userId).size < MAX_TOKENS_PER_USER) {
            CreateApiTokenError.TokenLimitExceeded
        }

        val plain = ApiTokens.generate()
        val saved = apiTokenRepository.save(
            ApiToken(
                id = null,
                userId = command.userId,
                name = name,
                tokenHash = ApiTokens.hash(plain),
                tokenPrefix = ApiTokens.displayPrefix(plain),
                createdAtEpochMs = System.currentTimeMillis(),
                lastUsedAtEpochMs = null,
            )
        )
        CreatedApiToken(saved, plain)
    }

    companion object {
        const val MAX_NAME_LENGTH = 100
        const val MAX_TOKENS_PER_USER = 20
    }
}
