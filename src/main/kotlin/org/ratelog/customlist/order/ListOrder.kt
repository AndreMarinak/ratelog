package org.ratelog.customlist.order

import org.ratelog.customlist.CustomListItem

/*
 * List order (fork, roadmap item 16): the owner reorders a list on its page (drag, "move to #", up / down) or through
 * the API, and titles can be inserted at a place instead of only appended. The order is upstream's
 * custom_list_items.position; every change rewrites it as 1..n, so the stored positions and the ranks shown agree.
 */
object ListOrder {

    /**
     * List order: position, then id. Ties (and gaps) come from before reordering existed; this is the order the
     * list page counts ranks in (ListProgress), so a renumbering never changes what anyone sees.
     */
    val COMPARATOR: Comparator<CustomListItem> =
        compareBy<CustomListItem> { it.position }.thenBy { it.id?.value ?: Long.MAX_VALUE }

    fun ids(items: Collection<CustomListItem>): List<CustomListItem.Id> = items.sortedWith(COMPARATOR).map { it.id!! }

    /** [order] with [itemId] moved to the 1-based [position], clamped to the list (0 or less = first, past the end = last). */
    fun move(order: List<CustomListItem.Id>, itemId: CustomListItem.Id, position: Int): List<CustomListItem.Id> {
        require(itemId in order) { "item ${itemId.value} is not in this order" }
        val rest = order.filter { it != itemId }.toMutableList()
        rest.add((position - 1).coerceIn(0, rest.size), itemId)
        return rest
    }

    /** [leading] first, in that order, then every other item in its current order. */
    fun withLeading(order: List<CustomListItem.Id>, leading: List<CustomListItem.Id>): List<CustomListItem.Id> {
        val first = leading.toSet()
        return leading + order.filter { it !in first }
    }

    /** 1-based place of each item in [order]. */
    fun places(order: List<CustomListItem.Id>): Map<CustomListItem.Id, Int> =
        order.withIndex().associate { (index, id) -> id to index + 1 }
}
