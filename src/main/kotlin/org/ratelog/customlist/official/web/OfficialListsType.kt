package org.ratelog.customlist.official.web

import org.ratelog.MediaType
import org.ratelog.browse.BrowseParamParser
import org.ratelog.browse.BrowseParams
import java.time.LocalDate

/**
 * Official lists by Discover's Movies / TV mode (user, 2026-11-23: TV mode shows "only lists with TV").
 * `type` takes Discover's values and aliases (`movie`, `tv`, `shows`...), parsed by [BrowseParamParser] so both agree.
 */
object OfficialListsType {

    /** The parsed `type`, or null when it is missing or unknown. */
    fun parse(raw: String?): MediaType? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // The date only matters for year filters, which aren't passed here.
        val parsed = BrowseParamParser.parse(BrowseParams(type = value), LocalDate.EPOCH)
        return if (parsed.invalid.isEmpty()) parsed.filters.type else null
    }

    /** Discover's mode: Movies unless `type` says TV, the same default as the page itself. */
    fun discoverMode(raw: String?): MediaType = parse(raw) ?: MediaType.movie

    /** "See all" from Discover opens the same subset on `/lists/official`. */
    fun seeAllHref(type: MediaType): String = OfficialListsRowView.SEE_ALL_HREF + "?type=" + param(type)

    fun param(type: MediaType): String = when (type) {
        MediaType.movie -> "movie"
        MediaType.tvshow -> "tv"
    }
}
