package org.ratelog.tag.replace

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.MediaType
import org.ratelog.tag.TagRepository
import org.ratelog.tag.assign.AssignTagsCommand
import org.ratelog.tag.assign.AssignTagsError
import org.ratelog.tag.assign.AssignTagsHandler
import org.ratelog.tag.assign.AssignedTags
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface ReplaceTitleTagsError {
    data object InvalidName : ReplaceTitleTagsError
    data object TitleNotFound : ReplaceTitleTagsError
    data object TagLimitExceeded : ReplaceTitleTagsError
}

/** Makes a title's tags exactly [names] (creating missing tags). An empty list clears them. */
data class ReplaceTitleTagsCommand(
    val userId: User.Id,
    val mediaType: MediaType,
    val mediaId: Long,
    val names: List<String>,
)

@Service
class ReplaceTitleTagsHandler(
    private val tagRepository: TagRepository,
    private val assignTagsHandler: AssignTagsHandler,
) {
    @Transactional
    fun handle(command: ReplaceTitleTagsCommand): Either<ReplaceTitleTagsError, AssignedTags> = either {
        val assigned = assignTagsHandler
            .handle(AssignTagsCommand(command.userId, command.mediaType, command.mediaId, command.names))
            .mapLeft {
                when (it) {
                    AssignTagsError.InvalidName -> ReplaceTitleTagsError.InvalidName
                    AssignTagsError.TitleNotFound -> ReplaceTitleTagsError.TitleNotFound
                    AssignTagsError.TagLimitExceeded -> ReplaceTitleTagsError.TagLimitExceeded
                }
            }
            .bind()

        val keep = assigned.tags.map { it.id }.toSet()
        tagRepository.findForTitle(command.userId, command.mediaType, command.mediaId)
            .filter { it.id !in keep }
            .forEach { tagRepository.unassign(it.id!!, command.mediaType, command.mediaId) }

        assigned
    }
}
