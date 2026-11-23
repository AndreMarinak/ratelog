package org.ratelog.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ratelog.Role
import org.ratelog.Username
import org.ratelog.test.FakePasswordEncoder
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.UserFactory

class AdminPasswordBootstrapTest {

    private val encoder = FakePasswordEncoder()
    private val users = InMemoryUserRepository()

    private fun seedAdmin(passwordHash: String = encoder.encode("admin")) =
        users.save(UserFactory.aUser(username = "admin", passwordHash = passwordHash, role = Role.SUPERADMIN))

    private fun adminHash() = users.findByUsername(Username("admin"))!!.passwordHash

    @Test
    fun `replaces the default admin password when ADMIN_PASSWORD is set`() {
        seedAdmin()
        AdminPasswordBootstrap(users, encoder, "a-long-secret-123").run()
        assertEquals(encoder.encode("a-long-secret-123"), adminHash())
    }

    @Test
    fun `leaves a password that was already changed`() {
        seedAdmin(passwordHash = encoder.encode("changed-in-the-ui"))
        AdminPasswordBootstrap(users, encoder, "a-long-secret-123").run()
        assertEquals(encoder.encode("changed-in-the-ui"), adminHash())
    }

    @Test
    fun `does nothing without ADMIN_PASSWORD`() {
        seedAdmin()
        AdminPasswordBootstrap(users, encoder, "").run()
        assertEquals(encoder.encode("admin"), adminHash())
    }

    @Test
    fun `ignores a too-short ADMIN_PASSWORD`() {
        seedAdmin()
        AdminPasswordBootstrap(users, encoder, "short").run()
        assertEquals(encoder.encode("admin"), adminHash())
    }

    @Test
    fun `does nothing when there is no admin user`() {
        AdminPasswordBootstrap(users, encoder, "a-long-secret-123").run()
    }
}
