package org.ratelog.customlist.saved.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.customlist.browse.ListCardView
import org.ratelog.customlist.official.web.toOfficialCard
import org.ratelog.customlist.saved.browse.GetSavedListsHandler
import org.ratelog.home.dashboard.GetHomeDashboardHandler
import org.ratelog.home.dashboard.HomeDashboardView
import org.ratelog.home.dashboard.HomeRowKind
import org.ratelog.home.dashboard.HomeRowView
import org.ratelog.home.dashboard.moreHref
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

/**
 * The home "Your lists" row also shows the official lists you saved, after your own (fork, saved lists 2026-11-24),
 * with the Official badge and your progress. Added after the controller ran, so the home slice stays untouched: the
 * row gets the cards appended (still at most the row's limit), or is added at its place (the last row) when you have
 * no lists of your own. One query when nothing is saved.
 */
class SavedListsHomeInterceptor(
    private val handler: GetSavedListsHandler,
    private val currentUser: () -> User?,
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(SavedListsHomeInterceptor::class.java)

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        if (modelAndView == null || modelAndView.viewName != "search") return
        val home = modelAndView.model["home"] as? HomeDashboardView ?: return
        val viewerId = currentUser()?.id ?: return
        val saved = try {
            this.handler.handle(viewerId, includeOwn = false)
        } catch (e: Exception) {
            logger.warn("Saved lists unavailable on home: {}", e.javaClass.simpleName)
            return
        }
        if (saved.isEmpty()) return
        modelAndView.addObject("home", withSaved(home, saved.map { it.list.toOfficialCard(it.seen) }))
    }

    companion object {
        const val ROW_ID = "row-your-lists"

        fun withSaved(home: HomeDashboardView, cards: List<ListCardView>): HomeDashboardView {
            if (cards.isEmpty()) return home
            // Home cards have no Save toggle (the row is a plain shelf); the badge and progress say what they are.
            val shelf = cards.map { it.copy(saved = null) }
            val existing = home.rows.indexOfFirst { it.isLists }
            val rows = if (existing >= 0) {
                home.rows.toMutableList().also { rows ->
                    val row = rows[existing]
                    rows[existing] = row.copy(lists = (row.lists + shelf).take(GetHomeDashboardHandler.ROW_LIMIT))
                }
            } else {
                // YOUR_LISTS is the last kind, so its row goes at the end.
                home.rows + HomeRowView(
                    id = ROW_ID,
                    titleKey = HomeRowKind.YOUR_LISTS.titleKey,
                    moreHref = HomeRowKind.YOUR_LISTS.moreHref,
                    moreKey = "home.row.see.all",
                    isLists = true,
                    tiles = emptyList(),
                    lists = shelf.take(GetHomeDashboardHandler.ROW_LIMIT),
                )
            }
            return home.copy(rows = rows, showWelcome = false)
        }
    }
}
