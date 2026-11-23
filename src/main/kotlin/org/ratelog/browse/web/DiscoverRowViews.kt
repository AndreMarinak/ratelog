package org.ratelog.browse.web

import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.discover.DiscoverLanding
import org.ratelog.browse.discover.DiscoverRowSlot
import org.ratelog.browse.discover.DiscoverRowSpec
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import java.util.Locale

/** One row of `templates/fragments/discover-rows.html` (home.css row look). */
data class DiscoverRowView(
    /** HTML id, e.g. `discover-row-horror`. */
    val id: String,
    val title: String,
    /** The row's filtered grid, carrying the "hide seen" choice. */
    val moreHref: String,
    /** Where discover.js fetches a pending row from (`/discover/row?...`); null once loaded. */
    val srcHref: String?,
    val tiles: List<BrowseTileView>,
) {
    val pending: Boolean get() = srcHref != null
}

/** The "Hide titles I've seen" switch: a plain link to the same view with the setting flipped (page 1). */
data class SeenToggleView(
    val on: Boolean,
    val href: String,
)

@Component
class DiscoverRowViews(
    private val messages: MessageSource,
    private val barViews: BrowseBarViews,
) {
    fun rows(landing: DiscoverLanding, locale: Locale = LocaleContextHolder.getLocale()): List<DiscoverRowView> =
        landing.rows.map { row(it, locale) }

    fun row(slot: DiscoverRowSlot, locale: Locale = LocaleContextHolder.getLocale()): DiscoverRowView {
        val spec = slot.spec
        return DiscoverRowView(
            id = "discover-row-${spec.id}",
            title = title(spec, locale),
            moreHref = spec.filters.href(),
            srcHref = if (slot.pending) srcHref(spec) else null,
            tiles = slot.tiles?.let { barViews.tiles(it, locale) }.orEmpty(),
        )
    }

    fun seenToggle(filters: BrowseFilters) = SeenToggleView(
        on = filters.hideSeen,
        href = filters.copy(hideSeen = !filters.hideSeen).href(),
    )

    private fun title(spec: DiscoverRowSpec, locale: Locale): String {
        val key = spec.genre?.let { "genre.${it.tmdbId}" } ?: "discover.row.popular"
        return messages.getMessage(key, null, key, locale) ?: key
    }

    companion object {
        const val ROW_PATH = "/discover/row"

        fun srcHref(spec: DiscoverRowSpec): String = BrowseFilters.link(
            ROW_PATH,
            buildList {
                add("type" to BrowseFilters.typeParam(spec.filters.type))
                add("row" to spec.id)
                if (!spec.filters.hideSeen) add("hideSeen" to "0")
            },
        )
    }
}
