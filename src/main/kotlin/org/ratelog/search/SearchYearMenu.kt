package org.ratelog.search

import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowseParamParser
import org.ratelog.browse.BrowseYear
import org.ratelog.browse.web.BarHidden
import org.ratelog.browse.web.BarMenu
import org.ratelog.browse.web.BarYearBox
import org.ratelog.browse.web.BrowseBarViews
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.util.Locale

/**
 * Fork (2026-11-24, user: "can the year under search allow you to type but also still [have the] same style of like 2020s
 * but can click exact year if you want"): the search page's Year menu is Discover's (BrowseBarViews.yearMenu: Any year,
 * This year, Last year, the decades, a decade's years) with its links pointing at `/` and a "Type a year" box on top.
 * docs/decisions/people-and-credits.md "Year menu".
 */
@Component
class SearchYearMenu(
    private val bar: BrowseBarViews,
    private val messages: MessageSource,
    /** UTC like BrowseBarViews', so "This year" and the years a box accepts agree. */
    private val clock: Clock = Clock.systemUTC(),
) {

    fun today(): LocalDate = LocalDate.now(clock)

    /**
     * @param mediaType the type filter as in the URL (`movie`, `tvshow` or null), kept by every link.
     * @param typedYear the raw `year` parameter, to show again with an error when it wasn't a year.
     */
    fun build(
        query: String,
        mediaType: String?,
        filter: SearchYearFilter,
        typedYear: String?,
        locale: Locale = LocaleContextHolder.getLocale(),
    ): BarMenu {
        val today = today()
        val invalid = SearchYearFilter.invalidYear(typedYear, today)
        val (decade, year) = when (filter) {
            SearchYearFilter.AnyYear -> null to null
            is SearchYearFilter.Exact -> null to BrowseYear.Of(filter.year)
            is SearchYearFilter.Decade -> filter.decade to null
        }
        val min = BrowseParamParser.FIRST_FILM_YEAR
        val max = BrowseParamParser.latestYear(today)
        val box = BarYearBox(
            action = "/",
            hidden = listOfNotNull(BarHidden("q", query), mediaType?.let { BarHidden("mediaType", it) }),
            value = invalid ?: (filter as? SearchYearFilter.Exact)?.year?.toString(),
            label = message("search.year.type", locale),
            placeholder = message("search.year.type.placeholder", locale),
            submitLabel = message("search.year.go", locale),
            min = min,
            max = max,
            error = message("search.year.invalid", locale, min.toString(), max.toString()),
            invalid = invalid != null,
        )
        return bar.yearMenu(decade, year, withUpcoming = false, yearBox = box, locale = locale) { d, y ->
            href(query, mediaType, when (y) {
                is BrowseYear.Of -> SearchYearFilter.Exact(y.year)
                else -> d?.let { SearchYearFilter.Decade(it) } ?: SearchYearFilter.AnyYear
            })
        }
    }

    private fun message(key: String, locale: Locale, vararg args: String): String =
        messages.getMessage(key, if (args.isEmpty()) null else arrayOf<Any>(*args), key, locale) ?: key

    companion object {
        /** A results page link: `/?q=...&mediaType=...&year=2023` (or `decade=2020s`); page 1 is implied. */
        fun href(query: String, mediaType: String?, filter: SearchYearFilter): String =
            BrowseFilters.link("/", listOfNotNull("q" to query, mediaType?.takeIf { it.isNotBlank() }?.let { "mediaType" to it }, filter.param))
    }
}
