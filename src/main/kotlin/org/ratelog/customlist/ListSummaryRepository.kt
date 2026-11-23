package org.ratelog.customlist

import org.ratelog.user.User

/** A list as a card: name, owner, size and the first posters for the cover (fork, used by /lists and the home page). */
data class ListSummary(
    val id: CustomList.Id,
    val ownerId: User.Id,
    val ownerUsername: String,
    val name: String,
    val isPublic: Boolean,
    val itemCount: Int,
    /** Posters of the first items that have one, in list order (at most [COVER_SIZE]). */
    val coverPosterPaths: List<String>,
    /** When the list was created or last had a title added. */
    val lastActivityEpochMs: Long,
    /** An admin-curated list every account sees (fork, `official_lists`). Official lists are always public. */
    val isOfficial: Boolean = false,
) {
    companion object {
        const val COVER_SIZE = 4
    }
}

/** Card data for lists, most recently active first. Kept apart from upstream's [CustomListRepository]. */
interface ListSummaryRepository {
    /** Every list of the owner, public or private. */
    fun findByOwner(ownerId: User.Id): List<ListSummary>

    /** Public, non-empty lists of these owners, leaving out official lists (they have their own section). */
    fun findPublicByOwners(ownerIds: Collection<User.Id>, limit: Int): List<ListSummary>

    /** Public, non-empty lists of everyone except these owners, leaving out official lists. */
    fun findPublicExcludingOwners(excludedOwnerIds: Collection<User.Id>, limit: Int): List<ListSummary>

    /** Every official list (any owner, empty ones too), most recently active first. */
    fun findOfficial(): List<ListSummary>
}
