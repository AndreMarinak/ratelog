package org.ratelog.customlist.official.web

import org.ratelog.annotations.CurrentUser
import org.ratelog.browse.discover.DiscoverController
import org.ratelog.customlist.official.browse.GetOfficialListsHandler
import org.ratelog.user.User
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.thymeleaf.context.LazyContextVariable

/*
 * The "Official lists" row on the Discover landing, right after "Popular now" (fragments/discover-rows.html).
 * Added without touching DiscoverController. The value is lazy: Thymeleaf only loads it when the landing renders the
 * row, so the poster grid, its "Load more" fragments and the many /discover/row fetches cost no extra queries.
 */
@ControllerAdvice(assignableTypes = [DiscoverController::class])
class DiscoverOfficialListsAdvice(
    private val handler: GetOfficialListsHandler,
) {
    @ModelAttribute
    fun addOfficialListsRow(@CurrentUser user: User?, model: Model) {
        val viewerId = user?.id ?: return
        model.addAttribute(MODEL_KEY, object : LazyContextVariable<OfficialListsRowView?>() {
            override fun loadValue(): OfficialListsRowView? = OfficialListsRowView.of(handler.handle(viewerId), id = "discover-official-lists")
        })
    }

    companion object {
        const val MODEL_KEY = "officialListsRow"
    }
}
