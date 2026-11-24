package org.ratelog.user.follow

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.Username
import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class SetUserFollow(
    val followerId: User.Id,
    val followedId: User.Id,
    val following: Boolean,
)

data class UserFollowState(
    val username: Username,
    val following: Boolean,
)

sealed interface SetUserFollowError {
    data object UserNotFound : SetUserFollowError
    data object CannotFollowYourself : SetUserFollowError
}

/**
 * Fork: Follow / Unfollow with an explicit target state (the Follow / Following buttons, 2026-11-23). Unlike upstream's
 * toggle (`/user/follow`), sending the state the button showed means a stale page or a double tap can't flip it the
 * wrong way: "Follow" on someone you already follow is a no-op.
 */
@Service
class SetUserFollowHandler(
    private val userRepository: UserRepository,
) {
    @Transactional
    fun handle(command: SetUserFollow): Either<SetUserFollowError, UserFollowState> = either {
        val followed = userRepository.findById(command.followedId) ?: raise(SetUserFollowError.UserNotFound)
        if (followed.id == command.followerId) raise(SetUserFollowError.CannotFollowYourself)

        if (userRepository.isFollowing(command.followerId, command.followedId) != command.following) {
            userRepository.toggleFollow(command.followerId, command.followedId)
        }
        UserFollowState(followed.username, command.following)
    }
}
