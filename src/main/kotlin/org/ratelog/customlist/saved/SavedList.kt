package org.ratelog.customlist.saved

import org.ratelog.customlist.CustomList
import org.ratelog.customlist.ListSummary
import org.ratelog.user.User

/*
 * Saved lists (fork, user request 2026-11-24): "being able to favorite an official list and that pins it to front of
 * official lists". A user saves an official list like a playlist: it comes first in every Official lists section, shows
 * under their own lists (Lists tab, home "Your lists") and is named on title pages and in the add-to-list sheet.
 * Stored in `saved_lists` (V1142), apart from upstream's custom_lists. The user said "I'll tell you if I like it", so the
 * rules sit in small pieces that are easy to change: [SavedListPolicy] (what can be saved and shown) and [SavedFirst]
 * (the order).
 */

/** One saved list of one user. */
data class SavedList(
    val listId: CustomList.Id,
    val savedAtEpochMs: Long,
)

interface SavedListRepository {
    /** The user's saved lists, newest save first, whatever their state now ([SavedListPolicy.visible] filters them). */
    fun findByUser(userId: User.Id): List<SavedList>

    fun isSaved(userId: User.Id, listId: CustomList.Id): Boolean

    /** True if the list was not saved before. */
    fun save(userId: User.Id, listId: CustomList.Id, atEpochMs: Long): Boolean

    /** True if the list was saved before. */
    fun unsave(userId: User.Id, listId: CustomList.Id): Boolean
}

/**
 * What can be saved and when a saved list is shown. Only official lists for now (the table allows any list, so public
 * lists could be added later by changing this object). A saved list that is no longer official or public keeps its
 * row but is shown nowhere: the viewer may not be allowed to see it any more.
 */
object SavedListPolicy {
    /** Saving needs a list everyone sees: official and public (a hidden official list is its owner's draft). */
    fun canSave(isOfficial: Boolean, isPublic: Boolean): Boolean = isOfficial && isPublic

    /**
     * The saved lists to show, newest save first: those among [shown] (public official lists, any order). Pages leave out
     * the viewer's own lists ([excludeOwner]), which already show as theirs; the API lists them all.
     */
    fun visible(saved: List<SavedList>, shown: List<ListSummary>, excludeOwner: User.Id?): List<ListSummary> {
        val byId = shown.filter { canSave(it.isOfficial, it.isPublic) && it.ownerId != excludeOwner }.associateBy { it.id }
        return saved.mapNotNull { byId[it.listId] }
    }
}

/**
 * The order of Official lists sections: the viewer's saved lists first (newest save first), then the rest in the
 * section's own order. Stable, so equal items keep their order.
 */
object SavedFirst {
    fun <T> order(items: List<T>, saved: List<SavedList>, id: (T) -> CustomList.Id): List<T> {
        if (saved.isEmpty() || items.isEmpty()) return items
        val savedAt = saved.associate { it.listId to it.savedAtEpochMs }
        val (first, rest) = items.partition { id(it) in savedAt }
        return first.sortedByDescending { savedAt[id(it)] } + rest
    }
}
