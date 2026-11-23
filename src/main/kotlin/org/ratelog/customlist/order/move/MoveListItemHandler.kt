package org.ratelog.customlist.order.move

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.CustomListRepository
import org.ratelog.customlist.order.ListOrder
import org.ratelog.customlist.order.ListOrderRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface MoveListItemError {
    data object ListNotFound : MoveListItemError
    /** Only the list's owner changes its order; admins don't, official lists included. */
    data object NotOwner : MoveListItemError
    data object ItemNotFound : MoveListItemError
}

/** Where to move a title. Relative targets are worked out after the list is locked, from its current order. */
sealed interface MoveTarget {
    /** 1-based place. Clamped to the list: 0 or less = first, past the end = last. */
    data class Place(val position: Int) : MoveTarget
    data object Top : MoveTarget
    data object Bottom : MoveTarget
    data object Up : MoveTarget
    data object Down : MoveTarget

    /** The place this target means for an item now at [current] (1-based) in a list of [total]. */
    fun resolve(current: Int, total: Int): Int = when (this) {
        is Place -> position
        Top -> 1
        Bottom -> total
        Up -> current - 1
        Down -> current + 1
    }

    companion object {
        /** The page's buttons: `to=top|up|down|bottom`. Anything else is null. */
        fun parse(value: String?): MoveTarget? = when (value?.trim()?.lowercase()) {
            "top", "first" -> Top
            "bottom", "last" -> Bottom
            "up" -> Up
            "down" -> Down
            else -> null
        }
    }
}

data class MoveListItem(
    val listId: CustomList.Id,
    val userId: User.Id,
    val itemId: CustomListItem.Id,
    val target: MoveTarget,
)

data class ListItemMoved(
    val itemId: CustomListItem.Id,
    /** Where it was and where it is now (1-based, after clamping). */
    val from: Int,
    val to: Int,
    val total: Int,
    /** Every item of the list, in the new order. */
    val order: List<CustomListItem.Id>,
)

/**
 * Moves one title to a place in its list and renumbers the whole list 1..n in one transaction, with the list locked
 * so concurrent moves don't interleave. Used by the list page's reorder mode and the API.
 */
@Service
class MoveListItemHandler(
    private val customListRepository: CustomListRepository,
    private val listOrderRepository: ListOrderRepository,
) {
    @Transactional
    fun handle(command: MoveListItem): Either<MoveListItemError, ListItemMoved> = either {
        listOrderRepository.lock(command.listId)
        val list = customListRepository.findById(command.listId) ?: raise(MoveListItemError.ListNotFound)
        ensure(list.userId == command.userId) { MoveListItemError.NotOwner }
        ensure(list.items.any { it.id == command.itemId }) { MoveListItemError.ItemNotFound }

        val before = ListOrder.ids(list.items)
        val position = command.target.resolve(before.indexOf(command.itemId) + 1, before.size)
        val after = ListOrder.move(before, command.itemId, position)
        listOrderRepository.renumber(command.listId, after)
        ListItemMoved(
            itemId = command.itemId,
            from = before.indexOf(command.itemId) + 1,
            to = after.indexOf(command.itemId) + 1,
            total = after.size,
            order = after,
        )
    }
}
