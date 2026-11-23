package org.ratelog.customlist.official

import org.ratelog.customlist.CustomList

/*
 * Official lists (fork): admin-curated lists every account sees on the Lists tab, the Discover landing and the API,
 * each viewer tracking their own progress. Stored in `official_lists` (V1120), apart from upstream's custom_lists.
 * The mark and the list's visibility are independent (2026-11-23): only **public** official lists are shown to
 * everyone; a private one is hidden (a draft only its owner sees) until it is made public again.
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
