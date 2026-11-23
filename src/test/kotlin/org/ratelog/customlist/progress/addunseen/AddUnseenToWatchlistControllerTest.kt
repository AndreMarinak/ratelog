package org.ratelog.customlist.progress.addunseen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ratelog.customlist.progress.ListProgressFixture
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.WatchStatus
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap

class AddUnseenToWatchlistControllerTest {

    private val f = ListProgressFixture()
    private val controller = AddUnseenToWatchlistController(AddUnseenToWatchlistHandler(f.handler, f.progressRepository))
    private val owner = UserFactory.aUser(id = 1)
    private val viewer = UserFactory.aUser(id = 2)

    @Test
    fun `adds, flashes the count and returns to the same tab`() {
        val list = f.list(owner.id!!)
        val coco = f.movie("Coco")
        f.add(list, coco, f.movie("Up"))
        val flash = RedirectAttributesModelMap()

        val view = controller.addUnseen(list.id!!.value, "unseen", viewer, flash)

        assertEquals("redirect:/list/${list.id!!.value}?show=unseen", view)
        assertEquals(2, flash.flashAttributes["listWatchlistAdded"])
        assertEquals(WatchStatus.WANT_TO_WATCH, f.statusOf(viewer.id!!, coco))
    }

    @Test
    fun `nothing to add flashes a note instead of a zero count`() {
        val list = f.list(owner.id!!)
        val coco = f.movie("Coco")
        f.add(list, coco)
        f.status(viewer.id!!, coco, WatchStatus.WATCHING)
        val flash = RedirectAttributesModelMap()

        val view = controller.addUnseen(list.id!!.value, null, viewer, flash)

        assertEquals("redirect:/list/${list.id!!.value}", view)
        assertNull(flash.flashAttributes["listWatchlistAdded"])
        assertEquals("list.progress.add.none", flash.flashAttributes["success"])
        assertEquals(WatchStatus.WATCHING, f.statusOf(viewer.id!!, coco))
    }

    @Test
    fun `the show parameter is re-parsed, never echoed into the redirect`() {
        val list = f.list(owner.id!!)

        assertEquals(
            "redirect:/list/${list.id!!.value}",
            controller.addUnseen(list.id!!.value, "//evil.example", viewer, RedirectAttributesModelMap()),
        )
        assertEquals(
            "redirect:/list/${list.id!!.value}?show=seen",
            controller.addUnseen(list.id!!.value, "seen", viewer, RedirectAttributesModelMap()),
        )
    }

    @Test
    fun `a private list of someone else flashes an error`() {
        val list = f.list(owner.id!!, isPublic = false)
        val coco = f.movie("Coco")
        f.add(list, coco)
        val flash = RedirectAttributesModelMap()

        controller.addUnseen(list.id!!.value, null, viewer, flash)

        assertEquals("list.not.authorized", flash.flashAttributes["error"])
        assertNull(f.statusOf(viewer.id!!, coco))
    }
}
