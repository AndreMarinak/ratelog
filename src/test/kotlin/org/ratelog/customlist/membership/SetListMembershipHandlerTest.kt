package org.ratelog.customlist.membership

import arrow.core.left
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.customlist.removeitem.RemoveFromListHandler
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.user.User

class SetListMembershipHandlerTest {

    private val lists = InMemoryCustomListRepository()
    private val handler = SetListMembershipHandler(lists, AddToListHandler(lists), RemoveFromListHandler(lists))

    private val owner = User.Id(1)
    private val someoneElse = User.Id(2)

    private fun list(name: String, user: User.Id = owner, vararg mediaIds: Long): CustomList {
        val list = lists.save(CustomList(null, user, ListName(name), isPublic = false, createdAtEpochMs = 1_000))
        mediaIds.forEachIndexed { index, mediaId ->
            lists.addItem(CustomListItem(null, list.id!!, mediaId, MediaType.movie, index + 1, 1_000))
        }
        return list
    }

    private fun items(list: CustomList) = lists.findById(list.id!!)!!.items

    @Test
    fun `adding a title appends it at the end, like Add to list`() {
        val disney = list("Best Disney Movies", owner, 10, 11)

        val result = handler.handle(SetListMembership(disney.id!!, owner, MediaType.movie, 42, inList = true))

        assertEquals(SetListMembershipResult(ListName("Best Disney Movies"), inList = true, changed = true), result.getOrNull())
        val added = items(disney).single { it.mediaId == 42L }
        assertEquals(3, added.position)
        assertEquals(MediaType.movie, added.mediaType)
    }

    @Test
    fun `removing a title takes only that title out of only that list`() {
        val disney = list("Best Disney Movies", owner, 10, 42, 11)
        val pixar = list("Pixar", owner, 42)

        val result = handler.handle(SetListMembership(disney.id!!, owner, MediaType.movie, 42, inList = false))

        assertEquals(SetListMembershipResult(ListName("Best Disney Movies"), inList = false, changed = true), result.getOrNull())
        assertEquals(listOf(10L, 11L), items(disney).map { it.mediaId })
        assertEquals(listOf(42L), items(pixar).map { it.mediaId }, "the other list keeps it")
    }

    @Test
    fun `asking for the state it already has changes nothing`() {
        val disney = list("Best Disney Movies", owner, 42)
        val empty = list("Empty", owner)

        val again = handler.handle(SetListMembership(disney.id!!, owner, MediaType.movie, 42, inList = true))
        val notThere = handler.handle(SetListMembership(empty.id!!, owner, MediaType.movie, 42, inList = false))

        assertEquals(false, again.getOrNull()?.changed)
        assertEquals(true, again.getOrNull()?.inList)
        assertEquals(1, items(disney).size, "never added twice")
        assertEquals(false, notThere.getOrNull()?.changed)
        assertTrue(items(empty).isEmpty())
    }

    @Test
    fun `a show and a movie with the same local id are different titles`() {
        val mixed = list("Mixed", owner, 42) // movie 42

        handler.handle(SetListMembership(mixed.id!!, owner, MediaType.tvshow, 42, inList = true))
        assertEquals(2, items(mixed).size)

        handler.handle(SetListMembership(mixed.id!!, owner, MediaType.tvshow, 42, inList = false))
        assertEquals(listOf(MediaType.movie), items(mixed).map { it.mediaType }, "the movie stays")
    }

    @Test
    fun `a missing list is ListNotFound`() {
        val result = handler.handle(SetListMembership(CustomList.Id(999), owner, MediaType.movie, 42, inList = true))
        assertEquals(SetListMembershipError.ListNotFound.left(), result)
    }

    @Test
    fun `only the owner can add to or remove from a list`() {
        val theirs = list("Their list", someoneElse, 42)

        val remove = handler.handle(SetListMembership(theirs.id!!, owner, MediaType.movie, 42, inList = false))
        val add = handler.handle(SetListMembership(theirs.id!!, owner, MediaType.movie, 7, inList = true))

        assertEquals(SetListMembershipError.NotOwner.left(), remove)
        assertEquals(SetListMembershipError.NotOwner.left(), add)
        assertEquals(listOf(42L), items(theirs).map { it.mediaId }, "untouched")
        assertNull(items(theirs).find { it.mediaId == 7L })
        assertFalse(remove.isRight())
    }
}
