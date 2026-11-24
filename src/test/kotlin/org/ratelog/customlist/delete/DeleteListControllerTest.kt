package org.ratelog.customlist.delete

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.ListName
import org.ratelog.customlist.CustomList
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.UserFactory
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap

/** Fork (2026-11-24): deleting a list lands on the Lists tab, never the profile (which looks like Community). */
class DeleteListControllerTest {

    private val lists = InMemoryCustomListRepository()
    private val controller = DeleteListController(DeleteListHandler(lists))
    private val owner = UserFactory.aUser(id = 1)

    private fun aList() = lists.save(CustomList(id = null, userId = owner.id!!, name = ListName("Rewatch"), isPublic = false, createdAtEpochMs = 1L))

    @Test
    fun `deleting a list goes to the Lists tab and says so`() {
        val list = aList()
        val flash = RedirectAttributesModelMap()

        val view = controller.deleteList(list.id!!.value, owner, flash)

        assertEquals("redirect:/lists", view)
        assertEquals("list.deleted", flash.flashAttributes["success"])
        assertTrue(lists.findByUserId(owner.id!!).isEmpty())
    }

    @Test
    fun `someone else's list stays, and you land on the Lists tab with the error`() {
        val list = aList()
        val flash = RedirectAttributesModelMap()

        val view = controller.deleteList(list.id!!.value, UserFactory.aUser(id = 2), flash)

        assertEquals("redirect:/lists", view)
        assertEquals("list.error.not.owner", flash.flashAttributes["error"])
        assertEquals(1, lists.findByUserId(owner.id!!).size)
    }
}
