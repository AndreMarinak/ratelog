package org.ratelog.customlist.progress.markall

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.progress.GetListProgressError
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Form POST from the tick-off bar (CSRF-protected, confirmed with `data-confirm`, sent in place by `inplace.js`).
 * Redirects back to the same tab, still in tick-off mode.
 */
@Controller
class MarkAllSeenController(
    private val handler: MarkAllSeenHandler,
) {
    @PostMapping("/list/{id}/seen-all")
    fun markAll(
        @PathVariable id: Long,
        @RequestParam("show", required = false) show: String?,
        @CurrentUser user: User,
        redirectAttributes: RedirectAttributes,
    ): String {
        // Re-parsed, never echoed raw, so the redirect can only be one of the list's own tabs.
        val back = "redirect:/list/$id?" + (ListFilter.parse(show).param?.let { "show=$it&" } ?: "") + "mark=1"
        handler.handle(MarkAllSeen(CustomList.Id(id), user.id!!)).fold(
            { error ->
                val message = when (error) {
                    GetListProgressError.ListNotFound -> "list.not.found"
                    GetListProgressError.NotAuthorized -> "list.not.authorized"
                }
                redirectAttributes.addFlashAttribute("error", message)
            },
            { result ->
                redirectAttributes.addFlashAttribute("listSeenMarked", result.marked)
                redirectAttributes.addFlashAttribute("listSeenNotOut", result.notOut)
            }
        )
        return back
    }
}
