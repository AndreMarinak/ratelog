package org.ratelog.customlist.progress.web

import org.ratelog.MediaType
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.customlist.progress.ListProgress
import org.ratelog.customlist.progress.ListProgressEntry
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.TileActionsView
import org.ratelog.watchlist.seen.TitleRelease
import java.time.LocalDate
import java.util.Locale

/* What templates/fragments/list-progress.html renders on /list/{id}. Built from a [ListProgress]. */

data class ListProgressView(
    val listId: Long,
    val total: Int,
    val seen: Int,
    /** 0-100, for the bar. */
    val percent: Int,
    val filter: ListFilter,
    /** `unseen` / `seen`, or null for All: sent back by the bulk form so the redirect keeps the tab. */
    val filterParam: String?,
    val tabs: List<ListFilterTab>,
    /** Unseen titles with no status: the count on the bulk watchlist button (hidden at 0). */
    val addableCount: Int,
    /** Where the bulk watchlist form posts (AddUnseenToWatchlistController). */
    val addHref: String,
    /** Items shown under the current filter. */
    val visibleCount: Int,
    /** Shown instead of the grid when the filter leaves nothing (and the list itself isn't empty). */
    val emptyKey: String?,
    private val entries: Map<Long, ListProgressEntryView>,
    /** Tick-off mode (`?mark=1`): tapping a poster toggles Seen instead of opening the title. */
    val tick: Boolean = false,
    /** "Mark what you've seen": this view in tick-off mode. */
    val tickHref: String = "/list/$listId?mark=1",
    /** "Done": this view without tick-off mode. */
    val doneHref: String = "/list/$listId",
    /** The current view, sent as `back` by the poster forms so a plain post returns to the same tab and mode. */
    val selfHref: String = "/list/$listId",
) {
    /** The item's badges and rank, by item id (null for an item added after the progress was read). */
    fun entry(itemId: Long): ListProgressEntryView? = entries[itemId]

    /** Whether the item is in the current filter. Unknown items show only under All. */
    fun shows(itemId: Long): Boolean = entries[itemId]?.visible ?: (filter == ListFilter.ALL)
}

data class ListFilterTab(
    val labelKey: String,
    val count: Int,
    val href: String,
    val active: Boolean,
)

data class ListProgressEntryView(
    val rank: Int,
    val seen: Boolean,
    val visible: Boolean,
    val statusIcon: String?,
    val statusKey: String?,
    /** CSS modifier for the badge, e.g. `is-watched`. */
    val statusClass: String?,
    val scoreLabel: String?,
    /** The poster's Seen / Watchlist quick actions and Upcoming badge; null if the title's row is missing. */
    val actions: TileActionsView? = null,
)

/**
 * @param tick tick-off mode: the tabs, "Done" and the poster forms keep or drop `mark=1`.
 * @param releases stored TMDB id + release date per title, for the poster actions and the Upcoming badge.
 */
fun ListProgress.toView(
    filter: ListFilter,
    tick: Boolean = false,
    releases: Map<Pair<MediaType, Long>, TitleRelease> = emptyMap(),
    today: LocalDate = LocalDate.now(),
    locale: Locale = Locale.ENGLISH,
): ListProgressView {
    val base = "/list/${listId.value}"
    fun href(tab: ListFilter, mark: Boolean): String {
        val params = listOfNotNull(tab.param?.let { "show=$it" }, if (mark) "mark=1" else null)
        return if (params.isEmpty()) base else base + "?" + params.joinToString("&")
    }
    return ListProgressView(
        listId = listId.value,
        total = total,
        seen = seenCount,
        percent = if (total == 0) 0 else (seenCount * 100 / total),
        filter = filter,
        filterParam = filter.param,
        tabs = ListFilter.entries.map { tab ->
            ListFilterTab(
                labelKey = tab.labelKey,
                count = count(tab),
                href = href(tab, tick),
                active = tab == filter,
            )
        },
        addableCount = addableCount,
        addHref = "$base/watchlist-unseen",
        visibleCount = count(filter),
        emptyKey = when {
            total == 0 || count(filter) > 0 -> null
            filter == ListFilter.UNSEEN -> "list.progress.empty.unseen"
            filter == ListFilter.SEEN -> "list.progress.empty.seen"
            else -> null
        },
        entries = entries.associate { it.itemId.value to it.toView(filter, releases[it.mediaType to it.mediaId], today, locale) },
        tick = tick,
        tickHref = href(filter, mark = true),
        doneHref = href(filter, mark = false),
        selfHref = href(filter, tick),
    )
}

private fun ListProgressEntry.toView(filter: ListFilter, release: TitleRelease?, today: LocalDate, locale: Locale) = ListProgressEntryView(
    rank = rank,
    seen = seen,
    visible = filter.accepts(this),
    // The status badge, or the bookmark for a title only on the watchlist (fork, 2026-11-24).
    statusIcon = WatchStatus.effective(status, onWatchlist)?.icon,
    statusKey = WatchStatus.effective(status, onWatchlist)?.messageKey,
    statusClass = WatchStatus.effective(status, onWatchlist)?.let { "is-" + it.apiName.replace('_', '-') },
    scoreLabel = score?.let { String.format(Locale.ROOT, "%.1f", it) },
    actions = release?.let {
        TileActionsView.of(
            tmdbId = it.tmdbId,
            mediaType = mediaType,
            status = status,
            rated = rated,
            releaseDate = it.releaseDate,
            today = today,
            locale = locale,
            score = score,
            onWatchlist = onWatchlist,
        )
    },
)

/** "← Lists" on your own list, "← alice's profile" on someone else's. */
data class ListBackLink(
    val href: String,
    val labelKey: String,
    /** The owner's username for `list.back.profile`. */
    val labelArg: String?,
) {
    companion object {
        val LISTS = ListBackLink("/lists", "list.back.lists", null)

        fun profile(ownerId: Long, username: String) = ListBackLink("/profile/$ownerId", "list.back.profile", username)
    }
}
