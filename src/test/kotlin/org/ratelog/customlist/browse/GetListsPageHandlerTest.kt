package org.ratelog.customlist.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryListSummaryRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.UserFactory
import org.ratelog.user.User

class GetListsPageHandlerTest {

    private val users = InMemoryUserRepository()
    private val customLists = InMemoryCustomListRepository()
    private val movies = InMemoryMovieRepository()
    private val handler = GetListsPageHandler(InMemoryListSummaryRepository(customLists, users, movies), users, org.ratelog.test.InMemorySavedListRepository())

    private lateinit var me: User.Id
    private lateinit var friend: User.Id
    private lateinit var stranger: User.Id
    private var clock = 1_000L

    @BeforeEach
    fun setUp() {
        me = users.save(UserFactory.aUser(username = "me")).id!!
        friend = users.save(UserFactory.aUser(username = "friend")).id!!
        stranger = users.save(UserFactory.aUser(username = "stranger")).id!!
        users.toggleFollow(me, friend)
    }

    /** A list with `items` movies in it (public lists of others only show up when they aren't empty). */
    private fun list(owner: User.Id, name: String, isPublic: Boolean, items: Int = 1): CustomList {
        val list = customLists.save(CustomList(id = null, userId = owner, name = ListName(name), isPublic = isPublic, createdAtEpochMs = clock++))
        repeat(items) { position ->
            val movie = movies.save(MovieFactory.aMovie(originalTitle = "$name $position", posterPath = "/$name-$position.jpg"))
            customLists.addItem(CustomListItem(null, list.id!!, movie.id!!.value, MediaType.movie, position, clock++))
        }
        return list
    }

    private fun page(limit: Int = GetListsPageHandler.PAGE_SIZE) = handler.handle(GetListsPage(me, limit))

    @Test
    fun `my lists include my private and empty lists`() {
        list(me, "Private", isPublic = false)
        list(me, "Public", isPublic = true)
        list(me, "Empty", isPublic = false, items = 0)

        assertEquals(setOf("Private", "Public", "Empty"), page().mine.map { it.name }.toSet())
    }

    @Test
    fun `lists from people I follow are their public, non-empty lists`() {
        list(friend, "Friend public", isPublic = true)
        list(friend, "Friend private", isPublic = false)
        list(friend, "Friend empty", isPublic = true, items = 0)

        val result = page()

        assertEquals(listOf("Friend public"), result.followed.map { it.name })
        assertEquals("friend", result.followed.single().ownerUsername)
    }

    @Test
    fun `other users' public lists come after, without my lists or my friends' lists`() {
        list(me, "Mine", isPublic = true)
        list(friend, "Friend public", isPublic = true)
        list(stranger, "Stranger public", isPublic = true)

        val result = page()

        assertEquals(listOf("Stranger public"), result.others.map { it.name })
        assertEquals(listOf("Friend public"), result.followed.map { it.name })
    }

    @Test
    fun `other users' private lists never show up`() {
        list(friend, "Friend private", isPublic = false)
        list(stranger, "Stranger private", isPublic = false)

        val result = page()

        assertTrue(result.followed.isEmpty())
        assertTrue(result.others.isEmpty())
        assertTrue((result.mine + result.followed + result.others).none { !it.isPublic && it.ownerId != me })
    }

    @Test
    fun `without anyone followed, every other public list is under others`() {
        users.toggleFollow(me, friend) // unfollow
        list(friend, "Friend public", isPublic = true)

        val result = page()

        assertTrue(result.followed.isEmpty())
        assertEquals(listOf("Friend public"), result.others.map { it.name })
    }

    @Test
    fun `others are limited, newest activity first, with a load more flag`() {
        repeat(GetListsPageHandler.PAGE_SIZE + 2) { list(stranger, "List $it", isPublic = true) }

        val first = page()
        assertEquals(GetListsPageHandler.PAGE_SIZE, first.others.size)
        assertTrue(first.hasMoreOthers)
        assertEquals("List ${GetListsPageHandler.PAGE_SIZE + 1}", first.others.first().name)

        val more = page(limit = GetListsPageHandler.PAGE_SIZE * 2)
        assertEquals(GetListsPageHandler.PAGE_SIZE + 2, more.others.size)
        assertFalse(more.hasMoreOthers)
    }

    @Test
    fun `the limit is kept between one page and the maximum`() {
        assertEquals(GetListsPageHandler.PAGE_SIZE, page(limit = 0).limit)
        assertEquals(GetListsPageHandler.MAX_LIMIT, page(limit = 100_000).limit)
    }

    @Test
    fun `cards carry the item count and the first four posters in list order`() {
        list(me, "Big", isPublic = true, items = 6)

        val card = page().mine.single()

        assertEquals(6, card.itemCount)
        assertEquals((0..3).map { "/Big-$it.jpg" }, card.coverPosterPaths)
    }
}
