package org.ratelog.customlist.createwithitem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.customlist.create.CreateListHandler
import org.ratelog.customlist.order.numbered.ListNumberedForm
import org.ratelog.customlist.order.numbered.NumberedField
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.UserFactory
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap

class CreateListWithItemControllerTest {

    private val lists = InMemoryCustomListRepository()
    private val numberedForm = mock<ListNumberedForm>()
    private val controller = CreateListWithItemController(CreateListHandler(lists), AddToListHandler(lists), numberedForm)
    private val user = UserFactory.aUser(id = 1)

    @Test
    fun `a list started from a title page holds the title and is saved as plain (no ranks)`() {
        val view = controller.createWithItem(user, "Rewatch soon", false, 42, "movie", RedirectAttributesModelMap())

        val created = lists.findByUserId(user.id!!).single()
        assertEquals("redirect:/list/${created.id!!.value}", view)
        assertEquals(1, created.items.size)
        // The marker without the checkbox is how the forms say "plain".
        verify(numberedForm).save(eq(created.id!!), eq(user.id!!), eq(null), eq(NumberedField.MARKER))
    }
}
