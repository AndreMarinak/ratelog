package org.ratelog.tag.create

import org.ratelog.annotations.CurrentUser
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class CreateTagController(
    private val handler: CreateTagHandler,
) {
    @PostMapping("/tags")
    fun create(
        @CurrentUser user: User,
        @RequestParam name: String,
        @RequestParam(required = false) color: String?,
        redirectAttributes: RedirectAttributes,
    ): String {
        handler.handle(CreateTagCommand(user.id!!, name, color)).fold(
            { error ->
                val message = when (error) {
                    is CreateTagError.InvalidName -> "tags.error.invalid.name"
                    is CreateTagError.InvalidColor -> "tags.error.invalid.color"
                    is CreateTagError.AlreadyExists -> "tags.error.exists"
                    is CreateTagError.TagLimitExceeded -> "tags.error.limit"
                }
                redirectAttributes.addFlashAttribute("error", message)
            },
            { redirectAttributes.addFlashAttribute("success", "tags.created") }
        )
        return "redirect:/tags"
    }
}
