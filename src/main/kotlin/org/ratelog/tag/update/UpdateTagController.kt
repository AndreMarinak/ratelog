package org.ratelog.tag.update

import org.ratelog.annotations.CurrentUser
import org.ratelog.tag.Tag
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class UpdateTagController(
    private val handler: UpdateTagHandler,
) {
    /** Rename and/or recolor. The form always sends both; color "none" removes it. */
    @PostMapping("/tags/{id}/edit")
    fun update(
        @CurrentUser user: User,
        @PathVariable id: Long,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) color: String?,
        redirectAttributes: RedirectAttributes,
    ): String {
        handler.handle(UpdateTagCommand(user.id!!, Tag.Id(id), name, color)).fold(
            { error ->
                val message = when (error) {
                    is UpdateTagError.TagNotFound, is UpdateTagError.NotOwner -> "tags.error.not.found"
                    is UpdateTagError.InvalidName -> "tags.error.invalid.name"
                    is UpdateTagError.InvalidColor -> "tags.error.invalid.color"
                    is UpdateTagError.NameTaken -> "tags.error.exists"
                }
                redirectAttributes.addFlashAttribute("error", message)
            },
            { redirectAttributes.addFlashAttribute("success", "tags.updated") }
        )
        return "redirect:/tags"
    }
}
