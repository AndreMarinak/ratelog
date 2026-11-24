package org.ratelog.customlist.browse

import org.ratelog.customlist.CustomList
import org.ratelog.customlist.ListSummary
import org.ratelog.customlist.ListSummaryRepository
import org.ratelog.customlist.saved.SavedFirst
import org.ratelog.customlist.saved.SavedListPolicy
import org.ratelog.customlist.saved.SavedListRepository
import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.springframework.stereotype.Service

data class GetListsPage(
    val userId: User.Id,
    /** How many of the other users' public lists to show ("Load more" raises it). */
    val limit: Int = GetListsPageHandler.PAGE_SIZE,
)

data class ListsPage(
    /**
     * Admin-curated lists for everyone (fork), shown first; the viewer's saved ones lead it (2026-11-24). Never repeated
     * under [followed] or [others].
     */
    val official: List<ListSummary>,
    /** The viewer's own lists, public and private (their official ones included: it is the owner's view). */
    val mine: List<ListSummary>,
    /** Official lists the viewer saved (newest save first), shown after [mine]; never one of [mine]. */
    val saved: List<ListSummary> = emptyList(),
    /** Each official list's place before the saved ones moved up (saved-lists.js puts an un-saved card back there). */
    val officialBaseOrder: Map<CustomList.Id, Int> = emptyMap(),
    /** Public lists of people the viewer follows. */
    val followed: List<ListSummary>,
    /** Public lists of everyone else. */
    val others: List<ListSummary>,
    val hasMoreOthers: Boolean,
    /** The limit actually used for [others]. */
    val limit: Int,
)

/**
 * The /lists page: official lists (saved ones first), then the viewer's own plus the official lists they saved, then
 * public lists of people they follow, then everyone else's.
 * Only the viewer's own lists can be private; everything else is filtered to public twice (query and here), and
 * official lists are left out of "followed" and "others" the same way.
 */
@Service
class GetListsPageHandler(
    private val listSummaryRepository: ListSummaryRepository,
    private val userRepository: UserRepository,
    private val savedLists: SavedListRepository,
) {
    fun handle(query: GetListsPage): ListsPage {
        val limit = query.limit.coerceIn(PAGE_SIZE, MAX_LIMIT)
        val byActivity = listSummaryRepository.findOfficial().filter { it.isOfficial && it.isPublic }
        val savedRows = savedLists.findByUser(query.userId)
        val official = SavedFirst.order(byActivity, savedRows) { it.id }
        val officialIds = official.map { it.id }.toSet()
        val mine = listSummaryRepository.findByOwner(query.userId).filter { it.ownerId == query.userId }
        val saved = SavedListPolicy.visible(savedRows, byActivity, excludeOwner = query.userId)

        val followedIds = userRepository.findFollowingByUserId(query.userId)
            .mapNotNull { it.id }
            .filter { it != query.userId }
            .distinct()
        val followed = listSummaryRepository.findPublicByOwners(followedIds, FOLLOWED_LIMIT)
            .filter { it.isPublic && it.ownerId in followedIds && !it.isOfficial && it.id !in officialIds }

        val excluded = (followedIds + query.userId).toSet()
        val othersPlusOne = listSummaryRepository.findPublicExcludingOwners(excluded, limit + 1)
            .filter { it.isPublic && it.ownerId !in excluded && !it.isOfficial && it.id !in officialIds }

        return ListsPage(
            official = official,
            mine = mine,
            saved = saved,
            officialBaseOrder = byActivity.mapIndexed { index, it -> it.id to index }.toMap(),
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
