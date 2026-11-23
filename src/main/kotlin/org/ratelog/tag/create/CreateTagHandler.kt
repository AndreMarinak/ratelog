package org.ratelog.tag.create

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.tag.Tag
import org.ratelog.tag.TagColor
import org.ratelog.tag.TagName
import org.ratelog.tag.TagRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface CreateTagError {
    data object InvalidName : CreateTagError
    data object InvalidColor : CreateTagError
    data class AlreadyExists(val existing: Tag) : CreateTagError
    data object TagLimitExceeded : CreateTagError
}

data class CreateTagCommand(
    val userId: User.Id,
    val name: String,
    val color: String? = null,
)

@Service
class CreateTagHandler(
    private val tagRepository: TagRepository,
) {
    @Transactional
    fun handle(command: CreateTagCommand): Either<CreateTagError, Tag> = either {
        val name = TagName.parse(command.name).mapLeft { CreateTagError.InvalidName }.bind()
        val color = TagColor.parse(command.color).mapLeft { CreateTagError.InvalidColor }.bind()

        tagRepository.findByUserIdAndName(command.userId, name)?.let { raise(CreateTagError.AlreadyExists(it)) }
        ensure(tagRepository.findByUserId(command.userId).size < TagRepository.MAX_TAGS_PER_USER) {
            CreateTagError.TagLimitExceeded
        }

        tagRepository.save(
            Tag(
                id = null,
                userId = command.userId,
                name = name,
                color = color,
                createdAtEpochMs = System.currentTimeMillis(),
            )
        )
    }
}
