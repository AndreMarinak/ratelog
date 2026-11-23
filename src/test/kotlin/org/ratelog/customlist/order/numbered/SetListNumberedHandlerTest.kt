package org.ratelog.customlist.order.numbered

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.order.ListOrderFixture

class SetListNumberedHandlerTest {

    private val f = ListOrderFixture()

    @Test
    fun `lists are numbered until the owner makes them plain`() {
        val (list, _) = f.list("A", "B")
        assertTrue(f.options.isNumbered(list.id!!), "no row = numbered: every existing list keeps its ranks")

        assertEquals(false, f.setNumbered.handle(SetListNumbered(list.id!!, f.owner, false)).getOrNull())
        assertFalse(f.options.isNumbered(list.id!!))
        assertEquals(setOf(list.id), f.options.plainAmong(listOf(list.id!!)))

        f.setNumbered.handle(SetListNumbered(list.id!!, f.owner, true))
        assertTrue(f.options.isNumbered(list.id!!))
        assertEquals(emptySet<CustomList.Id>(), f.options.plainAmong(listOf(list.id!!)))
    }

    @Test
    fun `setting the current value writes nothing`() {
        val (list, _) = f.list("A")

        f.setNumbered.handle(SetListNumbered(list.id!!, f.owner, true))

        assertTrue(f.options.rows().isEmpty())
    }

    @Test
    fun `only the owner, and only an existing list`() {
        val (list, _) = f.list("A")

        assertEquals(SetListNumberedError.NotOwner, f.setNumbered.handle(SetListNumbered(list.id!!, f.other, false)).leftOrNull())
        assertEquals(SetListNumberedError.ListNotFound, f.setNumbered.handle(SetListNumbered(CustomList.Id(999), f.owner, false)).leftOrNull())
        assertTrue(f.options.isNumbered(list.id!!))
    }

    @Test
    fun `deleting the list drops its options`() {
        val (list, _) = f.list("A")
        f.setNumbered.handle(SetListNumbered(list.id!!, f.owner, false))

        f.lists.delete(list.id!!)

        assertTrue(f.options.rows().isEmpty())
    }

    @Test
    fun `the form field reads the checkbox and its marker`() {
        assertEquals(true, NumberedField.parse("true", "1"))
        assertEquals(true, NumberedField.parse("on", null))
        assertEquals(false, NumberedField.parse(null, "1"), "box on the form but unchecked = plain")
        assertEquals(false, NumberedField.parse("false", "1"))
        assertNull(NumberedField.parse(null, null), "a form without the field changes nothing")
    }

    @Test
    fun `the form helper saves only when the field was sent`() {
        val (list, _) = f.list("A")
        val form = ListNumberedForm(f.setNumbered)

        form.save(list.id!!, f.owner, null, null)
        assertTrue(f.options.rows().isEmpty())

        form.save(list.id!!, f.owner, null, "1")
        assertFalse(f.options.isNumbered(list.id!!))

        form.save(list.id!!, f.other, "true", "1")
        assertFalse(f.options.isNumbered(list.id!!), "someone else's form post changes nothing")
    }
}
