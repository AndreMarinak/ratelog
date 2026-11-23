package org.ratelog.customlist.official.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.customlist.browse.GetListsPage
import org.ratelog.customlist.browse.GetListsPageHandler
import org.ratelog.customlist.browse.ListCardView
import org.ratelog.customlist.browse.ListsPageController
import org.ratelog.customlist.official.OfficialListsFixture
import org.ratelog.customlist.official.web.OfficialListsRowView
import org.ratelog.watchlist.WatchStatus
import org.springframework.ui.ExtendedModelMap

/** The Lists tab with official lists: their own section first, never repeated under "followed" or "others". */
class OfficialListsSectionsTest {

    private val f = OfficialListsFixture()
    private val friend = f.users.save(org.ratelog.test.UserFactory.aUser(username = "friend"))
    private val stranger = f.users.save(org.ratelog.test.UserFactory.aUser(username = "stranger"))

    init {
        f.users.toggleFollow(f.andre.id!!, friend.id!!)
        f.users.toggleFollow(f.andre.id!!, f.admin.id!!)
    }

    private fun pageFor(user: org.ratelog.user.User, limit: Int = GetListsPageHandler.PAGE_SIZE) = f.listsPage.handle(GetListsPage(user.id!!, limit))

    @Test
    fun `official lists get their own section and appear in no other section`() {
        val adminOfficial = f.list(f.admin, "AFI 100", createdAt = 10_000)
        val friendOfficial = f.list(friend, "Friend's canon", isPublic = true, createdAt = 11_000)
        val strangerOfficial = f.list(stranger, "Stranger's canon", isPublic = true, createdAt = 12_000)
        f.list(f.admin, "Admin public", isPublic = true, createdAt = 13_000)
        f.list(friend, "Friend public", isPublic = true, createdAt = 14_000)
        f.list(stranger, "Stranger public", isPublic = true, createdAt = 15_000)
        listOf(adminOfficial, friendOfficial, strangerOfficial).forEach { f.markOfficial(it) }

        val page = pageFor(f.andre)

        assertEquals(listOf("Stranger's canon", "Friend's canon", "AFI 100"), page.official.map { it.name }, "newest activity first")
        assertEquals(listOf("Friend public", "Admin public"), page.followed.map { it.name })
        assertEquals(listOf("Stranger public"), page.others.map { it.name })
        val everywhere = (page.official + page.mine + page.followed + page.others).map { it.id }
        assertEquals(everywhere.distinct(), everywhere, "no list appears twice")
        assertTrue(page.official.all { it.isOfficial && it.isPublic })
        assertTrue((page.followed + page.others).none { it.isOfficial })
    }

    @Test
    fun `a list made official moves out of followed and others, and back when un-marked`() {
        val list = f.list(stranger, "Moving", isPublic = true)
        assertEquals(listOf("Moving"), pageFor(f.andre).others.map { it.name })

        f.markOfficial(list)
        assertEquals(listOf("Moving"), pageFor(f.andre).official.map { it.name })
        assertTrue(pageFor(f.andre).others.isEmpty())

        f.setOfficial.handle(org.ratelog.customlist.official.mark.SetListOfficial(list.id!!, f.admin.id!!, official = false))
        assertTrue(pageFor(f.andre).official.isEmpty())
        assertEquals(listOf("Moving"), pageFor(f.andre).others.map { it.name })
    }

    @Test
    fun `the owner still sees their official lists under My lists`() {
        val list = f.list(f.admin, "AFI 100")
        f.markOfficial(list)

        val page = pageFor(f.admin)

        assertEquals(listOf("AFI 100"), page.official.map { it.name })
        assertEquals(listOf("AFI 100"), page.mine.map { it.name })
        assertTrue(page.mine.single().isOfficial)
    }

    @Test
    fun `empty official lists are shown too, unlike empty public lists`() {
        f.markOfficial(f.list(f.admin, "Coming soon", titles = 0))
        f.list(stranger, "Empty public", isPublic = true, titles = 0)

        val page = pageFor(f.andre)

        assertEquals(listOf("Coming soon"), page.official.map { it.name })
        assertTrue(page.others.isEmpty())
    }

    @Test
    fun `official lists don't use up the others page`() {
        repeat(GetListsPageHandler.PAGE_SIZE) { f.list(stranger, "Public $it", isPublic = true, createdAt = 10_000L + it) }
        f.markOfficial(f.list(stranger, "Official", isPublic = true, createdAt = 50_000))

        val page = pageFor(f.andre)

        assertEquals(GetListsPageHandler.PAGE_SIZE, page.others.size)
        assertFalse(page.hasMoreOthers, "the official list isn't counted as one more public list")
    }

    // --- Controller: the row at the top of the page ---

    private val controller = ListsPageController(f.listsPage, f.p.handler)

    @Suppress("UNCHECKED_CAST")
    private fun cards(model: ExtendedModelMap, key: String) = model[key] as List<ListCardView>

    @Test
    fun `the row shows official cards with the viewer's own progress and no owner`() {
        val list = f.list(f.admin, "AFI 100", titles = 0)
        val (citizenKane, casablanca, vertigo) = listOf("Citizen Kane", "Casablanca", "Vertigo").map { f.p.movie(it) }
        f.p.add(list, citizenKane, casablanca, vertigo)
        f.markOfficial(list)
        f.p.status(f.andre.id!!, citizenKane, WatchStatus.WATCHED)
        f.p.rate(f.andre.id!!, vertigo, 9.0)
        f.p.status(f.admin.id!!, casablanca, WatchStatus.WATCHED)
        val model = ExtendedModelMap()

        controller.listsPage(f.andre, null, model)

        val row = model["officialLists"] as OfficialListsRowView
        assertEquals("official-lists", row.id)
        assertEquals("/lists/official", row.seeAllHref)
        assertEquals(1, row.total)
        val card = row.cards.single()
        assertTrue(card.isOfficial)
        assertNull(card.ownerUsername, "presented as the site's list")
        assertEquals(2, card.seenCount, "andre's progress, not the admin's")
        assertEquals(3, card.itemCount)
        assertEquals("/list/${list.id!!.value}", card.href)
    }

    @Test
    fun `no official lists means no row at all`() {
        f.list(f.admin, "Private")
        val model = ExtendedModelMap()

        controller.listsPage(f.andre, null, model)

        assertTrue(model.containsAttribute("officialLists"))
        assertNull(model["officialLists"])
    }

    @Test
    fun `the row holds at most the row limit, the total says how many there are`() {
        repeat(OfficialListsRowView.ROW_LIMIT + 3) { f.markOfficial(f.list(f.admin, "Official $it", titles = 1, createdAt = 10_000L + it)) }
        val model = ExtendedModelMap()

        controller.listsPage(f.andre, null, model)

        val row = model["officialLists"] as OfficialListsRowView
        assertEquals(OfficialListsRowView.ROW_LIMIT, row.cards.size)
        assertEquals(OfficialListsRowView.ROW_LIMIT + 3, row.total)
        assertEquals("Official ${OfficialListsRowView.ROW_LIMIT + 2}", row.cards.first().name)
    }

    @Test
    fun `the admin's own official list is badged under My lists too`() {
        f.markOfficial(f.list(f.admin, "AFI 100"))
        val model = ExtendedModelMap()

        controller.listsPage(f.admin, null, model)

        assertTrue(cards(model, "myLists").single().isOfficial)
        assertTrue(cards(model, "otherLists").isEmpty())
    }

    @Test
    fun `the See all page lists every official list with progress`() {
        val list = f.list(f.admin, "AFI 100", titles = 0)
        val kane = f.p.movie("Citizen Kane")
        f.p.add(list, kane, f.p.movie("Casablanca"))
        f.markOfficial(list)
        f.p.status(f.andre.id!!, kane, WatchStatus.WATCHED)
        f.list(f.admin, "Not official", isPublic = true)
        val model = ExtendedModelMap()

        val view = OfficialListsPageController(f.getOfficial).officialLists(f.andre, model)

        assertEquals("lists-official", view)
        val all = cards(model, "officialCards")
        assertEquals(listOf("AFI 100"), all.map { it.name })
        assertEquals(1, all.single().seenCount)
        assertTrue(all.single().isOfficial)
    }
}
