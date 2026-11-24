package org.ratelog.feed.community.people

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Role
import org.ratelog.feed.community.people.web.CommunityPeopleView
import org.ratelog.test.InMemoryCommunityPeopleRepository
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.UserFactory
import org.ratelog.user.User

class ListCommunityPeopleHandlerTest {

    private val users = InMemoryUserRepository()
    private val people = InMemoryCommunityPeopleRepository(users)
    private val handler = ListCommunityPeopleHandler(users, people)

    private fun save(name: String, role: Role = Role.USER) = users.save(UserFactory.aUser(username = name, role = role))

    @Test
    fun `lists everyone but the viewer, A-Z ignoring case, admins included, with follow state and rating counts`() {
        val viewer = save("viewer")
        val admin = save("admin", Role.ADMIN)
        val marisol = save("Marisol")
        save("bob")
        users.toggleFollow(viewer.id!!, marisol.id!!)
        people.ratingCounts[marisol.id!!] = 12

        val result = handler.handle(ListCommunityPeople(viewer.id!!)).getOrNull()!!

        assertEquals(listOf("admin", "bob", "Marisol"), result.people.map { it.username.value })
        assertEquals(listOf(false, false, true), result.people.map { it.following })
        assertEquals(12L, result.people.last().ratingCount)
        assertEquals(1, result.followingCount)
        assertTrue(result.people.none { it.id == viewer.id })
        assertTrue(result.people.any { it.id == admin.id }, "admin accounts are listed like anyone else")
    }

    @Test
    fun `a viewer alone on the instance gets an empty directory`() {
        val viewer = save("onlyone")

        val result = handler.handle(ListCommunityPeople(viewer.id!!)).getOrNull()!!

        assertTrue(result.people.isEmpty())
        assertEquals(0, result.followingCount)
    }

    @Test
    fun `an unknown viewer is an error, not a directory`() {
        save("someone")

        val result = handler.handle(ListCommunityPeople(User.Id(999)))

        assertEquals(ListCommunityPeopleError.ViewerNotFound, result.leftOrNull())
    }

    @Test
    fun `the view gives each person an upper-case initial and keeps the counts`() {
        val viewer = save("viewer2")
        val zed = save("zed")
        users.toggleFollow(viewer.id!!, zed.id!!)
        people.ratingCounts[zed.id!!] = 3

        val view = CommunityPeopleView.of(handler.handle(ListCommunityPeople(viewer.id!!)).getOrNull()!!)

        assertEquals("Z", view.people.single().initial)
        assertEquals(3L, view.people.single().ratingCount)
        assertEquals(1, view.total)
        assertEquals(1, view.followingCount)
    }
}
