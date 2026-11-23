package org.ratelog.tag.list

import org.ratelog.tag.Tag
import org.ratelog.tag.TagRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service

data class ListTags(val userId: User.Id)

data class TagSummary(val tag: Tag, val titleCount: Int)

@Service
class ListTagsHandler(
    private val tagRepository: TagRepository,
) {
    /** The user's tags sorted by name, each with how many titles carry it. */
    fun handle(query: ListTags): List<TagSummary> {
        val counts = tagRepository.countTitlesByUserId(query.userId)
        return tagRepository.findByUserId(query.userId).map { TagSummary(it, counts[it.id] ?: 0) }
    }
}
