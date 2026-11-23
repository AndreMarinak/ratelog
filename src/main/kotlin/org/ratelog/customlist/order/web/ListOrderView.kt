package org.ratelog.customlist.order.web

import org.ratelog.customlist.order.sort.ListSort

/** What templates/fragments/list-order.html renders on /list/{id}: the Sort menu, "Edit order" and reorder mode. */
data class ListOrderView(
    val listId: Long,
    /** Ranks on the posters. Plain lists (false) show none; their order still counts and can be edited. */
    val numbered: Boolean,
    /** The owner, on a list with at least two titles. Admins don't reorder other people's lists. */
    val canReorder: Boolean,
    /** Reorder mode (`?reorder=1`): the whole list in list order as editable rows; tabs, Sort and tick-off are hidden. */
    val reordering: Boolean,
    val sort: ListSort,
    val sortLinks: List<ListSortLink>,
    val total: Int,
    /** "Edit order": reorder mode. */
    val reorderHref: String = "/list/$listId?reorder=1",
    /** "Done": the list page. */
    val doneHref: String = "/list/$listId",
) {
    val plain: Boolean get() = !numbered

    /** Sorted by the viewer (anything but list order). */
    val sorted: Boolean get() = sort != ListSort.LIST

    /** Classes on the page's #listRegion: `is-plain-list` hides the rank badges, `is-reordering` the tabs. */
    val regionClass: String?
        get() = listOfNotNull(if (plain) "is-plain-list" else null, if (reordering) "is-reordering" else null)
            .joinToString(" ").ifEmpty { null }

    /** POST target of an item's reorder form (MoveListItemController). */
    fun moveHref(itemId: Long): String = "/list/$listId/item/$itemId/move"

    companion object {
        /**
         * @param show the current tab's `show` value (null = All), kept by every sort link.
         * @param tick tick-off mode, kept by every sort link.
         */
        fun of(
            listId: Long,
            numbered: Boolean,
            isOwner: Boolean,
            reorderRequested: Boolean,
            sort: ListSort,
            total: Int,
            show: String? = null,
            tick: Boolean = false,
        ): ListOrderView {
            val canReorder = isOwner && total >= 2
            val reordering = canReorder && reorderRequested
            val shown = if (reordering) ListSort.LIST else sort
            return ListOrderView(
                listId = listId,
                numbered = numbered,
                canReorder = canReorder,
                reordering = reordering,
                sort = shown,
                sortLinks = ListSort.entries.map { option ->
                    ListSortLink(
                        labelKey = option.labelKey,
                        href = href("/list/$listId", show, tick, option),
                        active = option == shown,
                    )
                },
                total = total,
            )
        }

        /** `/list/7?show=unseen&mark=1&sort=title`; list order adds no `sort`. */
        fun href(base: String, show: String?, tick: Boolean, sort: ListSort): String {
            val params = listOfNotNull(show?.let { "show=$it" }, if (tick) "mark=1" else null, sort.param?.let { "sort=$it" })
            return if (params.isEmpty()) base else base + "?" + params.joinToString("&")
        }

        /** Adds `sort=` to a list page link (the tabs, tick-off's "Mark" / "Done"), so they keep the viewer's sort. */
        fun withSort(href: String, sort: ListSort): String {
            val param = sort.param ?: return href
            if (Regex("[?&]sort=").containsMatchIn(href)) return href
            return href + (if ('?' in href) "&" else "?") + "sort=$param"
        }
    }
}

data class ListSortLink(
    val labelKey: String,
    val href: String,
    val active: Boolean,
)
