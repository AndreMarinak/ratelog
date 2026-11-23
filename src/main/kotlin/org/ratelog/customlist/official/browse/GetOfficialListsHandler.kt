package org.ratelog.customlist.official.browse

import org.ratelog.customlist.ListSummary
import org.ratelog.customlist.ListSummaryRepository
import org.ratelog.customlist.progress.GetListProgressHandler
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** An official list with how many of its titles the viewer has seen. */
data class OfficialListProgress(
    val list: ListSummary,
    val seen: Int,
) {
    val total: Int get() = list.itemCount
}

/**
 * Every official list with the viewer's own progress, most recently active first: the Discover row,
 * `/lists/official` and `GET /api/v1/lists/official`. Three queries in all (lists, covers, seen counts).
 */
@Service
class GetOfficialListsHandler(
    private val listSummaryRepository: ListSummaryRepository,
    private val listProgressHandler: GetListProgressHandler,
) {
    @Transactional(readOnly = true)
    fun handle(viewerId: User.Id): List<OfficialListProgress> {
        val lists = listSummaryRepository.findOfficial().filter { it.isOfficial && it.isPublic }
        if (lists.isEmpty()) return emptyList()
        val seen = listProgressHandler.seenCounts(viewerId, lists.map { it.id })
        return lists.map { OfficialListProgress(it, seen[it.id] ?: 0) }
    }
}
