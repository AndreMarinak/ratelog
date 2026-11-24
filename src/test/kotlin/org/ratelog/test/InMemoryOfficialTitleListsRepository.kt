package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.order.ListOptionsRepository
import org.ratelog.customlist.saved.intitle.OfficialTitleListsRepository
import org.ratelog.customlist.saved.intitle.TitleInOfficialList
import org.ratelog.user.User
import java.util.concurrent.atomic.AtomicInteger

/**
 * OfficialTitleListsDAO's rules over the in-memory fakes: public official lists only, rank by position then id, no rank
 * on a plain list ([options] numbered = false), and the viewer's save time.
 */
class InMemoryOfficialTitleListsRepository(
    private val lists: InMemoryCustomListRepository,
    private val official: InMemoryOfficialListRepository,
    private val saved: InMemorySavedListRepository,
    private val options: ListOptionsRepository? = null,
) : OfficialTitleListsRepository {

    /** Calls, to check a page reads it once. */
    val queries = AtomicInteger()

    override fun findContaining(viewerId: User.Id, mediaType: MediaType, mediaId: Long): List<TitleInOfficialList> {
        queries.incrementAndGet()
        val savedAt = saved.findByUser(viewerId).associate { it.listId to it.savedAtEpochMs }
        return official.markedIds().mapNotNull { lists.findById(it) }
            .filter { it.isPublic }
            .mapNotNull { list ->
                val ordered = list.items.sortedWith(compareBy<CustomListItem> { it.position }.thenBy { it.id!!.value })
                val index = ordered.indexOfFirst { it.mediaType == mediaType && it.mediaId == mediaId }
                if (index < 0) return@mapNotNull null
                val numbered = options?.isNumbered(list.id!!) ?: true
                TitleInOfficialList(list.id!!, list.name.value, list.userId, if (numbered) index + 1 else null, savedAt[list.id])
            }
    }
}
