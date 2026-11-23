package org.ratelog.customlist.order

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem

class ListOrderTest {

    private fun id(value: Long) = CustomListItem.Id(value)
    private val abcde = listOf(1L, 2L, 3L, 4L, 5L).map(::id)
    private fun item(id: Long, position: Int) = CustomListItem(id(id), CustomList.Id(1), id, MediaType.movie, position, 0L)

    @Test
    fun `list order is position, then id for ties`() {
        val items = listOf(item(5, 2), item(3, 1), item(4, 2), item(9, 7))
        assertEquals(listOf(3L, 4L, 5L, 9L), ListOrder.ids(items).map { it.value })
    }

    @Test
    fun `move puts the item at the 1-based place and shifts the rest`() {
        assertEquals(listOf(4L, 1L, 2L, 3L, 5L), ListOrder.move(abcde, id(4), 1).map { it.value }, "up to the top")
        assertEquals(listOf(2L, 3L, 1L, 4L, 5L), ListOrder.move(abcde, id(1), 3).map { it.value }, "down")
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L), ListOrder.move(abcde, id(3), 5).map { it.value }, "to the end")
        assertEquals(abcde, ListOrder.move(abcde, id(2), 2), "same place")
    }

    @Test
    fun `move clamps out-of-range places`() {
        assertEquals(listOf(3L, 1L, 2L, 4L, 5L), ListOrder.move(abcde, id(3), 0).map { it.value })
        assertEquals(listOf(3L, 1L, 2L, 4L, 5L), ListOrder.move(abcde, id(3), -7).map { it.value })
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L), ListOrder.move(abcde, id(3), 99).map { it.value })
    }

    @Test
    fun `move refuses an item that isn't in the order`() {
        assertThrows<IllegalArgumentException> { ListOrder.move(abcde, id(42), 1) }
    }

    @Test
    fun `withLeading puts the given ids first and keeps the rest in order`() {
        assertEquals(listOf(4L, 2L, 1L, 3L, 5L), ListOrder.withLeading(abcde, listOf(id(4), id(2))).map { it.value })
        assertEquals(abcde, ListOrder.withLeading(abcde, emptyList()))
        assertEquals(abcde.reversed(), ListOrder.withLeading(abcde, abcde.reversed()))
    }

    @Test
    fun `places are 1-based`() {
        assertEquals(mapOf(id(1) to 1, id(2) to 2), ListOrder.places(listOf(id(1), id(2))))
    }
}
