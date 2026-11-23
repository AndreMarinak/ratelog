package org.ratelog.customlist.browse

import org.ratelog.customlist.ListSummary
import org.ratelog.customlist.ListSummaryRepository
import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.springframework.stereotype.Service

data class GetListsPage(
    val userId: User.Id,
    /** How many of the other users' public lists to show ("Load more" raises it). */
    val limit: Int = GetListsPageHandler.PAGE_SIZE,
)

data class ListsPage(
    /** The viewer's own lists, public and private. */
    val mine: List<ListSummary>,
    /** Public lists of people the viewer follows. */
    val followed: List<ListSummary>,
    /** Public lists of everyone else. */
    val others: List<ListSummary>,
    val hasMoreOthers: Boolean,
    /** The limit actually used for [others]. */
    val limit: Int,
)

/** The /lists page. Only the viewer's own lists can be private; everything else is filtered to public twice (query and here). */
@Service
class GetListsPageHandler(
    private val listSummaryRepository: ListSummaryRepository,
    private val userRepository: UserRepository,
) {
    fun handle(query: GetListsPage): ListsPage {
        val limit = query.limit.coerceIn(PAGE_SIZE, MAX_LIMIT)
        val mine = listSummaryRepository.findByOwner(query.userId).filter { it.ownerId == query.userId }

        val followedIds = userRepository.findFollowingByUserId(query.userId)
            .mapNotNull { it.id }
            .filter { it != query.userId }
            .distinct()
        val followed = listSummaryRepository.findPublicByOwners(followedIds, FOLLOWED_LIMIT)
            .filter { it.isPublic && it.ownerId in followedIds }

        val excluded = (followedIds + query.userId).toSet()
        val othersPlusOne = listSummaryRepository.findPublicExcludingOwners(excluded, limit + 1)
            .filter { it.isPublic && it.ownerId !in excluded }

        return ListsPage(
            mine = mine,
            followed = followed,
            others = othersPlusOne.take(limit),
            hasMoreOthers = othersPlusOne.size > limit,
            limit = limit,
        )
    }

    companion object {
        const val PAGE_SIZE = 24
        const val MAX_LIMIT = 240
        const val FOLLOWED_LIMIT = 60
    }
}
