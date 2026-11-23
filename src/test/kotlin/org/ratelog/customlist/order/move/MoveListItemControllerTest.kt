package org.ratelog.customlist.order.move

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.customlist.order.ListOrderFixture
import org.ratelog.test.UserFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import java.util.Locale

/** POST /list/{id}/item/{itemId}/move: the plain form (redirect + flash) and the fetch variant (JSON). */
class MoveListItemControllerTest {

    private val f = ListOrderFixture()
    private val messages = ResourceBundleMessageSource().apply {
        setBasenames("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    private val controller = MoveListItemController(f.move, f.lists, f.p.movies, f.p.shows, messages)
    private val owner = UserFactory.aUser(id = 1)
    private val other = UserFactory.aUser(id = 2)

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    @Test
    fun `a plain post moves the title and returns to reorder mode at its row`() {
        val (list, items) = f.list("Coco", "Up", "Moana")
        val attributes = RedirectAttributesModelMap()

        val view = controller.move(list.id!!.value, items[2].id!!.value, "1", null, owner, attributes)

        assertEquals("redirect:/list/${list.id!!.value}?reorder=1#order-item-${items[2].id!!.value}", view)
        assertEquals("Moved \"Moana\" to #1.", attributes.flashAttributes[MoveListItemController.NOTICE])
        assertEquals(listOf("Moana", "Coco", "Up"), f.titles(list))
    }

    @Test
    fun `the arrow buttons send to, which wins over the number field`() {
        val (list, items) = f.list("A", "B", "C")

        controller.move(list.id!!.value, items[0].id!!.value, "1", "down", owner, RedirectAttributesModelMap())
        assertEquals(listOf("B", "A", "C"), f.titles(list))
        controller.move(list.id!!.value, items[0].id!!.value, null, "bottom", owner, RedirectAttributesModelMap())
        assertEquals(listOf("B", "C", "A"), f.titles(list))
    }

    @Test
    fun `fetch gets the saved order as JSON`() {
        val (list, items) = f.list("A", "B", "C", "D")

        val response = controller.moveJson(list.id!!.value, items[3].id!!.value, "2", null, owner)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertTrue(body.ok)
        assertEquals("Moved \"D\" to #2.", body.message)
        assertEquals(2, body.position)
        assertEquals(4, body.total)
        assertEquals(listOf(items[0], items[3], items[1], items[2]).map { it.id!!.value }, body.order)
    }

    @Test
    fun `numbers are clamped, and the same place says so`() {
        val (list, items) = f.list("A", "B")

        assertEquals(2, controller.moveJson(list.id!!.value, items[0].id!!.value, "900", null, owner).body!!.position)
        assertEquals("\"A\" is already at #2.", controller.moveJson(list.id!!.value, items[0].id!!.value, "2", null, owner).body!!.message)
    }

    @Test
    fun `big places read without a thousands separator`() {
        assertEquals("Moved \"X\" to #1200.", messages.getMessage("list.order.moved", arrayOf<Any>("X", 1200), Locale.ENGLISH))
    }

    @Test
    fun `someone else, a bad number or a stale item get an error and nothing moves`() {
        val (list, items) = f.list("A", "B")
        val id = list.id!!.value

        val forbidden = controller.moveJson(id, items[1].id!!.value, "1", null, other)
        assertEquals(HttpStatus.FORBIDDEN, forbidden.statusCode)
        assertFalse(forbidden.body!!.ok)
        assertEquals("Only the list's owner can change its order.", forbidden.body!!.message)

        assertEquals(HttpStatus.BAD_REQUEST, controller.moveJson(id, items[1].id!!.value, "first!", null, owner).statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, controller.moveJson(id, items[1].id!!.value, null, "sideways", owner).statusCode)
        assertEquals(HttpStatus.NOT_FOUND, controller.moveJson(id, 999, "1", null, owner).statusCode)
        assertEquals(HttpStatus.NOT_FOUND, controller.moveJson(999, items[1].id!!.value, "1", null, owner).statusCode)

        val attributes = RedirectAttributesModelMap()
        controller.move(id, items[1].id!!.value, "", null, owner, attributes)
        assertEquals("list.order.error.position", attributes.flashAttributes["error"])
        assertEquals(listOf("A", "B"), f.titles(list))
    }
}
