package org.ratelog.customlist.membership

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.user.User
import org.springframework.stereotype.Repository

@Repository
class ListMembershipRepositoryImpl(
    private val dao: ListMembershipDAO,
) : ListMembershipRepository {

    override fun findListsContaining(userId: User.Id, mediaType: MediaType, mediaId: Long): List<TitleInList> =
        dao.findListsContaining(userId.value, mediaType.name, mediaId).map {
            TitleInList(CustomList.Id(it.listId), it.name, CustomListItem.Id(it.itemId), it.rank?.toInt())
        }
}
