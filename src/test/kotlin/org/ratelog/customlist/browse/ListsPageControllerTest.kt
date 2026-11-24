package org.ratelog.customlist.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ratelog.customlist.progress.ListProgressFixture
import org.ratelog.test.InMemoryListSummaryRepository
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.WatchStatus
import org.springframework.ui.ExtendedModelMap

/** The "9/25 seen" on /lists cards: the viewer's own progress, read in one query for the whole page. */
class ListsPageControllerTest {

    private val f = ListProgressFixture()
    private val users = InMemoryUserRepository()
    private val me = users.save(UserFactory.aUser(username = "me"))
    private val friend = users.save(UserFactory.aUser(username = "friend"))
    private val controller = ListsPageController(
        GetListsPageHandler(InMemoryListSummaryRepository(f.lists, users, f.movies, f.shows), users, org.ratelog.test.InMemorySavedListRepository()),
        f.handler,
    )

    @Suppress("UNCHECKED_CAST")
    private fun cards(model: ExtendedModelMap, key: String) = model[key] as List<ListCardView>

    @Test
    fun `cards show how many titles the viewer has seen`() {
        users.toggleFollow(me.id!!, friend.id!!)
        val mine = f.list(me.id!!, isPublic = false, name = "Mine")
        val theirs = f.list(friend.id!!, isPublic = true, name = "Theirs")
        val coco = f.movie("Coco")
        val up = f.movie("Up")
        val moana = f.movie("Moana")
        f.add(mine, coco, up, moana)
        f.add(theirs, coco, up)
        f.status(me.id!!, coco, WatchStatus.WATCHED)
        f.rate(me.id!!, moana, 8.0)
        f.status(friend.id!!, up, WatchStatus.WATCHED)
        val model = ExtendedModelMap()

        assertEquals("lists", controller.listsPage(me, null, model))

        val myCard = cards(model, "myLists").single()
        assertEquals(2, myCard.seenCount)
        assertEquals(3, myCard.itemCount)
        val friendCard = cards(model, "followedLists").single()
        assertEquals(1, friendCard.seenCount, "my progress, not the friend's")
    }

    @Test
    fun `empty lists still get a card with zero seen`() {
        f.list(me.id!!, name = "Empty")
        val model = ExtendedModelMap()

        controller.listsPage(me, null, model)

        assertEquals(0, cards(model, "myLists").single().seenCount)
    }

    @Test
    fun `cards built elsewhere (home row) carry no seen count`() {
        val list = f.list(me.id!!, name = "Home")
        val summary = InMemoryListSummaryRepository(f.lists, users, f.movies, f.shows).findByOwner(me.id!!).single { it.id == list.id }

        assertNull(summary.toCard(showOwner = false).seenCount)
    }
}
