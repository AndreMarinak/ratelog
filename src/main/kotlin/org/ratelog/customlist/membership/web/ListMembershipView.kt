package org.ratelog.customlist.membership.web

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.membership.TitleInList

/**
 * The add-to-list sheet and its button on a title page (`templates/fragments/add-to-list-modal.html`): every one of the
 * viewer's lists, ticked when it has the title. Lists with the title come first; within each group the usual order
 * (newest list first) is kept. The sheet never reorders while it is open (list-membership.js re-sorts when it opens).
 */
data class ListMembershipView(
    /** `movie` / `tvshow`: the `mediaType` of POST /list/{id}/membership. */
    val mediaType: String,
    /** Local id (movies.id / tv.id). */
    val mediaId: Long,
    /** The title page, sent as `back` so a plain form post comes back here. */
    val back: String,
    val rows: List<ListMembershipRow>,
) {
    /** How many of the viewer's lists have the title. */
    val count: Int = rows.count { it.inList }
    val inAny: Boolean get() = count > 0

    companion object {
        fun of(type: MediaType, mediaId: Long, tmdbId: Int, lists: List<CustomList>, inLists: List<TitleInList>): ListMembershipView {
            val byList = inLists.associateBy { it.listId }
            val rows = lists
                .filter { it.id != null }
                .mapIndexed { index, list ->
                    val hit = byList[list.id]
                    ListMembershipRow(list.id!!.value, list.name.value, hit != null, hit?.rank, index)
                }
                .sortedByDescending { it.inList } // stable: each group keeps the usual order
            return ListMembershipView(
                mediaType = type.name,
                mediaId = mediaId,
                back = if (type == MediaType.tvshow) "/tv/$tmdbId" else "/movie/$tmdbId",
                rows = rows,
            )
        }
    }
}

data class ListMembershipRow(
    val listId: Long,
    val name: String,
    val inList: Boolean,
    /** The title's place in the list (as the list page numbers it), null when it isn't in it. */
    val rank: Int?,
    /** Place in the usual order (newest list first): the sheet sorts by (has the title, order) when it opens. */
    val order: Int,
)
