package org.ratelog.customlist.suggest.web

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.suggest.GetListSuggestions
import org.ratelog.customlist.suggest.GetListSuggestionsHandler
import org.ratelog.customlist.suggest.TitleKey
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

/**
 * `GET /list/{id}/suggestions[?skip=m862.t1396]`: the "Suggested for this list" row as an HTML fragment, which
 * `static/list-suggest.js` swaps in for the list page's skeleton once it nears the viewport (and fetches again for
 * "More suggestions", sending the titles it has shown in `skip`). The list page itself never waits on TMDB.
 * Never fails: someone else's list, TMDB errors and bugs answer an empty fragment, and the page drops the row.
 */
@Controller
class ListSuggestionsController(
    private val handler: GetListSuggestionsHandler,
    private val views: ListSuggestViews,
) {
    private val logger = LoggerFactory.getLogger(ListSuggestionsController::class.java)

    @GetMapping("/list/{id}/suggestions")
    fun row(
        @CurrentUser user: User,
        @PathVariable id: Long,
        @RequestParam("skip", required = false) skip: String?,
        model: Model,
    ): String {
        val view = if (id <= 0) null else try {
            handler.handle(GetListSuggestions(CustomList.Id(id), user.id!!, user.metadataLang, TitleKey.parseList(skip, MAX_SKIP)))
                .getOrNull()
                ?.let { views.row(it) }
        } catch (e: Exception) {
            // Never log e.message: TMDB errors can carry the request URL (and its api_key).
            logger.warn("List suggestions failed for list {}: {}", id, e.javaClass.simpleName)
            null
        }
        model.addAttribute("listSuggest", view)
        return FRAGMENT
    }

    companion object {
        const val FRAGMENT = "fragments/list-suggest :: row"

        /** More than ten batches of 20; older keys are dropped first. */
        const val MAX_SKIP = 300
    }
}
