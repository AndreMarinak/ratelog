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
            of(
                lists.map { it.list },
                lists.associate { it.list.id to it.seen },
                id,
                seeAllHref,
                saved = lists.filter { it.saved }.map { it.list.id }.toSet(),
                baseOrder = lists.associate { it.list.id to it.baseOrder },
            )

        /** [saved]: the viewer's saved lists (their toggle reads "Saved"); [baseOrder]: each card's place before they moved up. */
        fun of(
            lists: List<ListSummary>,
            seen: Map<CustomList.Id, Int>,
            id: String,
            seeAllHref: String = SEE_ALL_HREF,
            saved: Set<CustomList.Id> = emptySet(),
            baseOrder: Map<CustomList.Id, Int> = emptyMap(),
        ): OfficialListsRowView? {
            if (lists.isEmpty()) return null
            return OfficialListsRowView(
                cards = lists.take(ROW_LIMIT).mapIndexed { index, it ->
                    it.toOfficialCard(seen[it.id] ?: 0, saved = it.id in saved, baseOrder = baseOrder[it.id] ?: index)
                },
                total = lists.size,
                id = id,
                seeAllHref = seeAllHref,
            )
        }
    }
}

/**
 * Official cards never show the owner: the list is presented as the site's, Letterboxd-style. Each has the viewer's
 * Save toggle (fork, saved lists 2026-11-24), except a hidden (private) one, which can't be saved.
 */
fun ListSummary.toOfficialCard(seen: Int, saved: Boolean = false, baseOrder: Int = 0): ListCardView =
    toCard(showOwner = false, seenCount = seen).copy(isOfficial = true, saved = saved.takeIf { isPublic }, baseOrder = baseOrder)
