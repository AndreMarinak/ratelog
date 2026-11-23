package org.ratelog.tag.detail

import org.ratelog.annotations.CurrentUser
import org.ratelog.tag.Tag
import org.ratelog.tag.titlePath
import org.ratelog.tag.toView
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

data class TaggedTitleResponse(
    val mediaType: String,
    val mediaId: Long,
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val score: Double?,
    val href: String,
)

@Controller
class TagDetailController(
    private val handler: GetTagDetailHandler,
) {
    @GetMapping("/tags/{id}")
    fun page(@CurrentUser user: User, @PathVariable id: Long, model: Model): String =
        handler.handle(GetTagDetail(user.id!!, Tag.Id(id), user.metadataLang)).fold(
            {
                // Other users' tags are private, so they look missing too.
                model.addAttribute("error", "tags.error.not.found")
                "tag-detail"
            },
            { detail ->
                model.addAttribute("tag", detail.tag.toView(detail.titles.size))
                model.addAttribute("titles", detail.titles.map { it.toResponse() })
                "tag-detail"
            }
        )

    private fun TaggedTitle.toResponse() = TaggedTitleResponse(
        mediaType = mediaType.name,
        mediaId = mediaId,
        tmdbId = tmdbId,
        title = title,
        year = year,
        posterPath = posterPath,
        score = score,
        href = titlePath(mediaType, tmdbId),
    )
}
