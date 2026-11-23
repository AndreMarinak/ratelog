package org.ratelog.customlist.official

import org.ratelog.customlist.CustomList

/*
 * Official lists (fork): admin-curated lists every account sees on the Lists tab, the Discover landing and the API,
 * each viewer tracking their own progress. Stored in `official_lists` (V1120), apart from upstream's custom_lists.
 * Official implies public: [SetListOfficialHandler] makes a list public when marking it and
 * [OfficialListsStayPublicRepository] keeps it public while it stays official.
 */
interface OfficialListRepository {
    fun isOfficial(listId: CustomList.Id): Boolean

    /** Which of these lists are official, in one query. */
    fun officialAmong(listIds: Collection<CustomList.Id>): Set<CustomList.Id>

    /** True if the list was not official before. */
    fun mark(listId: CustomList.Id, atEpochMs: Long): Boolean

    /** True if the list was official before. */
    fun unmark(listId: CustomList.Id): Boolean
}
