package org.ratelog.customlist.official.mark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.customlist.official.OfficialListsFixture
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap

/** The admin-only "Official" toggle on /list/{id}: always back to the list, with a flash message. */
class SetListOfficialControllerTest {

    private val f = OfficialListsFixture()
    private val controller = SetListOfficialController(f.setOfficial)

    @Test
    fun `an admin marks a list and comes back to it with a confirmation`() {
        val list = f.list(f.admin, "AFI 100", isPublic = false)
        val flash = RedirectAttributesModelMap()

        val view = controller.setOfficial(list.id!!.value, official = true, user = f.admin, redirectAttributes = flash)

        assertEquals("redirect:/list/${list.id!!.value}", view)
        assertEquals("list.official.marked", flash.flashAttributes["success"])
        assertTrue(f.official.isOfficial(list.id!!))
        assertTrue(f.reload(list)!!.isPublic)
    }

    @Test
    fun `an admin un-marks a list`() {
        val list = f.list(f.admin, "AFI 100")
        f.markOfficial(list)
        val flash = RedirectAttributesModelMap()

        controller.setOfficial(list.id!!.value, official = false, user = f.admin, redirectAttributes = flash)

        assertEquals("list.official.unmarked", flash.flashAttributes["success"])
        assertFalse(f.official.isOfficial(list.id!!))
    }

    @Test
    fun `a regular user's post is refused and changes nothing`() {
        val own = f.list(f.andre, "Mine", isPublic = false)
        val flash = RedirectAttributesModelMap()

        val view = controller.setOfficial(own.id!!.value, official = true, user = f.andre, redirectAttributes = flash)

        assertEquals("redirect:/list/${own.id!!.value}", view)
        assertEquals("list.official.error.forbidden", flash.flashAttributes["error"])
        assertNull(flash.flashAttributes["success"])
        assertFalse(f.official.isOfficial(own.id!!))
        assertFalse(f.reload(own)!!.isPublic)
    }

    @Test
    fun `a missing list flashes not found`() {
        val flash = RedirectAttributesModelMap()

        controller.setOfficial(9_999, official = true, user = f.admin, redirectAttributes = flash)

        assertEquals("list.not.found", flash.flashAttributes["error"])
    }
}
