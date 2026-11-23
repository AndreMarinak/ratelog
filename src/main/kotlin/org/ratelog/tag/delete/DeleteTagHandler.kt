package org.ratelog.tag.delete

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.tag.Tag
import org.ratelog.tag.TagRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface DeleteTagError {
    data object TagNotFound : DeleteTagError
    data object NotOwner : DeleteTagError
}

data class DeleteTagCommand(
    val userId: User.Id,
    val tagId: Tag.Id,
)

@Service
class DeleteTagHandler(
    private val tagRepository: TagRepository,
) {
    @Transactional
    fun handle(command: DeleteTagCommand): Either<DeleteTagError, Unit> = either {
        val tag = tagRepository.findById(command.tagId) ?: raise(DeleteTagError.TagNotFound)
        ensure(tag.userId == command.userId) { DeleteTagError.NotOwner }

        tagRepository.delete(command.tagId)
    }
}
