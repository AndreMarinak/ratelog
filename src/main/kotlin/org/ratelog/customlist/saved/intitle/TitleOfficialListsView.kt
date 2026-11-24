package org.ratelog.customlist.saved.intitle

import org.ratelog.user.User

/**
 * What `fragments/saved-lists :: titleLine` (the title page's "In official lists" chips) and `:: sheetLine` (the
 * add-to-list sheet's "Also in your saved lists") render. Built from one query ([OfficialTitleListsRepository]).
 */
data class TitleOfficialListsView(
    /** Every public official list with the title, saved ones first ([OfficialTitleListsOrder]). */
    val chips: List<OfficialListChip>,
    /** The viewer's saved lists with the title that aren't their own (their own lists are ticks in the sheet). */
    val savedNotOwned: List<OfficialListChip>,
) {
    /** The first [SHOWN] chips; the rest sit behind "+N more". */
    val shown: List<OfficialListChip> get() = chips.take(SHOWN)
    val more: List<OfficialListChip> get() = chips.drop(SHOWN)
    val isEmpty: Boolean get() = chips.isEmpty()

    companion object {
        const val SHOWN = 3

        fun of(lists: List<TitleInOfficialList>, viewerId: User.Id): TitleOfficialListsView {
            val chips = OfficialTitleListsOrder.sort(lists).map { OfficialListChip(it.listId.value, it.name, it.rank, it.saved, it.ownerId == viewerId) }
            return TitleOfficialListsView(chips, chips.filter { it.saved && !it.owned })
        }
    }
}

data class OfficialListChip(
    val listId: Long,
    val name: String,
    /** The title's place, only on a numbered list. */
    val rank: Int?,
    val saved: Boolean,
    /** The viewer owns the list (an admin's own official list). */
    val owned: Boolean = false,
) {
    val href: String get() = "/list/$listId"
}
