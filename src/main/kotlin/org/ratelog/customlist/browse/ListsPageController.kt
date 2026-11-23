package org.ratelog.customlist.browse

import org.ratelog.annotations.CurrentUser
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/** The Lists tab (fork): your lists, then public lists from people you follow, then everyone else's. */
@Controller
class ListsPageController(
    private val handler: GetListsPageHandler,
) {
    @GetMapping("/lists")
    fun listsPage(
        @CurrentUser user: User,
        @RequestParam("limit", required = false) limit: Int?,
        model: Model,
    ): String {
        val page = handler.handle(GetListsPage(user.id!!, limit ?: GetListsPageHandler.PAGE_SIZE))

        model.addAttribute("myLists", page.mine.map { it.toCard(showOwner = false) })
        model.addAttribute("followedLists", page.followed.map { it.toCard(showOwner = true) })
        model.addAttribute("otherLists", page.others.map { it.toCard(showOwner = true) })
        model.addAttribute("moreHref", "/lists?limit=${page.limit + GetListsPageHandler.PAGE_SIZE}#more-lists".takeIf { page.hasMoreOthers })
        return "lists"
    }
}
