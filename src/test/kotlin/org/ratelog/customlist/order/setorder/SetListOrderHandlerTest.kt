package org.ratelog.customlist.order.setorder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.order.ListOrderFixture

class SetListOrderHandlerTest {

    private val f = ListOrderFixture()

    @Test
    fun `sets the whole order and renumbers 1 to n`() {
        val (list, items) = f.list("A", "B", "C", "D")

        val order = f.setOrder.handle(SetListOrder(list.id!!, f.owner, items.reversed().map { it.id!! })).getOrNull()!!

        assertEquals(listOf("D", "C", "B", "A"), f.titles(list))
        assertEquals(listOf(1, 2, 3, 4), f.positions(list))
        assertEquals(items.reversed().map { it.id }, order)
        assertEquals(listOf(list.id), f.order.locked)
    }

    @Test
    fun `titles left out follow in their current order`() {
        val (list, items) = f.list("A", "B", "C", "D", "E")

        f.setOrder.handle(SetListOrder(list.id!!, f.owner, listOf(items[3].id!!, items[1].id!!)))

        assertEquals(listOf("D", "B", "A", "C", "E"), f.titles(list))
    }

    @Test
    fun `unknown or repeated items change nothing`() {
        val (list, items) = f.list("A", "B", "C")
        val (_, others) = f.list("X")

        val unknown = f.setOrder.handle(SetListOrder(list.id!!, f.owner, listOf(items[2].id!!, others[0].id!!, CustomListItem.Id(999))))
        assertEquals(SetListOrderError.NotInList(listOf(others[0].id!!, CustomListItem.Id(999))), unknown.leftOrNull())

        val twice = f.setOrder.handle(SetListOrder(list.id!!, f.owner, listOf(items[2].id!!, items[0].id!!, items[2].id!!)))
        assertEquals(SetListOrderError.Duplicates(listOf(items[2].id!!)), twice.leftOrNull())

        assertEquals(listOf("A", "B", "C"), f.titles(list))
        assertEquals(0, f.order.rowsWritten)
    }

    @Test
    fun `only the owner, and only an existing list`() {
        val (list, items) = f.list("A", "B")

        assertEquals(SetListOrderError.NotOwner, f.setOrder.handle(SetListOrder(list.id!!, f.other, listOf(items[1].id!!))).leftOrNull())
        assertEquals(SetListOrderError.ListNotFound, f.setOrder.handle(SetListOrder(CustomList.Id(999), f.owner, emptyList())).leftOrNull())
        assertEquals(listOf("A", "B"), f.titles(list))
    }
}
