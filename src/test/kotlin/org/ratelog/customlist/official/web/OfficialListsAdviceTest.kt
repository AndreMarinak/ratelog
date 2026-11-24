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

        discoverAdvice.addOfficialListsRow(f.andre, null, model)

        val lazy = model[DiscoverOfficialListsAdvice.MODEL_KEY] as ILazyContextVariable<*>
        val row = lazy.value as OfficialListsRowView
        assertEquals("discover-official-lists", row.id)
        assertEquals("/lists/official?type=movie", row.seeAllHref, "Discover opens on Movies")
        assertEquals(listOf("AFI 100"), row.cards.map { it.name })
        assertEquals(1, row.cards.single().seenCount)
        assertTrue(row.cards.single().isOfficial)
    }

    @Test
    fun `Discover's row is null with no official lists, and absent when logged out`() {
        val model = ExtendedModelMap()
        discoverAdvice.addOfficialListsRow(f.andre, null, model)
        assertNull((model[DiscoverOfficialListsAdvice.MODEL_KEY] as ILazyContextVariable<*>).value)

        val anonymous = ExtendedModelMap()
        discoverAdvice.addOfficialListsRow(null, null, anonymous)
        assertFalse(anonymous.containsAttribute(DiscoverOfficialListsAdvice.MODEL_KEY))
    }

    // --- /discover: the row follows Movies / TV (user, 2026-11-23: TV mode shows "only lists with TV") ---

    private fun discoverRow(type: String?): OfficialListsRowView? {
        val model = ExtendedModelMap()
        discoverAdvice.addOfficialListsRow(f.andre, type, model)
        return (model[DiscoverOfficialListsAdvice.MODEL_KEY] as ILazyContextVariable<*>).value as OfficialListsRowView?
    }

    @Test
    fun `Movies shows lists with a movie, TV lists with a show, and a mixed list shows in both`() {
        val films = f.list(f.admin, "AFI 100", isPublic = true, titles = 0, createdAt = 3_000L).also { f.p.add(it, f.p.movie("Vertigo")) }
        val series = f.list(f.admin, "Best TV Shows of All Time", isPublic = true, titles = 0, createdAt = 2_000L)
            .also { f.p.add(it, f.p.show("The Wire"), f.p.show("The Sopranos")) }
        val mixed = f.list(f.admin, "Mixed", isPublic = true, titles = 0, createdAt = 1_000L)
            .also { f.p.add(it, f.p.movie("Heat"), f.p.show("Twin Peaks")) }
        listOf(films, series, mixed).forEach { f.markOfficial(it) }
        val names = { row: OfficialListsRowView? -> row!!.cards.map { it.name }.toSet() }

        assertEquals(setOf("AFI 100", "Mixed"), names(discoverRow(null)), "no type = Movies, Discover's default")
        assertEquals(setOf("AFI 100", "Mixed"), names(discoverRow("movie")))
        assertEquals(setOf("Best TV Shows of All Time", "Mixed"), names(discoverRow("tv")))
        assertEquals(setOf("Best TV Shows of All Time", "Mixed"), names(discoverRow("shows")), "Discover's aliases")
        assertEquals(setOf("AFI 100", "Mixed"), names(discoverRow("nonsense")), "an unknown type is ignored, as on Discover")
        assertEquals(2, discoverRow("tv")!!.total)
        assertEquals("/lists/official?type=tv", discoverRow("tv")!!.seeAllHref)

        // "See all" opens the same subset; /lists/official alone still lists every official list.
        fun page(type: String?) = ExtendedModelMap().also {
            org.ratelog.customlist.official.browse.OfficialListsPageController(f.getOfficial).officialLists(f.andre, type, it)
        }["officialCards"].let { cards -> (cards as List<*>).map { (it as org.ratelog.customlist.browse.ListCardView).name }.toSet() }
        assertEquals(setOf("Best TV Shows of All Time", "Mixed"), page("tv"))
        assertEquals(setOf("AFI 100", "Mixed"), page("movie"))
        assertEquals(setOf("AFI 100", "Best TV Shows of All Time", "Mixed"), page(null))
    }

    @Test
    fun `no official list of the mode's type, no row`() {
        f.markOfficial(f.list(f.admin, "AFI 100", isPublic = true))
        f.markOfficial(f.list(f.admin, "Empty", isPublic = true, titles = 0))

        assertEquals(listOf("AFI 100"), discoverRow(null)!!.cards.map { it.name }, "an empty list has no type, so it shows in neither")
        assertNull(discoverRow("tv"))
    }

    @Test
    fun `a hidden (private) official TV list stays out of TV mode`() {
        val draft = f.list(f.admin, "Draft TV", isPublic = false, titles = 0).also { f.p.add(it, f.p.show("The Wire")) }
        f.markOfficial(draft)

        assertNull(discoverRow("tv"))
    }
}
