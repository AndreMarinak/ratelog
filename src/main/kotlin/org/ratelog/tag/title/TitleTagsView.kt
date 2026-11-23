package org.ratelog.tag.title

import org.ratelog.MediaType
import org.ratelog.tag.TagView
import org.ratelog.tag.toView
import org.ratelog.user.UserDetailsService
import org.springframework.stereotype.Component

data class TitleTagsModel(
    val assigned: List<TagView>,
    val suggestions: List<TagView>,
)

/**
 * Read by `fragments/tags.html :: titleTags` as `${@titleTagsView.forTitle(...)}`, so the movie and TV
 * detail pages only need one include line and their controllers stay untouched.
 */
@Component("titleTagsView")
class TitleTagsView(
    private val handler: GetTitleTagsHandler,
) {
    fun forTitle(mediaType: String?, mediaId: Long?): TitleTagsModel {
        val user = UserDetailsService.getCurrentUser() ?: return EMPTY
        val type = MediaType.entries.find { it.name == mediaType } ?: return EMPTY
        if (mediaId == null) return EMPTY

        val tags = handler.handle(GetTitleTags(user.id!!, type, mediaId))
        return TitleTagsModel(tags.assigned.map { it.toView() }, tags.suggestions.map { it.toView() })
    }

    companion object {
        private val EMPTY = TitleTagsModel(emptyList(), emptyList())
    }
}
