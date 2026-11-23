package org.ratelog.customlist.progress

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Repository

@Repository
class ListProgressRepositoryImpl(
    private val dao: ListProgressDAO,
) : ListProgressRepository {

    override fun findMarks(userId: User.Id, mediaType: MediaType, mediaIds: Collection<Long>): Map<Long, ViewerMark> {
        if (mediaIds.isEmpty()) return emptyMap()
        val ids = mediaIds.distinct()
        val rows = when (mediaType) {
            MediaType.movie -> dao.findMovieMarks(userId.value, ids)
            MediaType.tvshow -> dao.findTvMarks(userId.value, ids)
        }
        return rows
            .map { it.mediaId to ViewerMark(it.status?.let(WatchStatus::valueOf), it.score, it.rated) }
            .filter { (_, mark) -> mark != ViewerMark.NONE }
            .toMap()
    }

    override fun countSeen(userId: User.Id, listIds: Collection<CustomList.Id>): Map<CustomList.Id, Int> {
        if (listIds.isEmpty()) return emptyMap()
        return dao.countSeenByList(userId.value, listIds.map { it.value }.distinct())
            .associate { CustomList.Id(it.listId) to it.seen.toInt() }
    }

    override fun addToWatchlist(userId: User.Id, listId: CustomList.Id, itemIds: Collection<CustomListItem.Id>, nowEpochMs: Long): Int {
        if (itemIds.isEmpty()) return 0
        return dao.insertWantToWatch(userId.value, listId.value, itemIds.map { it.value }.distinct(), nowEpochMs)
    }
}
