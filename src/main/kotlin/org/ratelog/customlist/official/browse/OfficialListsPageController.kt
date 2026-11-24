package org.ratelog.customlist.official.browse

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.official.web.OfficialListsType
import org.ratelog.customlist.official.web.toOfficialCard
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * `/lists/official`: every official list as a grid with the viewer's progress (the rows' "See all"). Discover's row
 * links here with its mode (`?type=movie` / `?type=tv`): only lists with that type of title, as in the row.
 */
@Controller
class OfficialListsPageController(
    private val handler: GetOfficialListsHandler,
) {
    @GetMapping("/lists/official")
    fun officialLists(
        @CurrentUser user: User,
        @RequestParam("type", required = false) type: String?,
        model: Model,
    ): String {
        val lists = handler.handle(user.id!!, containing = OfficialListsType.parse(type))
        model.addAttribute("officialCards", lists.map { it.list.toOfficialCard(it.seen, saved = it.saved, baseOrder = it.baseOrder) })
        return "lists-official"
    }
}
