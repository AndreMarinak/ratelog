package org.ratelog.customlist.order.numbered

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListRepository
import org.ratelog.customlist.order.ListOptionsRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface SetListNumberedError {
    data object ListNotFound : SetListNumberedError
    data object NotOwner : SetListNumberedError
}

data class SetListNumbered(
    val listId: CustomList.Id,
    val userId: User.Id,
    val numbered: Boolean,
)

/**
 * "Numbered list" on or off (owner only). Numbered lists show each title's rank on the list page; plain lists are a
 * collection without numbers. The order itself is kept either way. Returns the new value.
 */
@Service
class SetListNumberedHandler(
    private val customListRepository: CustomListRepository,
    private val listOptionsRepository: ListOptionsRepository,
) {
    @Transactional
    fun handle(command: SetListNumbered): Either<SetListNumberedError, Boolean> = either {
        val list = customListRepository.findById(command.listId) ?: raise(SetListNumberedError.ListNotFound)
        ensure(list.userId == command.userId) { SetListNumberedError.NotOwner }
        if (listOptionsRepository.isNumbered(command.listId) != command.numbered) {
            listOptionsRepository.setNumbered(command.listId, command.numbered)
        }
        command.numbered
    }
}

/**
 * The "Numbered list" checkbox of the create / edit list forms (fragments/list-order :: numberedField). A checkbox
 * sends nothing when unchecked, so the fragment also sends a `_numbered` marker: marker without the box = plain.
 * Neither (a form without the field) = null, leave the setting alone.
 */
object NumberedField {
    const val PARAM = "numbered"
    const val MARKER = "_numbered"

    fun parse(numbered: String?, marker: String?): Boolean? = when {
        numbered != null -> numbered.trim().lowercase() in setOf("true", "on", "1", "yes")
        marker != null -> false
        else -> null
    }
}
