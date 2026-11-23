package org.ratelog.customlist.official

import org.ratelog.customlist.CustomList
import org.springframework.stereotype.Repository

@Repository
class OfficialListRepositoryImpl(
    private val dao: OfficialListDAO,
) : OfficialListRepository {

    override fun isOfficial(listId: CustomList.Id): Boolean = dao.exists(listId.value)

    override fun officialAmong(listIds: Collection<CustomList.Id>): Set<CustomList.Id> {
        if (listIds.isEmpty()) return emptySet()
        return dao.findIn(listIds.map { it.value }.distinct()).map(CustomList::Id).toSet()
    }

    override fun mark(listId: CustomList.Id, atEpochMs: Long): Boolean = dao.insert(listId.value, atEpochMs) > 0

    override fun unmark(listId: CustomList.Id): Boolean = dao.delete(listId.value) > 0
}
