package org.ratelog.tag.title

import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service

data class GetTitleTags(
    val userId: User.Id,
    val mediaType: MediaType,
    val mediaId: Long,
)

data class TitleTags(
    /** Tags on this title, by name. */
    val assigned: List<Tag>,
    /** The user's other tags, offered as suggestions when adding one. */
    val suggestions: List<Tag>,
)

@Service
class GetTitleTagsHandler(
    private val tagRepository: TagRepository,
) {
    fun handle(query: GetTitleTags): TitleTags {
        val assigned = tagRepository.findForTitle(query.userId, query.mediaType, query.mediaId)
        val assignedIds = assigned.map { it.id }.toSet()
        val suggestions = tagRepository.findByUserId(query.userId).filter { it.id !in assignedIds }
        return TitleTags(assigned, suggestions)
    }
}
