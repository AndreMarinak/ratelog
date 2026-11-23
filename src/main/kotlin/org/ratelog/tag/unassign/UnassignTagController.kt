package org.ratelog.tag.unassign

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.tag.Tag
import org.ratelog.tag.titlePath
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class UnassignTagController(
    private val handler: UnassignTagHandler,
) {
    /**
     * The x on a tag chip. Sent from a title page (with `tmdbId`, goes back there)
     * or from the tag's own page (without it, goes back to the tag).
     */
    @PostMapping("/tags/{id}/unassign")
    fun unassign(
        @CurrentUser user: User,
        @PathVariable id: Long,
        @RequestParam mediaType: String,
        @RequestParam mediaId: Long,
        @RequestParam(required = false) tmdbId: Int?,
        redirectAttributes: RedirectAttributes,
    ): String {
        val type = MediaType.entries.find { it.name == mediaType }
        if (type == null) {
            redirectAttributes.addFlashAttribute("error", "tags.error.title.not.found")
            return "redirect:/tags/$id"
        }
        handler.handle(UnassignTagCommand(user.id!!, Tag.Id(id), type, mediaId))
            .onLeft { redirectAttributes.addFlashAttribute("error", "tags.error.not.found") }
        return "redirect:" + if (tmdbId != null) titlePath(type, tmdbId) else "/tags/$id"
    }
}
