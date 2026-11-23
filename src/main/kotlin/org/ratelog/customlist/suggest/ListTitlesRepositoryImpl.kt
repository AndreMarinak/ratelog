package org.ratelog.customlist.suggest

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.springframework.stereotype.Component

@Component
class ListTitlesRepositoryImpl(private val dao: ListSuggestDAO) : ListTitlesRepository {
    override fun findTitles(listId: CustomList.Id): List<ListTitle> =
        dao.findTitles(listId.value).mapNotNull { row ->
            val type = MediaType.entries.find { it.name == row.mediaType } ?: return@mapNotNull null
            val tmdbId = row.tmdbId?.takeIf { it > 0 } ?: return@mapNotNull null
            ListTitle(row.itemId, type, tmdbId, row.position)
        }
}
