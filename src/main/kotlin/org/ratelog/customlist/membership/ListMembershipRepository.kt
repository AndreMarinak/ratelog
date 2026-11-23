package org.ratelog.customlist.membership

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.user.User

/** One of the viewer's own lists that contains a title, with the title's place in it. */
data class TitleInList(
    val listId: CustomList.Id,
    val name: String,
    val itemId: CustomListItem.Id,
    /** 1-based place in the whole list (position, then id): the rank the list page shows. Null on a plain list. */
    val rank: Int?,
)

/** Which of a user's lists contain a title. Used by the add-to-list sheet, its toggle and the title API. */
interface ListMembershipRepository {
    /**
     * The user's own lists that contain this title (local id: movies.id / tv.id), newest list first.
     * One query, whatever the number of lists.
     */
    fun findListsContaining(userId: User.Id, mediaType: MediaType, mediaId: Long): List<TitleInList>
}
