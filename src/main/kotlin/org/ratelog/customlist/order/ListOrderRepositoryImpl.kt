package org.ratelog.customlist.order

import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.springframework.stereotype.Repository

@Repository
class ListOrderRepositoryImpl(
    private val dao: ListOrderDAO,
) : ListOrderRepository {

    override fun lock(listId: CustomList.Id) {
        dao.lockList(listId.value)
    }

    override fun renumber(listId: CustomList.Id, order: List<CustomListItem.Id>): Int {
        if (order.isEmpty()) return 0
        return dao.renumber(listId.value, order.joinToString(",", "[", "]") { it.value.toString() })
    }
}

@Repository
class ListOptionsRepositoryImpl(
    private val dao: ListOptionsDAO,
) : ListOptionsRepository {

    override fun isNumbered(listId: CustomList.Id): Boolean = dao.findNumbered(listId.value).firstOrNull() ?: true

    override fun plainAmong(listIds: Collection<CustomList.Id>): Set<CustomList.Id> {
        if (listIds.isEmpty()) return emptySet()
        return dao.findPlainIn(listIds.map { it.value }.distinct()).map(CustomList::Id).toSet()
    }

    override fun setNumbered(listId: CustomList.Id, numbered: Boolean) {
        dao.upsertNumbered(listId.value, numbered)
    }
}
