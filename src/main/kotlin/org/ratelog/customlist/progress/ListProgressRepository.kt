package org.ratelog.customlist.progress

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.user.User

/** The viewer's progress on list titles. Every read is one query (per media type), never one per title. */
interface ListProgressRepository {
    /** The user's marks on these titles (local ids). Titles with no status and no rating are left out. */
    fun findMarks(userId: User.Id, mediaType: MediaType, mediaIds: Collection<Long>): Map<Long, ViewerMark>

    /** How many titles of each list the user has seen. Lists with none seen are left out. */
    fun countSeen(userId: User.Id, listIds: Collection<CustomList.Id>): Map<CustomList.Id, Int>

    /**
     * Sets Want to watch on these items of the list, skipping any title that already has a status or is rated.
     * @return how many titles were added.
     */
    fun addToWatchlist(userId: User.Id, listId: CustomList.Id, itemIds: Collection<CustomListItem.Id>, nowEpochMs: Long): Int
}
