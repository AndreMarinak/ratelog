package org.ratelog.test

import org.ratelog.customlist.CustomList
import org.ratelog.customlist.saved.SavedList
import org.ratelog.customlist.saved.SavedListRepository
import org.ratelog.user.User
import java.util.concurrent.ConcurrentHashMap

/**
 * Saved lists in memory, with `saved_lists`' rules (V1142): one row per (user, list), the first save time kept. With
 * [lists], a row whose list was deleted no longer counts, like the `ON DELETE CASCADE` foreign key.
 */
class InMemorySavedListRepository(
    private val lists: InMemoryCustomListRepository? = null,
) : SavedListRepository {
    private val rows = ConcurrentHashMap<Pair<User.Id, CustomList.Id>, Long>()

    override fun findByUser(userId: User.Id): List<SavedList> =
        rows.entries.filter { it.key.first == userId && exists(it.key.second) }
            .map { SavedList(it.key.second, it.value) }
            .sortedWith(compareByDescending<SavedList> { it.savedAtEpochMs }.thenByDescending { it.listId.value })

    override fun isSaved(userId: User.Id, listId: CustomList.Id): Boolean = rows.containsKey(userId to listId) && exists(listId)

    override fun save(userId: User.Id, listId: CustomList.Id, atEpochMs: Long): Boolean {
        if (!exists(listId)) error("saved_lists.list_id references a missing list ${listId.value}")
        return rows.putIfAbsent(userId to listId, atEpochMs) == null
    }

    override fun unsave(userId: User.Id, listId: CustomList.Id): Boolean = rows.remove(userId to listId) != null && exists(listId)

    private fun exists(listId: CustomList.Id) = lists == null || lists.findById(listId) != null
}
