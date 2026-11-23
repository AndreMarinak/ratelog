package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.suggest.ListTitle
import org.ratelog.customlist.suggest.ListTitlesRepository
import java.util.concurrent.atomic.AtomicInteger

/**
 * ListSuggestDAO's query on top of [InMemoryCustomListRepository]: the list's items in list order (position, then id),
 * by TMDB id. Items whose local id isn't in [tmdbIds] use the local id as the TMDB id, so tests can add items by TMDB id.
 */
class InMemoryListTitlesRepository(
    private val lists: InMemoryCustomListRepository,
    val tmdbIds: MutableMap<Pair<MediaType, Long>, Int> = mutableMapOf(),
) : ListTitlesRepository {
    val queries = AtomicInteger()

    override fun findTitles(listId: CustomList.Id): List<ListTitle> {
        queries.incrementAndGet()
        val list = lists.findById(listId) ?: return emptyList()
        return list.items
            .sortedWith(compareBy<CustomListItem> { it.position }.thenBy { it.id!!.value })
            .map { ListTitle(it.id!!.value, it.mediaType, tmdbIds[it.mediaType to it.mediaId] ?: it.mediaId.toInt(), it.position) }
    }
}
