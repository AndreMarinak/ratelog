package org.ratelog.customlist.official.mark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Role
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.official.OfficialListsFixture
import org.ratelog.test.UserFactory

class SetListOfficialHandlerTest {

    private val f = OfficialListsFixture()

    private fun set(list: CustomList, by: org.ratelog.user.User, official: Boolean) =
        f.setOfficial.handle(SetListOfficial(list.id!!, by.id!!, official))

    @Test
    fun `marking a private list official makes it public`() {
        val list = f.list(f.admin, "AFI 100", isPublic = false)

        val change = set(list, f.admin, official = true).getOrNull()!!

        assertTrue(change.official)
        assertTrue(change.changed)
        assertTrue(change.madePublic)
        assertTrue(f.official.isOfficial(list.id!!))
        assertTrue(f.reload(list)!!.isPublic, "official implies public")
    }

    @Test
    fun `marking an already public list keeps it public and reports no visibility change`() {
        val list = f.list(f.admin, "Sight and Sound", isPublic = true)

        val change = set(list, f.admin, official = true).getOrNull()!!

        assertFalse(change.madePublic)
        assertTrue(f.reload(list)!!.isPublic)
    }

    @Test
    fun `marking twice is a no-op the second time`() {
        val list = f.list(f.admin, "IMDb Top 100")
        set(list, f.admin, official = true)

        val again = set(list, f.admin, official = true).getOrNull()!!

        assertFalse(again.changed)
        assertTrue(f.official.isOfficial(list.id!!))
    }

    @Test
    fun `admins can mark any list, not only their own`() {
        val plainAdmin = f.users.save(UserFactory.aUser(username = "mod", role = Role.ADMIN))
        val andresList = f.list(f.andre, "Andre's picks", isPublic = false)

        val change = set(andresList, plainAdmin, official = true)

        assertTrue(change.isRight())
        assertTrue(f.official.isOfficial(andresList.id!!))
        assertTrue(f.reload(andresList)!!.isPublic)
        assertEquals(f.andre.id, f.reload(andresList)!!.userId, "the owner doesn't change")
    }

    @Test
    fun `a regular user can't mark lists, not even their own, and nothing changes`() {
        val own = f.list(f.andre, "Mine", isPublic = false)
        val admins = f.list(f.admin, "Admin's", isPublic = false)

        assertEquals(SetListOfficialError.NotAdmin, set(own, f.andre, official = true).leftOrNull())
        assertEquals(SetListOfficialError.NotAdmin, set(admins, f.andre, official = true).leftOrNull())

        assertFalse(f.official.isOfficial(own.id!!))
        assertFalse(f.reload(own)!!.isPublic, "a refused request leaves the list private")
        assertFalse(f.official.isOfficial(admins.id!!))
    }

    @Test
    fun `a regular user can't un-mark an official list either`() {
        val list = f.list(f.admin, "MCU in order")
        f.markOfficial(list)

        assertEquals(SetListOfficialError.NotAdmin, set(list, f.andre, official = false).leftOrNull())
        assertTrue(f.official.isOfficial(list.id!!))
    }

    @Test
    fun `the role comes from the database, not from the caller's session`() {
        // A session created while this account was an admin; it has since been demoted to USER.
        val demoted = f.users.save(UserFactory.aUser(username = "former-admin", role = Role.USER))
        val staleSession = demoted.copy(role = Role.ADMIN)
        val list = f.list(f.admin, "Disney")

        assertEquals(SetListOfficialError.NotAdmin, set(list, staleSession, official = true).leftOrNull())
        assertEquals(SetListOfficialError.NotAdmin, set(list, UserFactory.aUser(id = 999, role = Role.SUPERADMIN), official = true).leftOrNull(),
            "an id with no account is not an admin")
    }

    @Test
    fun `un-marking leaves the list public`() {
        val list = f.list(f.admin, "Pixar", isPublic = false)
        f.markOfficial(list)

        val change = set(list, f.admin, official = false).getOrNull()!!

        assertFalse(change.official)
        assertTrue(change.changed)
        assertFalse(f.official.isOfficial(list.id!!))
        assertTrue(f.reload(list)!!.isPublic, "the owner can make it private again; un-marking doesn't")
        assertFalse(set(list, f.admin, official = false).getOrNull()!!.changed, "un-marking twice is a no-op")
    }

    @Test
    fun `a missing list is reported as such`() {
        assertEquals(
            SetListOfficialError.ListNotFound,
            f.setOfficial.handle(SetListOfficial(CustomList.Id(404), f.admin.id!!, official = true)).leftOrNull(),
        )
    }
}
