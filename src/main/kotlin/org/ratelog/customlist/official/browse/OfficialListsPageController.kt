package org.ratelog.customlist.official.browse

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.official.web.toOfficialCard
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** `/lists/official`: every official list as a grid with the viewer's progress (the rows' "See all"). */
@Controller
class OfficialListsPageController(
    private val handler: GetOfficialListsHandler,
) {
    @GetMapping("/lists/official")
    fun officialLists(@CurrentUser user: User, model: Model): String {
        model.addAttribute("officialCards", handler.handle(user.id!!).map { it.list.toOfficialCard(it.seen) })
        return "lists-official"
    }
}
