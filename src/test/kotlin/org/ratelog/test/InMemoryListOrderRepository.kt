package org.ratelog.test

import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.order.ListOptionsRepository
import org.ratelog.customlist.order.ListOrderRepository
import java.util.concurrent.ConcurrentHashMap

/** Writes positions into the [lists] fake, like `custom_list_items.position`. Counts locks and written rows for tests. */
class InMemoryListOrderRepository(
    private val lists: InMemoryCustomListRepository,
) : ListOrderRepository {
    val locked = mutableListOf<CustomList.Id>()
    var rowsWritten = 0
        private set

    override fun lock(listId: CustomList.Id) {
        locked += listId
    }

    override fun renumber(listId: CustomList.Id, order: List<CustomListItem.Id>): Int {
        var changed = 0
        order.forEachIndexed { index, itemId ->
            val item = lists.findItemById(itemId) ?: return@forEachIndexed
            if (item.listId != listId || item.position == index + 1) return@forEachIndexed
            lists.addItem(item.copy(position = index + 1))
            changed++
        }
        rowsWritten += changed
        return changed
    }
}

/** List options in memory. With [lists], a deleted list's row no longer counts (`ON DELETE CASCADE`, V1121). */
class InMemoryListOptionsRepository(
    private val lists: InMemoryCustomListRepository? = null,
) : ListOptionsRepository {
    private val numbered = ConcurrentHashMap<CustomList.Id, Boolean>()

    override fun isNumbered(listId: CustomList.Id): Boolean = if (exists(listId)) numbered[listId] ?: true else true

    override fun plainAmong(listIds: Collection<CustomList.Id>): Set<CustomList.Id> = listIds.filterNot(::isNumbered).toSet()

    override fun setNumbered(listId: CustomList.Id, numbered: Boolean) {
        if (!exists(listId)) error("custom_list_options.list_id references a missing list ${listId.value}")
        this.numbered[listId] = numbered
    }

    /** Lists that have a row. */
    fun rows(): Map<CustomList.Id, Boolean> = numbered.filterKeys(::exists)

    private fun exists(listId: CustomList.Id) = lists == null || lists.findById(listId) != null
}
