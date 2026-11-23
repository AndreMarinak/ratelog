package org.ratelog.customlist.membership

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListRepository
import org.ratelog.customlist.additem.AddToListCommand
import org.ratelog.customlist.additem.AddToListError
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.customlist.removeitem.RemoveFromListCommand
import org.ratelog.customlist.removeitem.RemoveFromListError
import org.ratelog.customlist.removeitem.RemoveFromListHandler
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface SetListMembershipError {
    data object ListNotFound : SetListMembershipError
    data object NotOwner : SetListMembershipError
}

/**
 * Puts a title in one of the viewer's lists, or takes it out: a tap on a list in the add-to-list sheet.
 * [inList] is the state the sheet asked for (not "toggle"), so a repeated tap or a stale second tab never does the opposite.
 */
data class SetListMembership(
    val listId: CustomList.Id,
    val userId: User.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id), like custom_list_items.media_id. */
    val mediaId: Long,
    val inList: Boolean,
)

data class SetListMembershipResult(
    val listName: ListName,
    val inList: Boolean,
    /** false when the title already was where it was asked to be. */
    val changed: Boolean,
)

/**
 * Checks the list and its owner like the add / remove handlers do, then hands the change to them, so adding appends
 * the title exactly like "Add to list" and removing goes through the same path as the list page's Remove.
 */
@Service
class SetListMembershipHandler(
    private val customListRepository: CustomListRepository,
    private val addToListHandler: AddToListHandler,
    private val removeFromListHandler: RemoveFromListHandler,
) {
    @Transactional
    fun handle(command: SetListMembership): Either<SetListMembershipError, SetListMembershipResult> = either {
        val list = customListRepository.findById(command.listId)
            ?: raise(SetListMembershipError.ListNotFound)

        if (list.userId != command.userId) {
            raise(SetListMembershipError.NotOwner)
        }

        val existing = list.items.firstOrNull { it.mediaType == command.mediaType && it.mediaId == command.mediaId }

        val changed = when {
            command.inList && existing == null ->
                addToListHandler.handle(AddToListCommand(command.listId, command.userId, command.mediaId, command.mediaType.name))
                    .fold(
                        { error ->
                            when (error) {
                                AddToListError.ListNotFound -> raise(SetListMembershipError.ListNotFound)
                                AddToListError.NotOwner -> raise(SetListMembershipError.NotOwner)
                                AddToListError.ItemAlreadyInList -> false
                            }
                        },
                        { true },
                    )

            !command.inList && existing != null ->
                removeFromListHandler.handle(RemoveFromListCommand(command.listId, command.userId, existing.id!!))
                    .fold(
                        { error ->
                            when (error) {
                                RemoveFromListError.ListNotFound -> raise(SetListMembershipError.ListNotFound)
                                RemoveFromListError.NotOwner -> raise(SetListMembershipError.NotOwner)
                                RemoveFromListError.ItemNotFound -> false
                            }
                        },
                        { true },
                    )

            else -> false
        }

        SetListMembershipResult(list.name, command.inList, changed)
    }
}
