package org.ratelog.customlist.official.web

import org.ratelog.customlist.CustomList
import org.ratelog.customlist.ListSummary
import org.ratelog.customlist.browse.ListCardView
import org.ratelog.customlist.browse.toCard
import org.ratelog.customlist.official.browse.OfficialListProgress

/**
 * The "Official lists" row of list cards (templates/fragments/official-lists.html), on the Lists tab and the
 * Discover landing. Cards are the home "Your lists" collage cards with the viewer's "9/25 seen" and the badge.
 */
data class OfficialListsRowView(
    /** At most [ROW_LIMIT]; "See all" opens every one. */
    val cards: List<ListCardView>,
    val total: Int,
    /** The section's HTML id. */
    val id: String,
    /** Discover's row opens the same Movies / TV subset (`/lists/official?type=tv`). */
    val seeAllHref: String = SEE_ALL_HREF,
) {
    companion object {
        const val ROW_LIMIT = 20
        const val SEE_ALL_HREF = "/lists/official"

        /** Null when there are no official lists: the row is left out instead of showing an empty state. */
        fun of(lists: List<OfficialListProgress>, id: String, seeAllHref: String = SEE_ALL_HREF): OfficialListsRowView? =
            of(lists.map { it.list }, lists.associate { it.list.id to it.seen }, id, seeAllHref)

        fun of(lists: List<ListSummary>, seen: Map<CustomList.Id, Int>, id: String, seeAllHref: String = SEE_ALL_HREF): OfficialListsRowView? {
            if (lists.isEmpty()) return null
            return OfficialListsRowView(
                cards = lists.take(ROW_LIMIT).map { it.toOfficialCard(seen[it.id] ?: 0) },
                total = lists.size,
                id = id,
                seeAllHref = seeAllHref,
            )
        }
    }
}

/** Official cards never show the owner: the list is presented as the site's, Letterboxd-style. */
fun ListSummary.toOfficialCard(seen: Int): ListCardView = toCard(showOwner = false, seenCount = seen).copy(isOfficial = true)
