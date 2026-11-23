package org.ratelog.tag.assign

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.tag.titlePath
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class AssignTagController(
    private val handler: AssignTagsHandler,
) {
    /** The inline "add a tag" form on movie and TV pages. A new name creates the tag. */
    @PostMapping("/tags/assign")
    fun assign(
        @CurrentUser user: User,
        @RequestParam mediaType: String,
        @RequestParam mediaId: Long,
        @RequestParam tmdbId: Int,
        @RequestParam name: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        val type = MediaType.entries.find { it.name == mediaType }
        if (type == null) {
            redirectAttributes.addFlashAttribute("error", "tags.error.title.not.found")
            return "redirect:/tags"
        }
        handler.handle(AssignTagsCommand(user.id!!, type, mediaId, listOf(name))).onLeft { error ->
            val message = when (error) {
                is AssignTagsError.InvalidName -> "tags.error.invalid.name"
                is AssignTagsError.TitleNotFound -> "tags.error.title.not.found"
                is AssignTagsError.TagLimitExceeded -> "tags.error.limit"
            }
            redirectAttributes.addFlashAttribute("error", message)
        }
        return "redirect:" + titlePath(type, tmdbId)
    }
}
