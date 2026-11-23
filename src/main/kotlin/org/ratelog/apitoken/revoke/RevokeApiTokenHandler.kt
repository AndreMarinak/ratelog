package org.ratelog.apitoken.revoke

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.apitoken.ApiToken
import org.ratelog.apitoken.ApiTokenRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface RevokeApiTokenError {
    data object NotFound : RevokeApiTokenError
}

data class RevokeApiTokenCommand(
    val userId: User.Id,
    val tokenId: ApiToken.Id,
)

@Service
class RevokeApiTokenHandler(
    private val apiTokenRepository: ApiTokenRepository,
) {
    @Transactional
    fun handle(command: RevokeApiTokenCommand): Either<RevokeApiTokenError, Unit> = either {
        val token = apiTokenRepository.findById(command.tokenId)
        // Someone else's token is reported as "not found" so ids can't be probed.
        ensure(token != null && token.userId == command.userId) { RevokeApiTokenError.NotFound }
        apiTokenRepository.delete(command.tokenId)
    }
}
