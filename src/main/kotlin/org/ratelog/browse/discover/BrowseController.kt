package org.ratelog.browse.discover

import org.ratelog.WatchRegion
import org.ratelog.annotations.CurrentUser
import org.ratelog.browse.BrowseParamParser
import org.ratelog.browse.BrowseParams
import org.ratelog.browse.web.BrowseBarViews
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.LocalDate

/**
 * `/browse`: Movies / TV, the browse bar and a poster grid. Unknown or unusable parameters are ignored,
 * so any old or hand-edited link still renders. `partial=1` returns only the results fragment
 * (tiles + the next "Load more" link) for browse.js to append.
 */
@Controller
class BrowseController(
    private val handler: BrowseHandler,
    private val views: BrowseBarViews,
    private val clock: Clock = Clock.systemUTC(),
) {
    @GetMapping("/browse")
    fun browse(
        @CurrentUser user: User,
        params: BrowseParams,
        @RequestParam("partial", required = false) partial: String?,
        model: Model,
    ): String {
        val parsed = BrowseParamParser.parse(params, LocalDate.now(clock))
        val filters = parsed.filters

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

        if (partial == "1") return "browse :: results"

        model.addAttribute("browseBar", views.build(filters, views.providerOptions(filters.type, user.metadataLang)))
        model.addAttribute("isMovie", filters.isMovie)
        return "browse"
    }

    private fun errorKey(error: BrowseError) = when (error) {
        BrowseError.TmdbNotConfigured -> "browse.error.not.configured"
        BrowseError.TmdbKeyRejected -> "browse.error.bad.key"
        BrowseError.TmdbUnavailable -> "browse.error.unreachable"
    }
}
