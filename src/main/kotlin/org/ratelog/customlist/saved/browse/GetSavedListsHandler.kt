package org.ratelog.customlist.saved.browse

import org.ratelog.customlist.ListSummary
import org.ratelog.customlist.ListSummaryRepository
import org.ratelog.customlist.progress.GetListProgressHandler
import org.ratelog.customlist.saved.SavedListPolicy
import org.ratelog.customlist.saved.SavedListRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A saved list with the viewer's progress and when they saved it. */
data class SavedListProgress(
    val list: ListSummary,
    val seen: Int,
    val savedAtEpochMs: Long,
)

/**
 * The viewer's saved lists that they can still see (public official lists, [SavedListPolicy]), newest save first, with
 * their progress: the home "Your lists" row and `GET /api/v1/lists/saved`. One query when nothing is saved; otherwise
 * saved ids, official lists (+ covers) and seen counts.
 */
@Service
class GetSavedListsHandler(
    private val savedLists: SavedListRepository,
    private val listSummaryRepository: ListSummaryRepository,
    private val listProgressHandler: GetListProgressHandler,
) {
    /** [includeOwn] = false leaves out lists the viewer owns (pages show those as their own lists already). */
    @Transactional(readOnly = true)
    fun handle(viewerId: User.Id, includeOwn: Boolean = true): List<SavedListProgress> {
        val saved = savedLists.findByUser(viewerId)
        if (saved.isEmpty()) return emptyList()
        val lists = SavedListPolicy.visible(saved, listSummaryRepository.findOfficial(), excludeOwner = viewerId.takeUnless { includeOwn })
        if (lists.isEmpty()) return emptyList()
        val seen = listProgressHandler.seenCounts(viewerId, lists.map { it.id })
        val savedAt = saved.associate { it.listId to it.savedAtEpochMs }
        return lists.map { SavedListProgress(it, seen[it.id] ?: 0, savedAt.getValue(it.id)) }
    }
}
