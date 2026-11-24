package org.ratelog.customlist.saved

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.browse.GetListsPage
import org.ratelog.customlist.browse.ListCardView
import org.ratelog.customlist.browse.ListsPageController
import org.ratelog.customlist.official.OfficialListsFixture
import org.ratelog.customlist.official.browse.OfficialListsPageController
import org.ratelog.customlist.official.web.DiscoverOfficialListsAdvice
import org.ratelog.customlist.official.web.OfficialListsRowView
import org.ratelog.customlist.saved.browse.GetSavedListsHandler
import org.ratelog.customlist.saved.set.SetListSaved
import org.ratelog.customlist.saved.set.SetListSavedError
import org.ratelog.customlist.saved.set.SetListSavedHandler
import org.ratelog.customlist.update.UpdateListCommand
import org.ratelog.customlist.update.UpdateListHandler
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.ILazyContextVariable

/**
 * Saved lists (user request 2026-11-24): save an official list, it comes first in every Official lists section and shows
 * under your own lists; hidden or un-marked lists keep their row but are shown nowhere.
 */
class SavedListsTest {

    private val f = OfficialListsFixture()
    private val set = SetListSavedHandler(f.lists, f.official, f.saved, f.clock)
    private val savedLists = GetSavedListsHandler(f.saved, f.summaries, f.p.handler)
    private val listsController = ListsPageController(f.listsPage, f.p.handler)
    private val discoverAdvice = DiscoverOfficialListsAdvice(f.getOfficial)

    private fun setClock(ms: Long) {
        f.clock.now = java.time.Instant.ofEpochMilli(ms)
    }

    private fun save(list: CustomList, user: User = f.andre, saved: Boolean = true) =
        set.handle(SetListSaved(list.id!!, user.id!!, saved))

    private fun saveAt(list: CustomList, atMs: Long, user: User = f.andre) {
        setClock(atMs)
        assertTrue(save(list, user).isRight())
    }

    /** Four public official lists; their own order is newest activity first: D, C, B, A. */
    private fun fourOfficial(): List<CustomList> =
        listOf("A", "B", "C", "D").mapIndexed { i, name ->
            f.list(f.admin, name, isPublic = true, createdAt = 1_000L + i).also { f.markOfficial(it) }
        }

    @Suppress("UNCHECKED_CAST")
    private fun listsModel(user: User = f.andre): ExtendedModelMap = ExtendedModelMap().also { listsController.listsPage(user, null, it) }

    private fun officialRowNames(user: User = f.andre) =
        (listsModel(user)["officialLists"] as OfficialListsRowView).cards.map { it.name }

    @Suppress("UNCHECKED_CAST")
    private fun myCards(user: User = f.andre) = listsModel(user)["myLists"] as List<ListCardView>

    // --- Saving ---

    @Test
    fun `a plain user saves a public official list, once`() {
        val (a) = fourOfficial()
        setClock(5_000)

        val first = save(a).getOrNull()!!
        setClock(9_000)
        val again = save(a).getOrNull()!!

        assertTrue(first.saved && first.changed)
        assertTrue(again.saved && !again.changed, "saving twice changes nothing")
        assertEquals(listOf(SavedList(a.id!!, 5_000)), f.saved.findByUser(f.andre.id!!), "the first save time is kept")
    }

    @Test
    fun `owners and admins can save too`() {
        val (a) = fourOfficial()
        assertTrue(save(a, user = f.admin).isRight())
        assertTrue(f.saved.isSaved(f.admin.id!!, a.id!!))
    }

    @Test
    fun `only official public lists can be saved`() {
        val ordinary = f.list(f.admin, "Ordinary", isPublic = true)
        val hidden = f.list(f.admin, "Hidden").also { f.markOfficial(it) }
        val mine = f.list(f.andre, "Mine", isPublic = true)

        assertEquals(SetListSavedError.NotSaveable, save(ordinary).leftOrNull())
        assertEquals(SetListSavedError.NotSaveable, save(mine).leftOrNull(), "not even your own ordinary list")
        assertEquals(SetListSavedError.ListNotFound, save(hidden).leftOrNull(), "someone else's private list is missing")
        assertEquals(SetListSavedError.NotSaveable, save(hidden, user = f.admin).leftOrNull(), "a hidden official list is its owner's draft")
        assertEquals(SetListSavedError.ListNotFound, set.handle(SetListSaved(CustomList.Id(999), f.andre.id!!, true)).leftOrNull())
        assertTrue(f.saved.findByUser(f.andre.id!!).isEmpty())
    }

    @Test
    fun `un-saving works whatever the list is now, and twice is harmless`() {
        val (a, b) = fourOfficial()
        save(a)
        save(b)
        f.setOfficial.handle(org.ratelog.customlist.official.mark.SetListOfficial(a.id!!, f.admin.id!!, official = false))
        UpdateListHandler(f.lists).handle(UpdateListCommand(a.id!!, f.admin.id!!, "A", isPublic = false))

        val off = save(a, saved = false).getOrNull()!!
        assertTrue(!off.saved && off.changed, "a saved list that became private can still be taken off")
        assertEquals(SetListSavedError.ListNotFound, save(a, saved = false).leftOrNull(), "after that, a private list of someone else is missing again")

        assertTrue(save(b, saved = false).getOrNull()!!.changed)
        assertFalse(save(b, saved = false).getOrNull()!!.changed, "un-saving a public list twice changes nothing")
    }

    @Test
    fun `deleting the list deletes the save`() {
        val (a) = fourOfficial()
        save(a)
        f.lists.delete(a.id!!)
        assertTrue(f.saved.findByUser(f.andre.id!!).isEmpty())
    }

    // --- Order: saved first, newest save first, then the section's own order ---

    @Test
    fun `saved lists lead the Lists tab row, newest save first`() {
        val (a, b) = fourOfficial()
        assertEquals(listOf("D", "C", "B", "A"), officialRowNames())

        saveAt(a, 5_000)
        saveAt(b, 6_000)

        assertEquals(listOf("B", "A", "D", "C"), officialRowNames())
        assertEquals(listOf("D", "C", "B", "A"), officialRowNames(f.admin), "per user: the admin saved nothing")
        val cards = (listsModel()["officialLists"] as OfficialListsRowView).cards.associateBy { it.name }
        assertEquals(true, cards.getValue("B").saved)
        assertEquals(false, cards.getValue("D").saved, "every official card has a toggle")
        assertEquals(listOf(2, 3, 0, 1), listOf("B", "A", "D", "C").map { cards.getValue(it).baseOrder }, "base order = place before saving")
    }

    @Test
    fun `saved lists lead slash-lists-official, the Discover row and the API order`() {
        val (a, _, c) = fourOfficial()
        saveAt(c, 5_000)
        saveAt(a, 6_000)

        assertEquals(listOf("A", "C", "D", "B"), f.getOfficial.handle(f.andre.id!!).map { it.list.name })
        assertEquals(listOf(true, true, false, false), f.getOfficial.handle(f.andre.id!!).map { it.saved })

        val page = ExtendedModelMap()
        OfficialListsPageController(f.getOfficial).officialLists(f.andre, null, page)
        @Suppress("UNCHECKED_CAST")
        val grid = page["officialCards"] as List<ListCardView>
        assertEquals(listOf("A", "C", "D", "B"), grid.map { it.name })
        assertEquals(listOf(true, true, false, false), grid.map { it.saved })

        val model = ExtendedModelMap()
        discoverAdvice.addOfficialListsRow(f.andre, null, model)
        val row = (model[DiscoverOfficialListsAdvice.MODEL_KEY] as ILazyContextVariable<*>).value as OfficialListsRowView
        assertEquals(listOf("A", "C", "D", "B"), row.cards.map { it.name })
    }

    @Test
    fun `the Discover row still follows Movies and TV`() {
        val movies = f.list(f.admin, "Movies", isPublic = true).also { f.markOfficial(it) }
        val tv = f.p.lists.save(CustomList(null, f.admin.id!!, org.ratelog.ListName("TV"), true, createdAtEpochMs = 3_000)).also {
            f.p.add(it, f.p.show("Lost"))
            f.markOfficial(it)
        }
        saveAt(tv, 5_000)

        assertEquals(listOf("Movies"), f.getOfficial.handle(f.andre.id!!, containing = MediaType.movie).map { it.list.name })
        assertEquals(listOf("TV"), f.getOfficial.handle(f.andre.id!!, containing = MediaType.tvshow).map { it.list.name })
        assertTrue(movies.id != null)
    }

    // --- "Your lists": own lists, then saved ones with badge, progress and toggle ---

    @Test
    fun `My lists shows your lists, then the saved ones with progress`() {
        val (a, b) = fourOfficial()
        f.list(f.andre, "Mine", createdAt = 500)
        val first = f.reload(a)!!.items.first()
        f.p.status(f.andre.id!!, f.p.movies.findById(org.ratelog.movie.Movie.Id(first.mediaId))!!, WatchStatus.WATCHED)
        saveAt(a, 5_000)
        saveAt(b, 6_000)

        val cards = myCards()

        assertEquals(listOf("Mine", "B", "A"), cards.map { it.name })
        assertNull(cards[0].saved, "your own list has no Save toggle")
        assertTrue(cards[1].isOfficial && cards[1].saved == true)
        assertEquals(1, cards[2].seenCount, "your progress, as on other cards")
        assertNull(cards[2].ownerUsername, "official cards never show the owner")
    }

    @Test
    fun `an admin's own saved official list is not listed twice`() {
        val (a) = fourOfficial()
        save(a, user = f.admin)

        val names = myCards(f.admin).map { it.name }

        assertEquals(1, names.count { it == "A" })
        assertEquals("A", officialRowNames(f.admin).first(), "still first in the Official row")
    }

    @Test
    fun `a saved list that is hidden or no longer official is shown nowhere, and comes back`() {
        val (a) = fourOfficial()
        saveAt(a, 5_000)
        val edit = UpdateListHandler(f.lists)

        edit.handle(UpdateListCommand(a.id!!, f.admin.id!!, "A", isPublic = false))

        assertFalse("A" in officialRowNames())
        assertFalse("A" in myCards().map { it.name })
        assertTrue(savedLists.handle(f.andre.id!!).isEmpty())
        assertTrue(f.saved.isSaved(f.andre.id!!, a.id!!), "the row stays")

        edit.handle(UpdateListCommand(a.id!!, f.admin.id!!, "A", isPublic = true))
        assertEquals("A", officialRowNames().first(), "visible again: first again")

        f.setOfficial.handle(org.ratelog.customlist.official.mark.SetListOfficial(a.id!!, f.admin.id!!, official = false))
        assertFalse("A" in myCards().map { it.name }, "un-marked: an ordinary public list now, not a saved one")
        assertTrue(savedLists.handle(f.andre.id!!).isEmpty())
    }

    @Test
    fun `GetSavedListsHandler gives the visible saved lists with progress, newest save first`() {
        val (a, b) = fourOfficial()
        saveAt(a, 5_000)
        saveAt(b, 6_000)
        save(a, user = f.admin)

        assertEquals(listOf("B", "A"), savedLists.handle(f.andre.id!!).map { it.list.name })
        assertEquals(listOf(6_000L, 5_000L), savedLists.handle(f.andre.id!!).map { it.savedAtEpochMs })
        assertEquals(listOf("A"), savedLists.handle(f.admin.id!!).map { it.list.name }, "the API lists your own saved lists")
        assertTrue(savedLists.handle(f.admin.id!!, includeOwn = false).isEmpty(), "pages leave them out")
    }

    @Test
    fun `the Lists page reads saved lists once and adds nothing for people who saved none`() {
        fourOfficial()
        val page = f.listsPage.handle(GetListsPage(f.andre.id!!))
        assertTrue(page.saved.isEmpty())
        assertEquals(listOf("D", "C", "B", "A"), page.official.map { it.name })
    }

    // --- SavedFirst itself ---

    @Test
    fun `SavedFirst keeps the rest in their order and ignores saves of lists not in the section`() {
        val ids = (1L..5L).map(CustomList::Id)
        val saved = listOf(SavedList(ids[3], 20), SavedList(CustomList.Id(99), 30), SavedList(ids[1], 10))

        assertEquals(listOf(4L, 2L, 1L, 3L, 5L), SavedFirst.order(ids, saved) { it }.map { it.value })
        assertEquals(ids, SavedFirst.order(ids, emptyList()) { it })
    }
}
