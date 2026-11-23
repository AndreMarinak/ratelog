package org.ratelog.tag.update

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

sealed interface UpdateTagError {
    data object TagNotFound : UpdateTagError
    data object NotOwner : UpdateTagError
    data object InvalidName : UpdateTagError
    data object InvalidColor : UpdateTagError
    data object NameTaken : UpdateTagError
}

/** `null` leaves a field unchanged; a blank or "none" color removes the color. */
data class UpdateTagCommand(
    val userId: User.Id,
    val tagId: Tag.Id,
    val name: String? = null,
    val color: String? = null,
)

@Service
class UpdateTagHandler(
    private val tagRepository: TagRepository,
) {
    @Transactional
    fun handle(command: UpdateTagCommand): Either<UpdateTagError, Tag> = either {
        val tag = tagRepository.findById(command.tagId) ?: raise(UpdateTagError.TagNotFound)
        ensure(tag.userId == command.userId) { UpdateTagError.NotOwner }

        val name = command.name?.let { TagName.parse(it).mapLeft { UpdateTagError.InvalidName }.bind() } ?: tag.name
        val color = if (command.color == null) tag.color
        else TagColor.parse(command.color).mapLeft { UpdateTagError.InvalidColor }.bind()

        val clash = tagRepository.findByUserIdAndName(command.userId, name)
        ensure(clash == null || clash.id == tag.id) { UpdateTagError.NameTaken }

        tagRepository.save(tag.copy(name = name, color = color))
    }
}
