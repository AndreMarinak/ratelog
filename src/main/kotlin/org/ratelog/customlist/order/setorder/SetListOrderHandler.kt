package org.ratelog.customlist.order.setorder

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

sealed interface SetListOrderError {
    data object ListNotFound : SetListOrderError
    data object NotOwner : SetListOrderError
    /** Ids that aren't items of this list. Nothing changes. */
    data class NotInList(val itemIds: List<CustomListItem.Id>) : SetListOrderError
    /** The same item twice. Nothing changes. */
    data class Duplicates(val itemIds: List<CustomListItem.Id>) : SetListOrderError
}

data class SetListOrder(
    val listId: CustomList.Id,
    val userId: User.Id,
    /** The new order. Items left out keep their order and follow these. */
    val itemIds: List<CustomListItem.Id>,
)

/** Sets a whole list's order at once (`PUT /api/v1/lists/{id}/order`), renumbering it 1..n in one transaction. */
@Service
class SetListOrderHandler(
    private val customListRepository: CustomListRepository,
    private val listOrderRepository: ListOrderRepository,
) {
    @Transactional
    fun handle(command: SetListOrder): Either<SetListOrderError, List<CustomListItem.Id>> = either {
        listOrderRepository.lock(command.listId)
        val list = customListRepository.findById(command.listId) ?: raise(SetListOrderError.ListNotFound)
        ensure(list.userId == command.userId) { SetListOrderError.NotOwner }

        val known = list.items.mapNotNull { it.id }.toSet()
        val unknown = command.itemIds.filter { it !in known }.distinct()
        ensure(unknown.isEmpty()) { SetListOrderError.NotInList(unknown) }
        val duplicates = command.itemIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()
        ensure(duplicates.isEmpty()) { SetListOrderError.Duplicates(duplicates) }

        val order = ListOrder.withLeading(ListOrder.ids(list.items), command.itemIds)
        listOrderRepository.renumber(command.listId, order)
        order
    }
}
