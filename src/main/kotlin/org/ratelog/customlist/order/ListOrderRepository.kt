package org.ratelog.customlist.order

import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem

/** Writes a list's order (custom_list_items.position). Reads go through CustomListRepository. */
interface ListOrderRepository {
    /**
     * Locks the list until the transaction ends, so two changes to one list's order (two tabs, a script and the page)
     * run one after the other instead of both renumbering from the same old order. Call it before reading the items.
     */
    fun lock(listId: CustomList.Id)

    /**
     * Gives these items of the list the positions 1..n in this order, in one statement. Rows already in place are
     * not written; ids that aren't the list's are ignored. Returns how many rows changed.
     */
    fun renumber(listId: CustomList.Id, order: List<CustomListItem.Id>): Int
}

/** Per-list settings (custom_list_options, V1121). A list without a row uses the defaults. */
interface ListOptionsRepository {
    /** Numbered (ranks shown) unless the owner made it a plain list. */
    fun isNumbered(listId: CustomList.Id): Boolean

    /** Which of these lists are plain (not numbered), in one query. */
    fun plainAmong(listIds: Collection<CustomList.Id>): Set<CustomList.Id>

    fun setNumbered(listId: CustomList.Id, numbered: Boolean)
}
