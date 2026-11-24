package org.ratelog.customlist.saved

import org.ratelog.customlist.CustomList
import org.ratelog.user.User
import org.springframework.stereotype.Repository

@Repository
class SavedListRepositoryImpl(
    private val dao: SavedListDAO,
) : SavedListRepository {

    override fun findByUser(userId: User.Id): List<SavedList> =
        dao.findByUser(userId.value).map { SavedList(CustomList.Id(it.listId), it.savedAtEpochMs) }

    override fun isSaved(userId: User.Id, listId: CustomList.Id): Boolean = dao.exists(userId.value, listId.value)

    override fun save(userId: User.Id, listId: CustomList.Id, atEpochMs: Long): Boolean =
        dao.insert(userId.value, listId.value, atEpochMs) > 0

    override fun unsave(userId: User.Id, listId: CustomList.Id): Boolean = dao.delete(userId.value, listId.value) > 0
}
