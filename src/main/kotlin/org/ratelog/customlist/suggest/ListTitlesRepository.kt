package org.ratelog.customlist.suggest

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList

/** A title in a list, with what suggestions need: its TMDB id and where it sits. */
data class ListTitle(
    val itemId: Long,
    val type: MediaType,
    val tmdbId: Int,
    val position: Int,
) {
    val key: TitleKey get() = TitleKey(type, tmdbId)
}

/** The list's titles by TMDB id, in one query (the list page's own handler reads one title at a time). */
interface ListTitlesRepository {
    /** Every title of the list in list order (position, then id). Items whose movie / show row is missing are left out. */
    fun findTitles(listId: CustomList.Id): List<ListTitle>
}
