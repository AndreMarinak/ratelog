package org.ratelog.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Email
import org.ratelog.Lang
import org.ratelog.Password
import org.ratelog.Role
import org.ratelog.Username
import org.ratelog.test.FakePasswordEncoder
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.UserFactory
import org.ratelog.user.addaccount.AddAccountController
import org.ratelog.user.create.CreateUserCommand
import org.ratelog.user.create.CreateUserHandler
import org.ratelog.user.create.CreateUserHandlerError
import org.ratelog.user.delete.DeleteUserCommand
import org.ratelog.user.delete.DeleteUserHandler
import org.ratelog.user.delete.DeleteUserHandlerError
import org.ratelog.user.edit.EditUserCommand
import org.ratelog.user.edit.EditUserHandler
import org.ratelog.user.edit.EditUserHandlerError
import org.ratelog.user.listusers.ListUsersHandler
import org.ratelog.user.listusers.ListUsersHandlerError
import org.ratelog.user.listusers.ListUsersQuery
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap

/** Fork: the MANAGER role (2026-11-23) is a USER who can also add plain USER accounts. */
class ManagerRoleTest {

    private val users = InMemoryUserRepository()
    private val encoder = FakePasswordEncoder()
    private val createHandler = CreateUserHandler(users, encoder)
    private val editHandler = EditUserHandler(users, encoder)
    private val deleteHandler = DeleteUserHandler(users)
    private val addAccount = AddAccountController(createHandler)

    private val superadmin = users.save(UserFactory.aUser(username = "root", role = Role.SUPERADMIN))
    private val admin = users.save(UserFactory.aUser(username = "adm", role = Role.ADMIN))
    private val manager = users.save(UserFactory.aUser(username = "mgr", role = Role.MANAGER))
    private val user = users.save(UserFactory.aUser(username = "plain", role = Role.USER))

    private fun create(actor: User, role: Role, username: String = "new${role.name.lowercase()}") = createHandler.handle(
        CreateUserCommand(actor, Username(username), Email("$username@example.com"), Password("long-password"), Lang.en, role)
    )

    private fun changeRole(actor: User, target: User, role: Role) = editHandler.handle(
        EditUserCommand(actor, target.id!!, target.username, target.email, Password("password"), null, role)
    )

    private fun errorOf(result: arrow.core.Either<Any, Any>) = result.fold({ it }, { null })

    // --- Authorities ---

    @Test
    fun `a manager has the user and manager authorities, never admin`() {
        val authorities = AppUserDetails.from(manager).authorities.map { it.authority }.toSet()

        assertEquals(setOf("ROLE_USER", "ROLE_MANAGER"), authorities)
        assertTrue(Role.MANAGER.canCreateAccounts)
        assertFalse(Role.MANAGER.isAdminLike)
        assertFalse(Role.USER.canCreateAccounts)
        assertEquals(Role.MANAGER, Role.parse("manager").getOrNull())
    }

    // --- Creating accounts ---

    @Test
    fun `a manager can create a plain user account`() {
        assertTrue(create(manager, Role.USER).isRight())

        assertEquals(Role.USER, users.findByUsername(Username("newuser"))!!.role)
    }

    @Test
    fun `a manager cannot create managers, admins or superadmins`() {
        assertEquals(CreateUserHandlerError.ManagerCanOnlyCreateUsers, errorOf(create(manager, Role.MANAGER)))
        assertEquals(CreateUserHandlerError.ManagerCanOnlyCreateUsers, errorOf(create(manager, Role.ADMIN)))
        assertEquals(CreateUserHandlerError.CannotPromoteToSuperadmin, errorOf(create(manager, Role.SUPERADMIN)))
        assertNull(users.findByUsername(Username("newmanager")))
        assertNull(users.findByUsername(Username("newadmin")))
    }

    @Test
    fun `a plain user cannot create accounts`() {
        assertEquals(CreateUserHandlerError.Forbidden, errorOf(create(user, Role.USER)))
    }

    @Test
    fun `admins and superadmins can create managers`() {
        assertTrue(create(admin, Role.MANAGER, "m1").isRight())
        assertTrue(create(superadmin, Role.MANAGER, "m2").isRight())

        assertEquals(Role.MANAGER, users.findByUsername(Username("m1"))!!.role)
        assertEquals(CreateUserHandlerError.CannotCreateAdmin, errorOf(create(admin, Role.ADMIN, "a1")))
    }

    // --- Giving and taking away the role ---

    @Test
    fun `an admin can give and take away the manager role`() {
        assertTrue(changeRole(admin, user, Role.MANAGER).isRight())
        assertEquals(Role.MANAGER, users.findById(user.id!!)!!.role)

        assertTrue(changeRole(admin, users.findById(user.id)!!, Role.USER).isRight())
        assertEquals(Role.USER, users.findById(user.id)!!.role)
    }

    @Test
    fun `an admin cannot turn a manager into an admin`() {
        assertEquals(EditUserHandlerError.CannotChangeRole, errorOf(changeRole(admin, manager, Role.ADMIN)))
        assertEquals(Role.MANAGER, users.findById(manager.id!!)!!.role)
    }

    @Test
    fun `a superadmin can make a manager an admin`() {
        assertTrue(changeRole(superadmin, manager, Role.ADMIN).isRight())
    }

    @Test
    fun `a manager cannot change anyone's role or edit other accounts`() {
        assertEquals(EditUserHandlerError.Forbidden, errorOf(changeRole(manager, user, Role.MANAGER)))
        assertEquals(EditUserHandlerError.CannotChangeRole, errorOf(changeRole(manager, manager, Role.ADMIN)))
        assertEquals(Role.MANAGER, users.findById(manager.id!!)!!.role)
    }

    @Test
    fun `a manager cannot delete accounts or list users, an admin can delete a manager`() {
        assertEquals(DeleteUserHandlerError.Forbidden, errorOf(deleteHandler.handle(DeleteUserCommand(manager, user.id!!))))
        assertEquals(ListUsersHandlerError.Forbidden, errorOf(ListUsersHandler(users).handle(ListUsersQuery(manager))))

        assertTrue(deleteHandler.handle(DeleteUserCommand(admin, manager.id!!)).isRight())
        assertNull(users.findById(manager.id))
    }

    // --- The "Add account" page ---

    @Test
    fun `the add-account page creates a plain user and names it in the success message`() {
        val flash = RedirectAttributesModelMap()

        val view = addAccount.create(manager, "friend", "friend@example.com", "long-password", MockHttpServletRequest(), flash)

        assertEquals("redirect:/accounts/new", view)
        assertEquals("friend", flash.flashAttributes["createdUsername"])
        assertNull(flash.flashAttributes["error"])
        val created = users.findByUsername(Username("friend"))!!
        assertEquals(Role.USER, created.role)
        assertEquals("encoded_long-password", created.passwordHash)
    }

    @Test
    fun `the add-account page applies the username, email and password rules`() {
        fun attempt(username: String, email: String, password: String): Any? {
            val flash = RedirectAttributesModelMap()
            addAccount.create(manager, username, email, password, MockHttpServletRequest(), flash)
            return flash.flashAttributes["error"]
        }

        assertEquals("security.password.too.short", attempt("friend", "friend@example.com", "short"))
        assertEquals("admin.error.invalid.username", attempt("x", "friend@example.com", "long-password"))
        assertEquals("admin.error.invalid.email", attempt("friend", "not-an-email", "long-password"))
        assertEquals("admin.error.username.exists", attempt("plain", "other@example.com", "long-password"))
        assertNull(users.findByUsername(Username("friend")))
    }

    @Test
    fun `a plain user is turned away from the add-account page`() {
        assertEquals("redirect:/profile", addAccount.page(user))
        assertEquals("account-add", addAccount.page(manager))

        val flash = RedirectAttributesModelMap()
        addAccount.create(user, "notallowed", "notallowed@example.com", "long-password", MockHttpServletRequest(), flash)
        assertEquals("admin.error.forbidden", flash.flashAttributes["error"])
        assertNull(users.findByUsername(Username("notallowed")))
    }
}
