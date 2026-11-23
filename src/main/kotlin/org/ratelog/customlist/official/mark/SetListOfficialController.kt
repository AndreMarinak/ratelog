package org.ratelog.customlist.official.mark

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.CustomList
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * The admin-only "Official" toggle on the list page (CSRF-protected form POST). Always returns to the list, with a
 * message that says whether everyone can see it (marking never changes the list's visibility).
 */
@Controller
class SetListOfficialController(
    private val handler: SetListOfficialHandler,
) {
    @PostMapping("/list/{id}/official")
    fun setOfficial(
        @PathVariable id: Long,
        @RequestParam("official") official: Boolean,
        @CurrentUser user: User,
        redirectAttributes: RedirectAttributes,
    ): String {
        handler.handle(SetListOfficial(CustomList.Id(id), user.id!!, official)).fold(
            { error ->
                val message = when (error) {
                    SetListOfficialError.ListNotFound -> "list.not.found"
                    SetListOfficialError.NotAdmin -> "list.official.error.forbidden"
                }
                redirectAttributes.addFlashAttribute("error", message)
            },
            { change ->
                val message = when {
                    change.official && change.isPublic -> "list.official.marked"
                    change.official -> "list.official.marked.hidden"
                    change.isPublic -> "list.official.unmarked"
                    else -> "list.official.unmarked.private"
                }
                redirectAttributes.addFlashAttribute("success", message)
            },
        )
        return "redirect:/list/$id"
    }
}
