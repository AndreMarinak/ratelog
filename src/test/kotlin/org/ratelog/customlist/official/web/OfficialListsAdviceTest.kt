package org.ratelog.customlist.official.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Role
import org.ratelog.customlist.official.OfficialListsFixture
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.WatchStatus
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.ILazyContextVariable

class OfficialListsAdviceTest {

    private val f = OfficialListsFixture()

    // --- /list/{id}: listOfficial ---

    private val listAdvice = ListOfficialModelAdvice(f.official)

    private fun listOfficial(user: org.ratelog.user.User?, listId: Long): ListOfficialView {
        val model = ExtendedModelMap()
        listAdvice.addListOfficial(user, listId, model)
        return model["listOfficial"] as ListOfficialView
    }

    @Test
    fun `the toggle is for admins and superadmins only`() {
        val list = f.list(f.admin, "AFI 100", isPublic = true)
        val mod = UserFactory.aUser(id = 50, role = Role.ADMIN)

        assertTrue(listOfficial(f.admin, list.id!!.value).canToggle)
        assertTrue(listOfficial(mod, list.id!!.value).canToggle)
        assertFalse(listOfficial(f.andre, list.id!!.value).canToggle)
        assertFalse(listOfficial(null, list.id!!.value).canToggle)
        assertEquals("/list/${list.id!!.value}/official", listOfficial(f.admin, list.id!!.value).toggleHref)
    }

    @Test
    fun `the badge state follows the mark, and the toggle sends the opposite`() {
        val list = f.list(f.admin, "AFI 100")
        assertFalse(listOfficial(f.andre, list.id!!.value).official)
        assertTrue(listOfficial(f.admin, list.id!!.value).nextValue)

        f.markOfficial(list)

        assertTrue(listOfficial(f.andre, list.id!!.value).official)
        assertFalse(listOfficial(f.admin, list.id!!.value).nextValue)
    }

    // --- /list/{id}: the back link ---

    private fun backAfter(view: String, official: Boolean): Any? {
        val mav = org.springframework.web.servlet.ModelAndView(view).apply {
            addObject("listOfficial", ListOfficialView(official = official, canToggle = false, toggleHref = "/list/1/official"))
            addObject("listBack", org.ratelog.customlist.progress.web.ListBackLink.profile(1, "admin"))
        }
        OfficialListBackLinkInterceptor().postHandle(
            org.springframework.mock.web.MockHttpServletRequest("GET", "/list/1"),
            org.springframework.mock.web.MockHttpServletResponse(),
            Any(),
            mav,
        )
        return mav.model["listBack"]
    }

    @Test
    fun `official lists go back to Lists, not to the owner's profile`() {
        assertEquals(org.ratelog.customlist.progress.web.ListBackLink.LISTS, backAfter("list-detail", official = true))
        assertEquals("/profile/1", (backAfter("list-detail", official = false) as org.ratelog.customlist.progress.web.ListBackLink).href)
        assertEquals("/profile/1", (backAfter("other-view", official = true) as org.ratelog.customlist.progress.web.ListBackLink).href)
    }

    // --- /discover: officialListsRow ---

    private val discoverAdvice = DiscoverOfficialListsAdvice(f.getOfficial)

    @Test
    fun `Discover gets a lazy row with the viewer's progress`() {
        val list = f.list(f.admin, "AFI 100", isPublic = true, titles = 0)
        val kane = f.p.movie("Citizen Kane")
        f.p.add(list, kane, f.p.movie("Vertigo"))
        f.markOfficial(list)
        f.p.status(f.andre.id!!, kane, WatchStatus.WATCHED)
        val model = ExtendedModelMap()

        discoverAdvice.addOfficialListsRow(f.andre, model)

        val lazy = model[DiscoverOfficialListsAdvice.MODEL_KEY] as ILazyContextVariable<*>
        val row = lazy.value as OfficialListsRowView
        assertEquals("discover-official-lists", row.id)
        assertEquals(listOf("AFI 100"), row.cards.map { it.name })
        assertEquals(1, row.cards.single().seenCount)
        assertTrue(row.cards.single().isOfficial)
    }

    @Test
    fun `Discover's row is null with no official lists, and absent when logged out`() {
        val model = ExtendedModelMap()
        discoverAdvice.addOfficialListsRow(f.andre, model)
        assertNull((model[DiscoverOfficialListsAdvice.MODEL_KEY] as ILazyContextVariable<*>).value)

        val anonymous = ExtendedModelMap()
        discoverAdvice.addOfficialListsRow(null, anonymous)
        assertFalse(anonymous.containsAttribute(DiscoverOfficialListsAdvice.MODEL_KEY))
    }
}
