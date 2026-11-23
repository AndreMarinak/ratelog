package org.ratelog.test

import org.ratelog.customlist.CustomList
import org.ratelog.customlist.official.OfficialListRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Official marks in memory. With [lists], a mark whose list was deleted no longer counts, like the
 * `official_lists.list_id ... ON DELETE CASCADE` foreign key (V1120).
 */
class InMemoryOfficialListRepository(
    private val lists: InMemoryCustomListRepository? = null,
) : OfficialListRepository {
    private val marks = ConcurrentHashMap<CustomList.Id, Long>()

    override fun isOfficial(listId: CustomList.Id): Boolean = marks.containsKey(listId) && exists(listId)

    override fun officialAmong(listIds: Collection<CustomList.Id>): Set<CustomList.Id> = listIds.filter(::isOfficial).toSet()

    override fun mark(listId: CustomList.Id, atEpochMs: Long): Boolean {
        if (!exists(listId)) error("official_lists.list_id references a missing list ${listId.value}")
        return marks.putIfAbsent(listId, atEpochMs) == null
    }

    override fun unmark(listId: CustomList.Id): Boolean = marks.remove(listId) != null && exists(listId)

    /** Marks that still have their row (after the cascade). */
    fun markedIds(): Set<CustomList.Id> = marks.keys.filter(::exists).toSet()

    private fun exists(listId: CustomList.Id) = lists == null || lists.findById(listId) != null
}
