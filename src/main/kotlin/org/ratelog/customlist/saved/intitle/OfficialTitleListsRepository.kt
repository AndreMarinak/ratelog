package org.ratelog.customlist.saved.intitle

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.user.User
import org.springframework.stereotype.Repository

/** A public official list that contains a title, with the title's place in it and whether the viewer saved the list. */
data class TitleInOfficialList(
    val listId: CustomList.Id,
    val name: String,
    val ownerId: User.Id,
    /** 1-based place in the list, as the list page numbers it. Null on a plain list. */
    val rank: Int?,
    /** When the viewer saved it, null when they haven't. */
    val savedAtEpochMs: Long?,
) {
    val saved: Boolean get() = savedAtEpochMs != null
}

interface OfficialTitleListsRepository {
    /** Every public official list containing this title (local id: movies.id / tv.id), in no particular order. One query. */
    fun findContaining(viewerId: User.Id, mediaType: MediaType, mediaId: Long): List<TitleInOfficialList>
}

@Repository
class OfficialTitleListsRepositoryImpl(
    private val dao: OfficialTitleListsDAO,
) : OfficialTitleListsRepository {
    override fun findContaining(viewerId: User.Id, mediaType: MediaType, mediaId: Long): List<TitleInOfficialList> =
        dao.findContaining(viewerId.value, mediaType.name, mediaId).map {
            TitleInOfficialList(CustomList.Id(it.listId), it.name, User.Id(it.ownerId), it.rank?.toInt(), it.savedAt)
        }
}

/**
 * The order the title page shows them in: saved lists first (newest save first), then ranked lists by rank (a #3 says
 * more than a #180), then plain lists by name. Kept here, in one place, so it is easy to change.
 */
object OfficialTitleListsOrder {
    val COMPARATOR: Comparator<TitleInOfficialList> =
        compareByDescending<TitleInOfficialList> { it.saved }
            .thenByDescending { it.savedAtEpochMs ?: 0L }
            .thenBy(nullsLast()) { it.rank }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.listId.value }

    fun sort(lists: List<TitleInOfficialList>): List<TitleInOfficialList> = lists.sortedWith(COMPARATOR)
}
