package org.ratelog.browse.discover

import org.ratelog.WatchRegion
import org.ratelog.annotations.CurrentUser
import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowseParamParser
import org.ratelog.browse.BrowseParams
import org.ratelog.browse.web.BrowseBarViews
import org.ratelog.browse.web.DiscoverRowViews
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.LocalDate

/**
 * `/discover` (formerly `/browse`): Movies / TV, the "Hide titles I've seen" switch and the browse bar, over either
 * the category rows (no filter chosen) or the poster grid (any filter, sort or page). Unknown or unusable parameters
 * are ignored, so any old or hand-edited link still renders. Seen titles are hidden unless `hideSeen=0`.
 *
 * - `partial=1` returns only the grid's results fragment (tiles + the next "Load more") for browse.js to append.
 * - `/discover/row?type=&row=` returns one category row for discover.js to swap in (empty when it has nothing).
 */
@Controller
class DiscoverController(
    private val handler: BrowseHandler,
    private val rowsHandler: GetDiscoverRowsHandler,
    private val views: BrowseBarViews,
    private val rowViews: DiscoverRowViews,
    private val clock: Clock = Clock.systemUTC(),
) {
    @GetMapping("/discover")
    fun discover(
        @CurrentUser user: User,
        params: BrowseParams,
        @RequestParam("partial", required = false) partial: String?,
        model: Model,
    ): String {
        val parsed = BrowseParamParser.parse(params, LocalDate.now(clock))
        val filters = parsed.filters
        val landing = filters.isUnfiltered && parsed.page == 1 && partial != "1"

        if (landing) {
            val result = rowsHandler.landing(rowsQuery(user, filters))
            model.addAttribute("rows", rowViews.rows(result))
            result.error?.let { error ->
                model.addAttribute("errorKey", errorKey(error))
                model.addAttribute("retryHref", filters.href())
            }
        } else {
            val result = BrowseQuery(
                userId = user.id!!,
                lang = user.metadataLang,
                region = WatchRegion.fromLang(user.metadataLang),
                filters = filters,
                page = parsed.page,
            ).let(handler::handle)

            result.fold(
                { error ->
                    model.addAttribute("tiles", emptyList<Any>())
                    model.addAttribute("errorKey", errorKey(error))
                    model.addAttribute("retryHref", filters.href(parsed.page))
                },
                { page ->
                    model.addAttribute("tiles", views.tiles(page.tiles))
                    model.addAttribute("nextHref", page.nextPage?.let { filters.href(it) })
                    model.addAttribute("ignoredNote", page.ignored.isNotEmpty())
                },
            )
            model.addAttribute("page", parsed.page)
            model.addAttribute("firstPageHref", if (parsed.page > 1) filters.href() else null)

            if (partial == "1") return "discover :: results"
        }

        model.addAttribute("landing", landing)
        model.addAttribute("seenToggle", rowViews.seenToggle(filters))
        // The rows are fixed orderings, so the landing has no Sort menu; picking a filter opens the grid, which does.
        model.addAttribute("browseBar", views.build(filters, views.providerOptions(filters.type, user.metadataLang), showSort = !landing))
        model.addAttribute("isMovie", filters.isMovie)
        return "discover"
    }

    @GetMapping(DiscoverRowViews.ROW_PATH)
    fun row(
        @CurrentUser user: User,
        params: BrowseParams,
        @RequestParam("row", required = false) row: String?,
        model: Model,
    ): String {
        val filters = BrowseParamParser.parse(BrowseParams(type = params.type, hideSeen = params.hideSeen), LocalDate.now(clock)).filters
        val slot = row?.let { DiscoverRows.find(filters.type, it, filters.hideSeen) }?.let { spec ->
            rowsHandler.row(rowsQuery(user, filters), spec).getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { DiscoverRowSlot(spec, it) }
        }
        model.addAttribute("row", slot?.let { rowViews.row(it) })
        return "fragments/discover-rows :: lazyRow"
    }

    private fun rowsQuery(user: User, filters: BrowseFilters) = GetDiscoverRows(
        userId = user.id!!,
        lang = user.metadataLang,
        region = WatchRegion.fromLang(user.metadataLang),
        type = filters.type,
        hideSeen = filters.hideSeen,
    )

    private fun errorKey(error: BrowseError) = when (error) {
        BrowseError.TmdbNotConfigured -> "browse.error.not.configured"
        BrowseError.TmdbKeyRejected -> "discover.error.bad.key"
        BrowseError.TmdbUnavailable -> "browse.error.unreachable"
    }
}
