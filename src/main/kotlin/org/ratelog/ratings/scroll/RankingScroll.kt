package org.ratelog.ratings.scroll

import org.ratelog.system.PageLimits
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Fork (user request 2026-11-23: "the ratings page only natively loads 10 the default but can we just have it load
 * more as u scroll?"): the Ratings rankings (`/movies`, `/tvshows`) show [PAGE_SIZE] titles and load the next
 * [PAGE_SIZE] as you scroll near the end (static/ranking-scroll.js). docs/decisions/ratings.md "Ratings load as you scroll".
 *
 * - `limit`: how many the page shows from the top (default one page). After scrolling, the script puts the loaded
 *   count in the address (`?limit=60`, history.replaceState, never a new entry), so a reload or Back shows them again.
 * - `offset` + `partial=1`: only the next cards and the footer, for the script to append.
 * - One title more than shown is fetched, so the footer knows whether there is more ([RankingPage]).
 */
object RankingScroll {
    const val PAGE_SIZE = 30

    /** Offsets past this are clamped (Postgres takes any, but nobody has rated this many). */
    const val MAX_OFFSET = 100_000

    /** What to show: from `offset` (default 0), `limit` titles (default one page, at most [PageLimits.MAX]). */
    fun window(offset: Int?, limit: Int?): Window =
        Window(offset = (offset ?: 0).coerceIn(0, MAX_OFFSET), limit = PageLimits.clamp(limit ?: PAGE_SIZE))

    data class Window(val offset: Int, val limit: Int) {
        /** One more than shown, to tell whether there is a next page. */
        val fetch: Int get() = limit + 1
    }

    /**
     * The footer for a page whose query (of [Window.fetch] rows) returned `fetched` titles. `filters` are the view's
     * query parameters in the form's order (category, tag, ratingCategory, name); blank ones are left out.
     */
    fun page(path: String, window: Window, fetched: Int, filters: List<Pair<String, Any?>>): RankingPage =
        RankingPage(
            path = path,
            offset = window.offset,
            count = fetched.coerceAtMost(window.limit),
            hasMore = fetched > window.limit,
            filters = filters.mapNotNull { (name, value) -> value?.toString()?.takeIf { it.isNotBlank() }?.let { name to it } },
        )
}

/** The footer under a ranking page: a "Show more" link while there is more, else "That's all [shown]". */
data class RankingPage(
    val path: String,
    /** Titles above this page. */
    val offset: Int,
    /** Titles on this page. */
    val count: Int,
    val hasMore: Boolean,
    val filters: List<Pair<String, String>>,
) {
    /** Titles shown once this page is on screen. */
    val shown: Int get() = offset + count

    /** The 1-based place of a card on the page (`iterStat.count`) in the whole list: its anchor `rank-pos-N`. */
    fun position(countOnPage: Int): Int = offset + countOnPage

    /**
     * "Show more" without JS: the same view with one more page, opened at the first new card. (The script uses
     * [nextHref] instead and adds no history entry.)
     */
    val moreHref: String get() = href(listOf("limit" to (shown + RankingScroll.PAGE_SIZE).toString())) + "#rank-pos-${shown + 1}"

    /** The next page's cards and footer only. */
    val nextHref: String get() = href(listOf("offset" to shown.toString(), "limit" to RankingScroll.PAGE_SIZE.toString(), "partial" to "1"))

    /** The address once this page is shown: the view, plus `limit` when more than the first page is loaded. */
    val addressHref: String get() = href(if (shown > RankingScroll.PAGE_SIZE) listOf("limit" to shown.toString()) else emptyList())

    private fun href(extra: List<Pair<String, String>>): String {
        val query = (filters + extra).joinToString("&") { (name, value) -> name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) }
        return if (query.isEmpty()) path else "$path?$query"
    }
}
