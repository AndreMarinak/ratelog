package org.ratelog.customlist.browse

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.official.web.OfficialListsRowView
import org.ratelog.customlist.progress.GetListProgressHandler
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/** The Lists tab (fork): official lists, your lists, then public lists from people you follow, then everyone else's. */
@Controller
class ListsPageController(
    private val handler: GetListsPageHandler,
    private val listProgressHandler: GetListProgressHandler,
) {
    @GetMapping("/lists")
    fun listsPage(
        @CurrentUser user: User,
        @RequestParam("limit", required = false) limit: Int?,
        model: Model,
    ): String {
        val page = handler.handle(GetListsPage(user.id!!, limit ?: GetListsPageHandler.PAGE_SIZE))

        // "9/25 seen" on every card: one query for all the lists on the page.
        val seen = listProgressHandler.seenCounts(user.id, (page.official + page.mine + page.followed + page.others).map { it.id }.distinct())

        // Official lists: a row of cards at the top (fragments/official-lists.html); null when there are none.
        model.addAttribute("officialLists", OfficialListsRowView.of(page.official, seen, id = "official-lists"))
        model.addAttribute("myLists", page.mine.map { it.toCard(showOwner = false, seenCount = seen[it.id]) })
        model.addAttribute("followedLists", page.followed.map { it.toCard(showOwner = true, seenCount = seen[it.id]) })
        model.addAttribute("otherLists", page.others.map { it.toCard(showOwner = true, seenCount = seen[it.id]) })
        model.addAttribute("moreHref", "/lists?limit=${page.limit + GetListsPageHandler.PAGE_SIZE}#more-lists".takeIf { page.hasMoreOthers })
        return "lists"
    }
}
