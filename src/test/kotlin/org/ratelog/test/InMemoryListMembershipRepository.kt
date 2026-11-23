package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.membership.ListMembershipRepository
import org.ratelog.customlist.membership.TitleInList
import org.ratelog.user.User
import java.util.concurrent.atomic.AtomicInteger

/** Membership read from [InMemoryCustomListRepository], with ListMembershipDAO's rules (own lists, rank by position then id). */
class InMemoryListMembershipRepository(
    private val lists: InMemoryCustomListRepository,
) : ListMembershipRepository {

    /** Calls, to check a page or a toggle reads membership once. */
    val queries = AtomicInteger()

    /** Set to make the next reads fail, like a database error. */
    var failure: RuntimeException? = null

    override fun findListsContaining(userId: User.Id, mediaType: MediaType, mediaId: Long): List<TitleInList> {
        queries.incrementAndGet()
        failure?.let { throw it }
        return lists.findByUserId(userId).mapNotNull { list ->
            val ordered = list.items.sortedWith(compareBy<CustomListItem> { it.position }.thenBy { it.id!!.value })
            val index = ordered.indexOfFirst { it.mediaType == mediaType && it.mediaId == mediaId }
            if (index < 0) null else TitleInList(list.id!!, list.name.value, ordered[index].id!!, index + 1)
        }
    }
}
