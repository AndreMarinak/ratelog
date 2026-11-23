package org.ratelog.customlist.progress.addunseen

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

/** Form POST from the list page (CSRF-protected, confirmed with `data-confirm`). Redirects back to the same tab. */
@Controller
class AddUnseenToWatchlistController(
    private val handler: AddUnseenToWatchlistHandler,
) {
    @PostMapping("/list/{id}/watchlist-unseen")
    fun addUnseen(
        @PathVariable id: Long,
        @RequestParam("show", required = false) show: String?,
        @CurrentUser user: User,
        redirectAttributes: RedirectAttributes,
    ): String {
        // Re-parsed, never echoed raw, so the redirect can only be one of the list's own tabs.
        val back = "redirect:/list/$id" + (ListFilter.parse(show).param?.let { "?show=$it" } ?: "")
        handler.handle(AddUnseenToWatchlist(CustomList.Id(id), user.id!!)).fold(
            { error ->
                val message = when (error) {
                    GetListProgressError.ListNotFound -> "list.not.found"
                    GetListProgressError.NotAuthorized -> "list.not.authorized"
                }
                redirectAttributes.addFlashAttribute("error", message)
            },
            { added ->
                if (added > 0) redirectAttributes.addFlashAttribute("listWatchlistAdded", added)
                else redirectAttributes.addFlashAttribute("success", "list.progress.add.none")
            }
        )
        return back
    }
}
