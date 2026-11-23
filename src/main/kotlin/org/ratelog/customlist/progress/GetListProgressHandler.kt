package org.ratelog.customlist.progress

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface GetListProgressError {
    data object ListNotFound : GetListProgressError
    /** A private list of someone else. */
    data object NotAuthorized : GetListProgressError
}

data class GetListProgress(
    val listId: CustomList.Id,
    /** Progress is always the viewer's own, on their lists and on other people's public ones. */
    val viewerId: User.Id,
)

@Service
class GetListProgressHandler(
    private val customListRepository: CustomListRepository,
    private val listProgressRepository: ListProgressRepository,
) {
    @Transactional(readOnly = true)
    fun handle(query: GetListProgress): Either<GetListProgressError, ListProgress> = either {
        val list = customListRepository.findById(query.listId) ?: raise(GetListProgressError.ListNotFound)
        if (!list.isPublic && list.userId != query.viewerId) raise(GetListProgressError.NotAuthorized)
        progressOf(list, query.viewerId)
    }

    /** For a list the caller has already loaded and checked: one marks query per media type in the list. */
    fun progressOf(list: CustomList, viewerId: User.Id): ListProgress {
        val marks = list.items
            .groupBy({ it.mediaType }, { it.mediaId })
            .flatMap { (type, ids) ->
                listProgressRepository.findMarks(viewerId, type, ids).map { (mediaId, mark) -> (type to mediaId) to mark }
            }
            .toMap<Pair<MediaType, Long>, ViewerMark>()
        return ListProgress.of(list, marks)
    }

    /** Seen counts for list cards, in one query. Lists with nothing seen map to 0. */
    @Transactional(readOnly = true)
    fun seenCounts(viewerId: User.Id, listIds: Collection<CustomList.Id>): Map<CustomList.Id, Int> {
        val counts = listProgressRepository.countSeen(viewerId, listIds)
        return listIds.associateWith { counts[it] ?: 0 }
    }
}
