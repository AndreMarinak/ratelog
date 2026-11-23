package org.ratelog.customlist.order.numbered

import org.ratelog.customlist.CustomList
import org.ratelog.user.User
import org.springframework.stereotype.Component

/**
 * The one call upstream's create / edit list controllers make for the "Numbered list" checkbox, after their own
 * save succeeded. A form without the field leaves the setting as it is (new lists are numbered).
 */
@Component
class ListNumberedForm(
    private val handler: SetListNumberedHandler,
) {
    fun save(listId: CustomList.Id, userId: User.Id, numbered: String?, marker: String?) {
        val value = NumberedField.parse(numbered, marker) ?: return
        handler.handle(SetListNumbered(listId, userId, value))
    }
}
