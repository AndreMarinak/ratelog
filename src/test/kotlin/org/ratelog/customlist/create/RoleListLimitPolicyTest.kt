package org.ratelog.customlist.create

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Role
import org.ratelog.test.UserFactory
import org.ratelog.user.User
import org.ratelog.user.UserRepository

class RoleListLimitPolicyTest {

    private val users = mock<UserRepository>()
    private val policy = RoleListLimitPolicy(users)

    private fun limitFor(role: Role): Int {
        val user = UserFactory.aUser(id = 5, role = role)
        whenever(users.findById(User.Id(5))).thenReturn(user)
        return policy.maxLists(User.Id(5))
    }

    @Test
    fun `admins may own more lists than everyone else`() {
        assertEquals(200, limitFor(Role.ADMIN))
        assertEquals(200, limitFor(Role.SUPERADMIN))
        assertEquals(50, limitFor(Role.USER))
        assertEquals(50, limitFor(Role.MANAGER))
    }

    @Test
    fun `an unknown account gets the normal limit`() {
        assertEquals(50, policy.maxLists(User.Id(404)))
    }
}
