package org.ratelog.tag.unassign

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface UnassignTagError {
    data object TagNotFound : UnassignTagError
    data object NotOwner : UnassignTagError
}

/** Removes one tag from one title. Removing a tag the title doesn't have is a no-op. */
data class UnassignTagCommand(
    val userId: User.Id,
    val tagId: Tag.Id,
    val mediaType: MediaType,
    val mediaId: Long,
)

@Service
class UnassignTagHandler(
    private val tagRepository: TagRepository,
) {
    @Transactional
    fun handle(command: UnassignTagCommand): Either<UnassignTagError, Unit> = either {
        val tag = tagRepository.findById(command.tagId) ?: raise(UnassignTagError.TagNotFound)
        ensure(tag.userId == command.userId) { UnassignTagError.NotOwner }

        tagRepository.unassign(command.tagId, command.mediaType, command.mediaId)
    }
}
