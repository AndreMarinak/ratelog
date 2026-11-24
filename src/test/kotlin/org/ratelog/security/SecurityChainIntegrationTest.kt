package org.ratelog.security

import jakarta.servlet.Filter
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ratelog.Email
import org.ratelog.Role
import org.ratelog.SecurityConfig
import org.ratelog.Username
import org.ratelog.config.GeneralConfigRepository
import org.ratelog.system.RememberMeKeyProvider
import org.ratelog.test.InMemoryGeneralConfigRepository
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.UserFactory
import org.ratelog.user.AppUserDetails
import org.ratelog.user.User
import org.ratelog.user.UserDetailsService
import org.ratelog.user.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * The real browser security chain (SecurityConfig) in front of stub pages: headers, login throttling, session
 * revalidation (also for remember-me logins), the manager role's URL rules, CSRF and the request rate limit.
 */
@SpringJUnitWebConfig(SecurityChainIntegrationTest.Config::class)
class SecurityChainIntegrationTest {

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig::class, RateLimitConfig::class)
    class Config {
        @Bean fun userRepository(): InMemoryUserRepository = InMemoryUserRepository()
        @Bean fun generalConfigRepository(): GeneralConfigRepository = InMemoryGeneralConfigRepository()
        @Bean fun rememberMeKeyProvider(repository: GeneralConfigRepository) = RememberMeKeyProvider(repository)
        @Bean fun userDetailsService(repository: UserRepository) = UserDetailsService(repository)
        @Bean fun messageSource() = ResourceBundleMessageSource().apply { setBasenames("messages"); setDefaultEncoding("UTF-8") }
        @Bean fun pages() = Pages()
    }

    @RestController
    class Pages {
        /** Stands in for the login page; hands out the CSRF token. */
        @GetMapping("/login")
        fun login(request: HttpServletRequest): String = (request.getAttribute(CsrfToken::class.java.name) as CsrfToken).token

        @GetMapping("/home")
        fun home(): String = (SecurityContextHolder.getContext().authentication!!.principal as AppUserDetails).let { "${it.username}:${it.role}" }

        @GetMapping("/settings/api/guide.md") fun guide() = "# guide"
        @GetMapping("/admin/configuration") fun admin() = "admin page"
        @GetMapping("/accounts/new") fun addAccount() = "add account page"
        @PostMapping("/write") fun write() = "saved"
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var users: InMemoryUserRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain", Filter::class.java))
            .build()
    }

    private var counter = 0
    /** Each test signs in from its own address, so per-IP login counts never spill over between tests. */
    private val ip = "198.51.100.${(System.nanoTime() % 250).toInt()}"

    private fun aUser(role: Role = Role.USER, password: String = "correct-horse"): User =
        users.save(UserFactory.aUser(username = "u${System.nanoTime() % 1_000_000}x${counter++}", role = role, passwordHash = passwordEncoder.encode(password)))

    private fun csrf(session: MockHttpSession): String = mvc.perform(get("/login").session(session)).andReturn().response.contentAsString

    private fun login(user: User, password: String = "correct-horse", session: MockHttpSession = MockHttpSession(), rememberMe: Boolean = false): MvcResult =
        mvc.perform(
            post("/login/process").session(session).header("X-Real-IP", ip)
                .param("username", user.username.value).param("password", password).param("_csrf", csrf(session))
                .apply { if (rememberMe) param("remember-me", "on") }
        ).andReturn()

    private fun signedIn(user: User): MockHttpSession = MockHttpSession().also {
        assertEquals("/", login(user, session = it).response.redirectedUrl)
    }

    private fun home(session: MockHttpSession) = mvc.perform(get("/home").session(session)).andReturn().response

    // --- Headers ---

    @Test
    fun `pages carry the security headers`() {
        val response = mvc.perform(get("/login")).andReturn().response

        assertEquals(SecurityHeaders.CONTENT_SECURITY_POLICY, response.getHeader("Content-Security-Policy"))
        assertEquals("strict-origin-when-cross-origin", response.getHeader("Referrer-Policy"))
        assertEquals(SecurityHeaders.PERMISSIONS_POLICY, response.getHeader("Permissions-Policy"))
        assertEquals("DENY", response.getHeader("X-Frame-Options"))
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
        assertTrue(SecurityHeaders.CONTENT_SECURITY_POLICY.contains("frame-ancestors 'none'"))
    }

    @Test
    fun `the login page's fonts load for signed-out visitors`() {
        // Signed out, a font request must not be sent to the login page (the browser would get HTML instead of the font).
        // (This test context serves no static files, so passing security shows up as a 404 rather than a 302 to /login.)
        val response = mvc.perform(get("/fonts/CrimsonPro-Regular.woff2")).andReturn().response
        assertEquals(null, response.redirectedUrl, "redirected to the login page")
        assertTrue(response.status != 302 && response.status != 401 && response.status != 403, "status ${response.status}")
    }

    @Test
    fun `the API guide download needs a login`() {
        val response = mvc.perform(get("/settings/api/guide.md")).andReturn().response
        assertEquals(302, response.status)
        assertEquals("/login", response.redirectedUrl)
    }

    @Test
    fun `the login redirect is relative, whatever host the request names`() {
        val response = mvc.perform(get("/home").with { it.serverName = "ratelog.example"; it.scheme = "https"; it.serverPort = 443; it }).andReturn().response

        assertEquals("/login", response.redirectedUrl)
    }

    @Test
    fun `cross-origin requests are never allowed to carry the browser's cookies`() {
        val configs = object : org.springframework.web.servlet.config.annotation.CorsRegistry() {
            fun all() = corsConfigurations
        }.also { org.ratelog.WebConfig().addCorsMappings(it) }.all()

        assertTrue(configs.values.none { it.allowCredentials == true })
    }

    // --- Login throttling ---

    @Test
    fun `repeated wrong passwords lock the account for a while, even for the right password`() {
        val user = aUser()
        repeat(LoginThrottle.MAX_FAILURES_PER_USERNAME - 1) {
            assertEquals("/login?error=true", login(user, password = "wrong").response.redirectedUrl)
        }
        assertEquals("/login?error=locked", login(user, password = "wrong").response.redirectedUrl)

        val session = MockHttpSession()
        assertEquals("/login?error=locked", login(user, session = session).response.redirectedUrl)
        assertTrue(home(session).redirectedUrl!!.endsWith("/login"), "not signed in")
    }

    @Test
    fun `an unknown username gets exactly the same answers`() {
        val ghost = UserFactory.aUser(username = "ghost${counter++}")
        repeat(LoginThrottle.MAX_FAILURES_PER_USERNAME - 1) {
            assertEquals("/login?error=true", login(ghost, password = "wrong").response.redirectedUrl)
        }
        assertEquals("/login?error=locked", login(ghost, password = "wrong").response.redirectedUrl)
    }

    @Test
    fun `an existing short password still signs in`() {
        val legacy = aUser(password = "abc")

        assertEquals("/", login(legacy, password = "abc").response.redirectedUrl)
    }

    // --- Session revalidation ---

    @Test
    fun `a demoted admin loses the admin pages on the next request`() {
        val admin = aUser(Role.ADMIN)
        val session = signedIn(admin)
        assertEquals(200, mvc.perform(get("/admin/configuration").session(session)).andReturn().response.status)

        users.updateRole(admin.id!!, Role.USER)

        assertEquals("/profile", mvc.perform(get("/admin/configuration").session(session)).andReturn().response.redirectedUrl)
        assertEquals("${admin.username.value}:USER", home(session).contentAsString)
    }

    @Test
    fun `a deleted user's session ends`() {
        val user = aUser()
        val session = signedIn(user)

        users.deleteById(user.id!!)

        assertTrue(home(session).redirectedUrl!!.endsWith("/login"))
    }

    @Test
    fun `a password change ends the other sessions`() {
        val user = aUser()
        val session = signedIn(user)
        assertEquals(200, home(session).status)

        users.updateCredentials(user.id!!, user.username, user.email, passwordEncoder.encode("a-new-password"))

        assertTrue(home(session).redirectedUrl!!.endsWith("/login"))
        assertTrue(home(session).redirectedUrl!!.endsWith("/login"), "and it stays signed out")
    }

    @Test
    fun `remember-me logins follow the same rules`() {
        val user = aUser(Role.ADMIN)
        val cookie: Cookie = login(user, rememberMe = true).response.getCookie("remember-me")!!
        fun fresh() = mvc.perform(get("/home").cookie(cookie)).andReturn().response

        assertEquals("${user.username.value}:ADMIN", fresh().contentAsString)

        users.updateRole(user.id!!, Role.USER)
        assertEquals("${user.username.value}:USER", fresh().contentAsString)

        users.updateCredentials(user.id!!, Username(user.username.value), Email(user.email.value), passwordEncoder.encode("changed-password"))
        assertTrue(fresh().redirectedUrl!!.endsWith("/login"))
    }

    // --- Manager role ---

    @Test
    fun `managers reach the add-account page but not the admin pages, users reach neither`() {
        val manager = signedIn(aUser(Role.MANAGER))
        assertEquals("add account page", mvc.perform(get("/accounts/new").session(manager)).andReturn().response.contentAsString)
        assertEquals("/profile", mvc.perform(get("/admin/configuration").session(manager)).andReturn().response.redirectedUrl)

        val user = signedIn(aUser(Role.USER))
        assertEquals("/profile", mvc.perform(get("/accounts/new").session(user)).andReturn().response.redirectedUrl)
    }

    @Test
    fun `a new manager role applies to the open session`() {
        val person = aUser(Role.USER)
        val session = signedIn(person)
        users.updateRole(person.id!!, Role.MANAGER)

        assertEquals(200, mvc.perform(get("/accounts/new").session(session)).andReturn().response.status)
    }

    // --- CSRF and rate limit ---

    @Test
    fun `posts still need the CSRF token`() {
        val session = signedIn(aUser())

        val response = mvc.perform(post("/write").session(session)).andReturn().response

        assertEquals("/profile", response.redirectedUrl, "refused by the access-denied handler")
        assertEquals("saved", mvc.perform(post("/write").session(session).param("_csrf", csrf(session))).andReturn().response.contentAsString)
    }

    @Test
    fun `too many writes from one account get 429 with Retry-After`() {
        val session = signedIn(aUser())
        val token = csrf(session)
        // Real clock: the bucket refills one write a second while this runs, so send well over the 90 it holds.
        val statuses = (1..120).map { mvc.perform(post("/write").session(session).param("_csrf", token)).andReturn().response }

        assertTrue(statuses.take(90).all { it.status == 200 }, "60 a minute plus a burst of 30")
        val limited = statuses.firstOrNull { it.status == 429 }
        assertNotNull(limited, "refused once the bucket is empty")
        assertNotNull(limited!!.getHeader("Retry-After"))

        val other = signedIn(aUser())
        assertEquals(200, mvc.perform(post("/write").session(other).param("_csrf", csrf(other))).andReturn().response.status, "other accounts are unaffected")
    }
}
