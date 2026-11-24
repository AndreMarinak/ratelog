package org.ratelog.customlist.official.web

import org.ratelog.annotations.CurrentUser
import org.ratelog.browse.discover.DiscoverController
import org.ratelog.customlist.official.browse.GetOfficialListsHandler
import org.ratelog.user.User
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestParam
import org.thymeleaf.context.LazyContextVariable

/*
 * The "Official lists" row on the Discover landing, right after "Popular now" (fragments/discover-rows.html).
 * Added without touching DiscoverController. The value is lazy: Thymeleaf only loads it when the landing renders the
 * row, so the poster grid, its "Load more" fragments and the many /discover/row fetches cost no extra queries.
 * It follows Discover's Movies / TV mode (`type`, default movie; user, 2026-11-23): only official lists with at least
 * one title of that type, filtered in the lists query. None match = no row.
 */
@ControllerAdvice(assignableTypes = [DiscoverController::class])
class DiscoverOfficialListsAdvice(
    private val handler: GetOfficialListsHandler,
) {
    @ModelAttribute
    fun addOfficialListsRow(
        @CurrentUser user: User?,
        @RequestParam("type", required = false) type: String?,
        model: Model,
    ) {
        val viewerId = user?.id ?: return
        val mode = OfficialListsType.discoverMode(type)
        model.addAttribute(MODEL_KEY, object : LazyContextVariable<OfficialListsRowView?>() {
            override fun loadValue(): OfficialListsRowView? = OfficialListsRowView.of(
                handler.handle(viewerId, containing = mode),
                id = "discover-official-lists",
                seeAllHref = OfficialListsType.seeAllHref(mode),
            )
        })
    }

    companion object {
        const val MODEL_KEY = "officialListsRow"
    }
}
