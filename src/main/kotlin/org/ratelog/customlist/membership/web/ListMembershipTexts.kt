package org.ratelog.customlist.membership.web

import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder

/**
 * The sheet's and button's strings for the JSON answer, from the same keys the template uses
 * (`templates/fragments/add-to-list-modal.html`), so a toggle in place reads exactly like a fresh page.
 */
class ListMembershipTexts(private val messages: MessageSource) {
    fun summary(count: Int) = text("list.member.summary", count)
    fun buttonLabel(count: Int) = text("list.member.button", count)
    fun buttonTitle(count: Int) = text("list.member.button.title", count)
    fun rank(rank: Int) = text("list.member.rank", rank)
    fun confirmRemove(listName: String, rank: Int?) =
        if (rank != null) text("list.member.confirm.remove", listName, rank) else text("list.member.confirm.remove.norank", listName)
    fun added(listName: String) = text("list.member.added", listName)
    fun removed(listName: String) = text("list.member.removed", listName)
    fun error(key: String) = text(key)

    private fun text(key: String, vararg args: Any): String =
        messages.getMessage(key, args.takeIf { it.isNotEmpty() }, key, LocaleContextHolder.getLocale()) ?: key

    companion object {
        /** Flash keys for plain form posts: the title page shows `#{${success}}`, which takes no arguments. */
        const val ADDED_PLAIN = "list.member.added.plain"
        const val REMOVED_PLAIN = "list.member.removed.plain"
    }
}
