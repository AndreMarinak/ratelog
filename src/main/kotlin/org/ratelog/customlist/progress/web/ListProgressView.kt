package org.ratelog.customlist.progress.web

import org.ratelog.customlist.progress.ListFilter
import org.ratelog.customlist.progress.ListProgress
import org.ratelog.customlist.progress.ListProgressEntry
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
)

fun ListProgress.toView(filter: ListFilter): ListProgressView {
    val base = "/list/${listId.value}"
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
                href = tab.param?.let { "$base?show=$it" } ?: base,
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
        entries = entries.associate { it.itemId.value to it.toView(filter) },
    )
}

private fun ListProgressEntry.toView(filter: ListFilter) = ListProgressEntryView(
    rank = rank,
    seen = seen,
    visible = filter.accepts(this),
    statusIcon = status?.icon,
    statusKey = status?.messageKey,
    statusClass = status?.let { "is-" + it.apiName.replace('_', '-') },
    scoreLabel = score?.let { String.format(Locale.ROOT, "%.1f", it) },
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
