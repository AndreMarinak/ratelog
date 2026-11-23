package org.ratelog.customlist.order.move

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.order.ListOrderFixture

class MoveListItemHandlerTest {

    private val f = ListOrderFixture()

    private fun move(listId: org.ratelog.customlist.CustomList.Id, item: CustomListItem, target: MoveTarget, by: org.ratelog.user.User.Id = f.owner) =
        f.move.handle(MoveListItem(listId, by, item.id!!, target))

    @Test
    fun `moves a title to a place and renumbers the list 1 to n`() {
        val (list, items) = f.list("Coco", "Up", "Moana", "Frozen", "Cars")

        val moved = move(list.id!!, items[3], MoveTarget.Place(1)).getOrNull()!!

        assertEquals(listOf("Frozen", "Coco", "Up", "Moana", "Cars"), f.titles(list))
        assertEquals(listOf(1, 2, 3, 4, 5), f.positions(list))
        assertEquals(4, moved.from)
        assertEquals(1, moved.to)
        assertEquals(5, moved.total)
        assertEquals(listOf(items[3], items[0], items[1], items[2], items[4]).map { it.id }, moved.order)
        assertEquals(listOf(list.id), f.order.locked, "the list is locked before it is read")
    }

    @Test
    fun `only rows whose place changes are written`() {
        val (list, items) = f.list("A", "B", "C", "D", "E", "F")

        move(list.id!!, items[1], MoveTarget.Place(3))

        assertEquals(listOf("A", "C", "B", "D", "E", "F"), f.titles(list))
        assertEquals(2, f.order.rowsWritten, "B and C swap; A, D, E and F stay")
    }

    @Test
    fun `places outside the list are clamped`() {
        val (list, items) = f.list("A", "B", "C")

        assertEquals(1, move(list.id!!, items[2], MoveTarget.Place(0)).getOrNull()!!.to)
        assertEquals(listOf("C", "A", "B"), f.titles(list))
        assertEquals(3, move(list.id!!, items[2], MoveTarget.Place(500)).getOrNull()!!.to)
        assertEquals(listOf("A", "B", "C"), f.titles(list))
    }

    @Test
    fun `relative targets use the saved order`() {
        val (list, items) = f.list("A", "B", "C", "D")
        val id = list.id!!

        move(id, items[2], MoveTarget.Up)
        assertEquals(listOf("A", "C", "B", "D"), f.titles(list))
        move(id, items[2], MoveTarget.Down)
        assertEquals(listOf("A", "B", "C", "D"), f.titles(list))
        move(id, items[1], MoveTarget.Bottom)
        assertEquals(listOf("A", "C", "D", "B"), f.titles(list))
        move(id, items[1], MoveTarget.Top)
        assertEquals(listOf("B", "A", "C", "D"), f.titles(list))
        assertEquals(1, move(id, items[1], MoveTarget.Up).getOrNull()!!.to, "up from the top stays at the top")
        assertEquals(listOf("B", "A", "C", "D"), f.titles(list))
    }

    @Test
    fun `old lists with gaps and ties are renumbered in the order they were shown`() {
        val (list, _) = f.list()
        val ids = listOf("A", "B", "C", "D").map { f.p.movie(it) }
        // Positions 3, 3, 9, 1: shown as D, A, B, C (position, then id).
        val positions = listOf(3, 3, 9, 1)
        val items = ids.zip(positions).map { (movie, position) ->
            f.lists.addItem(CustomListItem(null, list.id!!, movie.id!!.value, MediaType.movie, position, 0L))
        }
        assertEquals(listOf("D", "A", "B", "C"), f.titles(list))

        move(list.id!!, items[2], MoveTarget.Place(2)) // C to #2

        assertEquals(listOf("D", "C", "A", "B"), f.titles(list))
        assertEquals(listOf(1, 2, 3, 4), f.positions(list))
    }

    @Test
    fun `only the owner can reorder, admins included`() {
        val (list, items) = f.list("A", "B")

        assertEquals(MoveListItemError.NotOwner, move(list.id!!, items[1], MoveTarget.Top, by = f.other).leftOrNull())
        assertEquals(listOf("A", "B"), f.titles(list))
    }

    @Test
    fun `a missing list or an item of another list is refused`() {
        val (list, items) = f.list("A", "B")
        val (_, otherItems) = f.list("X", "Y")

        assertEquals(MoveListItemError.ListNotFound, move(org.ratelog.customlist.CustomList.Id(999), items[0], MoveTarget.Top).leftOrNull())
        assertEquals(MoveListItemError.ItemNotFound, move(list.id!!, otherItems[1], MoveTarget.Top).leftOrNull())
        assertEquals(listOf("A", "B"), f.titles(list))
    }

    @Test
    fun `targets resolve and parse`() {
        assertEquals(1, MoveTarget.Top.resolve(4, 9))
        assertEquals(9, MoveTarget.Bottom.resolve(4, 9))
        assertEquals(3, MoveTarget.Up.resolve(4, 9))
        assertEquals(5, MoveTarget.Down.resolve(4, 9))
        assertEquals(7, MoveTarget.Place(7).resolve(4, 9))
        assertEquals(MoveTarget.Top, MoveTarget.parse(" TOP "))
        assertEquals(MoveTarget.Bottom, MoveTarget.parse("last"))
        assertEquals(MoveTarget.Up, MoveTarget.parse("up"))
        assertEquals(MoveTarget.Down, MoveTarget.parse("down"))
        assertNull(MoveTarget.parse("sideways"))
        assertNull(MoveTarget.parse(null))
        assertTrue(MoveTarget.parse("") == null)
    }
}
