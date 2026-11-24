package org.ratelog.user.follow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.UserFactory
import org.ratelog.user.User
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap

class SetUserFollowTest {

    private val users = InMemoryUserRepository()
    private val handler = SetUserFollowHandler(users)
    private val controller = SetUserFollowController(handler)

    private val me = users.save(UserFactory.aUser(username = "viewer"))
    private val marisol = users.save(UserFactory.aUser(username = "marisol"))

    @Test
    fun `follow then unfollow sets the state that was asked for`() {
        val followed = handler.handle(SetUserFollow(me.id!!, marisol.id!!, following = true)).getOrNull()!!
        assertTrue(followed.following)
        assertEquals("marisol", followed.username.value)
        assertTrue(users.isFollowing(me.id!!, marisol.id!!))

        handler.handle(SetUserFollow(me.id!!, marisol.id!!, following = false))
        assertFalse(users.isFollowing(me.id!!, marisol.id!!))
    }

    @Test
    fun `sending the same state twice never flips it back (stale page, double tap)`() {
        repeat(2) { handler.handle(SetUserFollow(me.id!!, marisol.id!!, following = true)) }
        assertTrue(users.isFollowing(me.id!!, marisol.id!!))

        repeat(2) { handler.handle(SetUserFollow(me.id!!, marisol.id!!, following = false)) }
        assertFalse(users.isFollowing(me.id!!, marisol.id!!))
    }

    @Test
    fun `nobody can follow themselves or an account that does not exist`() {
        assertEquals(SetUserFollowError.CannotFollowYourself, handler.handle(SetUserFollow(me.id!!, me.id!!, true)).leftOrNull())
        assertEquals(SetUserFollowError.UserNotFound, handler.handle(SetUserFollow(me.id!!, User.Id(999), true)).leftOrNull())
        assertFalse(users.isFollowing(me.id!!, me.id!!))
    }

    @Test
    fun `the controller saves and goes back to the page the button was on`() {
        val flash = RedirectAttributesModelMap()

        val view = controller.setFollow(me, marisol.id!!.value, true, "/community?limit=20", MockHttpServletRequest(), flash)

        assertEquals("redirect:/community?limit=20", view)
        assertTrue(users.isFollowing(me.id!!, marisol.id!!))
        assertNull(flash.flashAttributes["error"])
    }

    @Test
    fun `without a back field it uses the Referer path, then the profile, and never leaves the site`() {
        val id = marisol.id!!.value
        val request = MockHttpServletRequest().apply { addHeader("Referer", "https://example.test/community?limit=10") }
        assertEquals("redirect:/community?limit=10", controller.setFollow(me, id, true, null, request, RedirectAttributesModelMap()))

        // Off-site values keep only their path (BackPath), so the redirect stays on this site; no path: the profile.
        listOf("https://evil.test/x", "//evil.test/x").forEach { back ->
            assertEquals("redirect:/x", controller.setFollow(me, id, false, back, MockHttpServletRequest(), RedirectAttributesModelMap()), back)
        }
        assertEquals("redirect:/profile/$id", controller.setFollow(me, id, false, "javascript:alert(1)", MockHttpServletRequest(), RedirectAttributesModelMap()))
    }

    @Test
    fun `an error is flashed for the page to show`() {
        val flash = RedirectAttributesModelMap()

        controller.setFollow(me, 999, true, "/community", MockHttpServletRequest(), flash)

        assertEquals("error.user.not.found", flash.flashAttributes["error"])
    }
}
