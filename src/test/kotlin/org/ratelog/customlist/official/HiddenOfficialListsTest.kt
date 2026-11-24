package org.ratelog.customlist.official

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.customlist.browse.GetListsPage
import org.ratelog.customlist.browse.ListCardView
import org.ratelog.customlist.browse.ListsPageController
import org.ratelog.customlist.getlistdetail.ListDetail
import org.ratelog.customlist.official.web.DiscoverOfficialListsAdvice
import org.ratelog.customlist.official.web.ListOfficialView
import org.ratelog.customlist.official.web.OfficialListVisibilityInterceptor
import org.ratelog.customlist.update.UpdateListCommand
import org.ratelog.customlist.update.UpdateListHandler
import org.ratelog.user.User
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.ModelAndView
import org.thymeleaf.context.ILazyContextVariable

/**
 * Official lists can be private (user request 2026-11-23: "admin account should be able to make official lists not
 * public"). A private official list is hidden: its owner sees it under My lists ("Official · Hidden") and nobody
 * else sees it anywhere, until it is made public again. Upstream's edit form is the switch.
 */
class HiddenOfficialListsTest {

    private val f = OfficialListsFixture()
    private val editForm = UpdateListHandler(f.lists)
    private val listsController = ListsPageController(f.listsPage, f.p.handler)
    private val discoverAdvice = DiscoverOfficialListsAdvice(f.getOfficial)

    init {
        // andre follows the admin, so a leak into "From people you follow" would show up too.
        f.users.toggleFollow(f.andre.id!!, f.admin.id!!)
    }

    private fun setPublic(list: org.ratelog.customlist.CustomList, isPublic: Boolean) =
        editForm.handle(UpdateListCommand(list.id!!, f.admin.id!!, list.name.value, isPublic = isPublic))

    private fun everywhereFor(user: User): List<String> {
        val page = f.listsPage.handle(GetListsPage(user.id!!))
        return (page.official + page.followed + page.others).map { it.name } + f.getOfficial.handle(user.id).map { it.list.name }
    }

    @Suppress("UNCHECKED_CAST")
    private fun myCards(user: User): List<ListCardView> {
        val model = ExtendedModelMap()
        listsController.listsPage(user, null, model)
        return model["myLists"] as List<ListCardView>
    }

    private fun discoverRowFor(user: User): Any? {
        val model = ExtendedModelMap()
        discoverAdvice.addOfficialListsRow(user, null, model)
        return (model[DiscoverOfficialListsAdvice.MODEL_KEY] as ILazyContextVariable<*>).value
    }

    @Test
    fun `the owner's edit form makes an official list private, and it stays official`() {
        val list = f.list(f.admin, "AFI 100", isPublic = true)
        f.markOfficial(list)

        val result = setPublic(list, isPublic = false)

        assertTrue(result.isRight())
        assertFalse(f.reload(list)!!.isPublic, "unchecking Public works now (it used to be ignored)")
        assertTrue(f.official.isOfficial(list.id!!), "still official, just hidden")
    }

    @Test
    fun `a hidden official list disappears for everyone else, everywhere, and comes back when public`() {
        val list = f.list(f.admin, "AFI 100", isPublic = true)
        f.markOfficial(list)
        assertEquals(listOf("AFI 100", "AFI 100"), everywhereFor(f.andre), "Lists tab row and /lists/official (= API, Discover)")
        assertTrue(discoverRowFor(f.andre) != null)

        setPublic(list, isPublic = false)

        assertTrue(everywhereFor(f.andre).isEmpty(), "not in Official, not under followed, not under others")
        assertNull(discoverRowFor(f.andre), "no Discover row")
        assertTrue(everywhereFor(f.admin).isEmpty(), "the Official sections only show public lists, to the owner too")

        setPublic(list, isPublic = true)

        assertEquals(listOf("AFI 100", "AFI 100"), everywhereFor(f.andre))
        assertTrue(discoverRowFor(f.andre) != null)
    }

    @Test
    fun `the owner still has it under My lists, badged hidden`() {
        val hidden = f.list(f.admin, "Drafts")
        val shown = f.list(f.admin, "AFI 100", isPublic = true, createdAt = 2_000)
        f.markOfficial(hidden)
        f.markOfficial(shown)

        val cards = myCards(f.admin).associateBy { it.name }

        assertTrue(cards.getValue("Drafts").isOfficial)
        assertTrue(cards.getValue("Drafts").isHiddenOfficial)
        assertFalse(cards.getValue("AFI 100").isHiddenOfficial)
        assertTrue(myCards(f.andre).isEmpty())
    }

    @Test
    fun `an ordinary private list is not a hidden official one`() {
        f.list(f.admin, "Private")

        val card = myCards(f.admin).single()

        assertFalse(card.isOfficial)
        assertFalse(card.isHiddenOfficial)
    }

    // --- /list/{id}: the header learns the list's visibility after the controller ran ---

    private fun headerAfter(view: String, official: Boolean, listPublic: Boolean?): ListOfficialView {
        val mav = ModelAndView(view).apply {
            addObject("listOfficial", ListOfficialView(official = official, canToggle = true, toggleHref = "/list/1/official"))
            if (listPublic != null) addObject("list", ListDetail(org.ratelog.customlist.CustomList.Id(1), f.admin.id!!, "AFI 100", listPublic, 0, emptyList()))
        }
        OfficialListVisibilityInterceptor().postHandle(MockHttpServletRequest("GET", "/list/1"), MockHttpServletResponse(), Any(), mav)
        return mav.model["listOfficial"] as ListOfficialView
    }

    @Test
    fun `the list header knows a private official list is hidden`() {
        assertTrue(headerAfter("list-detail", official = true, listPublic = false).hidden)
        assertFalse(headerAfter("list-detail", official = true, listPublic = true).hidden)
        assertFalse(headerAfter("list-detail", official = false, listPublic = false).hidden, "not official, nothing to hide")
        assertFalse(headerAfter("list-detail", official = false, listPublic = false).listPublic, "the mark confirmation still knows it's private")
        assertTrue(headerAfter("list-detail", official = true, listPublic = null).listPublic, "no list shown (error page): left alone")
        assertTrue(headerAfter("other-view", official = true, listPublic = false).listPublic, "other views are left alone")
    }
}
