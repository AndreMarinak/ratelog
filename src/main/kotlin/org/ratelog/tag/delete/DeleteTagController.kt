package org.ratelog.tag.delete

import org.ratelog.annotations.CurrentUser
import org.ratelog.tag.Tag
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class DeleteTagController(
    private val handler: DeleteTagHandler,
) {
    @PostMapping("/tags/{id}/delete")
    fun delete(
        @CurrentUser user: User,
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes,
    ): String {
        handler.handle(DeleteTagCommand(user.id!!, Tag.Id(id))).fold(
            { redirectAttributes.addFlashAttribute("error", "tags.error.not.found") },
            { redirectAttributes.addFlashAttribute("success", "tags.deleted") }
        )
        return "redirect:/tags"
    }
}
