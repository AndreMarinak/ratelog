package org.ratelog.customlist.official.browse

import org.ratelog.MediaType
import org.ratelog.customlist.ListSummary
import org.ratelog.customlist.ListSummaryRepository
import org.ratelog.customlist.progress.GetListProgressHandler
import org.ratelog.customlist.saved.SavedFirst
import org.ratelog.customlist.saved.SavedListRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** An official list with how many of its titles the viewer has seen. */
data class OfficialListProgress(
    val list: ListSummary,
    val seen: Int,
    /** The viewer saved it (fork, saved lists 2026-11-24): it comes first and its card's toggle reads "Saved". */
    val saved: Boolean = false,
    /** Its place in the section's own order, before saved lists moved up (saved-lists.js puts an un-saved card back there). */
    val baseOrder: Int = 0,
) {
    val total: Int get() = list.itemCount
}

/**
 * Every official list with the viewer's own progress: the Discover row, `/lists/official` and `GET /api/v1/lists/official`.
 * The viewer's saved lists come first (newest save first, user 2026-11-24), then the rest, most recently active first.
 * Four queries in all (lists, covers, seen counts, saved). With [containing], only lists that have at least one title of
 * that type (Discover's Movies / TV row, 2026-11-23), filtered in the same lists query.
 */
@Service
class GetOfficialListsHandler(
    private val listSummaryRepository: ListSummaryRepository,
    private val listProgressHandler: GetListProgressHandler,
    private val savedLists: SavedListRepository,
) {
    @Transactional(readOnly = true)
    fun handle(viewerId: User.Id, containing: MediaType? = null): List<OfficialListProgress> {
        val found = containing?.let(listSummaryRepository::findOfficialContaining) ?: listSummaryRepository.findOfficial()
        val lists = found.filter { it.isOfficial && it.isPublic }
        if (lists.isEmpty()) return emptyList()
        val seen = listProgressHandler.seenCounts(viewerId, lists.map { it.id })
        val saved = savedLists.findByUser(viewerId)
        val savedIds = saved.map { it.listId }.toSet()
        val progress = lists.mapIndexed { index, list -> OfficialListProgress(list, seen[list.id] ?: 0, list.id in savedIds, index) }
        return SavedFirst.order(progress, saved) { it.list.id }
    }
}
