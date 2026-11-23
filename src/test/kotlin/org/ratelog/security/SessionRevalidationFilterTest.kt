package org.ratelog.security

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Email
import org.ratelog.Lang
import org.ratelog.Role
import org.ratelog.Username
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.UserFactory
import org.ratelog.user.AppUserDetails
import org.ratelog.user.User
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository

class SessionRevalidationFilterTest {

    private val users = InMemoryUserRepository()
    private val repository = HttpSessionSecurityContextRepository()
    private val filter = SessionRevalidationFilter(users, repository)

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    /** Signs [user] in as of now: the session holds a copy of their details. */
    private fun signIn(user: User): MockHttpSession {
        val details = AppUserDetails.from(user)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(details, null, details.authorities)
        return MockHttpSession()
    }

    private fun request(session: MockHttpSession, path: String = "/profile") = MockHttpServletRequest("GET", path).apply {
        servletPath = path
        this.session = session
    }

    private fun run(request: MockHttpServletRequest): MockFilterChain = MockFilterChain().also { filter.doFilter(request, MockHttpServletResponse(), it) }

    private fun principal(): AppUserDetails? = SecurityContextHolder.getContext().authentication?.principal as? AppUserDetails

    @Test
    fun `an unchanged user keeps the same session and authentication`() {
        val user = users.save(UserFactory.aUser(role = Role.ADMIN))
        val session = signIn(user)
        val before = SecurityContextHolder.getContext().authentication

        val chain = run(request(session))

        assertNotNull(chain.request)
        assertSame(before, SecurityContextHolder.getContext().authentication)
        assertFalse(session.isInvalid)
    }

    @Test
    fun `a demoted admin loses the admin authority on the next request`() {
        val admin = users.save(UserFactory.aUser(role = Role.ADMIN))
        val session = signIn(admin)
        users.updateRole(admin.id!!, Role.USER)

        val request = request(session)
        run(request)

        assertEquals(Role.USER, principal()!!.role)
        val authorities = SecurityContextHolder.getContext().authentication!!.authorities.map { it.authority }
        assertEquals(listOf("ROLE_USER"), authorities)
        val saved = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext
        assertEquals(Role.USER, (saved.authentication!!.principal as AppUserDetails).role, "stored in the session too")
        assertFalse(session.isInvalid)
    }

    @Test
    fun `a promoted user gains the admin authority`() {
        val user = users.save(UserFactory.aUser(role = Role.USER))
        val session = signIn(user)
        users.updateRole(user.id!!, Role.ADMIN)

        run(request(session))

        assertTrue(SecurityContextHolder.getContext().authentication!!.authorities.any { it.authority == "ROLE_ADMIN" })
    }

    @Test
    fun `a deleted user's session ends`() {
        val user = users.save(UserFactory.aUser())
        val session = signIn(user)
        users.deleteById(user.id!!)

        val chain = run(request(session))

        assertNull(SecurityContextHolder.getContext().authentication)
        assertTrue(session.isInvalid)
        assertNotNull(chain.request, "continues as anonymous; authorization then sends protected pages to the login page")
    }

    @Test
    fun `a session that signed in before a password change ends`() {
        val user = users.save(UserFactory.aUser(passwordHash = "hash-1"))
        val session = signIn(user)
        users.updateCredentials(user.id!!, user.username, user.email, "hash-2")

        run(request(session))

        assertNull(SecurityContextHolder.getContext().authentication)
        assertTrue(session.isInvalid)
    }

    @Test
    fun `renames, new email and languages are refreshed without ending the session`() {
        val user = users.save(UserFactory.aUser(username = "old", email = "old@example.com"))
        val session = signIn(user)
        users.updateCredentials(user.id!!, Username("new"), Email("new@example.com"), user.passwordHash)
        users.save(users.findById(user.id!!)!!.copy(lang = Lang.de, metadataLang = Lang.fr))

        run(request(session))

        val refreshed = principal()!!
        assertEquals("new", refreshed.username)
        assertEquals("new@example.com", refreshed.email)
        assertEquals(Lang.de, refreshed.lang)
        assertEquals(Lang.fr, refreshed.metadataLang)
        assertFalse(session.isInvalid)
    }

    @Test
    fun `anonymous requests are left alone`() {
        val chain = run(MockHttpServletRequest("GET", "/login").apply { servletPath = "/login" })

        assertNotNull(chain.request)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `static files skip the lookup, admin pages never do`() {
        listOf("/styles.css", "/lucide.min.js", "/manifest.webmanifest", "/img/logo.svg", "/fonts/CrimsonPro-Light.woff2")
            .forEach { assertTrue(SessionRevalidationFilter.isStaticAsset(it), it) }
        listOf("/admin/configuration", "/admin/users/1", "/admin/x.css", "/profile", "/movie/1", "/")
            .forEach { assertFalse(SessionRevalidationFilter.isStaticAsset(it), it) }
    }

    @Test
    fun `a deleted user's static file requests are not looked up`() {
        val user = users.save(UserFactory.aUser())
        val session = signIn(user)
        users.deleteById(user.id!!)

        run(request(session, "/styles.css"))

        assertFalse(session.isInvalid, "the next page request ends it")
    }
}
