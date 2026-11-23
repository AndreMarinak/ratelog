package org.ratelog.user

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Role
import org.ratelog.test.UserFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.support.GenericWebApplicationContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.util.Locale

/** Renders the real nav, admin and add-account templates for each role, with the Spring Security dialect. */
class ManagerTemplatesTest {

    private val messages = ResourceBundleMessageSource().apply {
        setBasenames("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    private val servletContext = MockServletContext()
    private val appContext = GenericWebApplicationContext(servletContext).apply {
        defaultListableBeanFactory.registerSingleton("webSecurityExpressionHandler", DefaultWebSecurityExpressionHandler())
        refresh()
    }
    private val engine = SpringTemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
            isCacheable = false
        })
        setTemplateEngineMessageSource(messages)
        addDialect(SpringSecurityDialect())
    }

    init {
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, appContext)
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        LocaleContextHolder.resetLocaleContext()
    }

    private fun render(role: Role, template: String, fragments: Set<String> = emptySet(), vars: Map<String, Any?> = emptyMap()): String {
        val details = AppUserDetails.from(UserFactory.aUser(id = 5, role = role))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(details, null, details.authorities)
        val exchange = JakartaServletWebApplication.buildApplication(servletContext)
            .buildExchange(MockHttpServletRequest(servletContext, "GET", "/accounts/new"), MockHttpServletResponse())
        val context = WebContext(exchange, Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, fragments, context).also { assertFalse(it.contains("??"), "every message key resolves") }
    }

    private fun nav(role: Role) = render(role, "fragments/nav", setOf("nav"), mapOf("active" to "profile"))

    @Test
    fun `managers get an Add account entry in the header and the menu sheet, without the admin links`() {
        val html = nav(Role.MANAGER)

        assertTrue(html.contains("""href="/accounts/new""""))
        assertTrue(html.contains(">Add account<"))
        assertFalse(html.contains("/admin/configuration"))
    }

    @Test
    fun `users see no Add account entry, admins keep their admin page instead`() {
        assertFalse(nav(Role.USER).contains("/accounts/new"))
        assertFalse(nav(Role.USER).contains("/admin/configuration"))

        val admin = nav(Role.ADMIN)
        assertFalse(admin.contains("/accounts/new"))
        assertTrue(admin.contains("/admin/configuration"))
    }

    @Test
    fun `the add-account page posts username, email and a password of at least 8 characters`() {
        val html = render(Role.MANAGER, "account-add", vars = mapOf("createdUsername" to "friend"))

        assertTrue(html.contains("""action="/accounts/new""""))
        assertTrue(html.contains("""name="_csrf" value="tok123""""))
        assertTrue(html.contains("""name="username""""))
        assertTrue(html.contains("""name="email""""))
        assertTrue(html.contains("""name="password" required minlength="8""""))
        assertFalse(html.contains("""name="role""""), "no role choice: always a plain user")
        assertTrue(html.contains("Account friend created."))
    }

    @Test
    fun `admins and superadmins can pick Manager when creating or editing a user, only superadmins Admin`() {
        val adminCreate = render(Role.ADMIN, "fragments/admin-create-user-modal", setOf("createUserModal"))
        assertTrue(adminCreate.contains("""<option value="MANAGER">Manager</option>"""))
        assertFalse(adminCreate.contains("""value="ADMIN""""))

        val adminEdit = render(Role.ADMIN, "fragments/edit-user-modal", setOf("editUserModal"), mapOf("action" to "/admin/users/0", "withRole" to true))
        assertTrue(adminEdit.contains("""<option value="MANAGER">Manager</option>"""))
        assertFalse(adminEdit.contains("""value="ADMIN""""))

        val superEdit = render(Role.SUPERADMIN, "fragments/edit-user-modal", setOf("editUserModal"), mapOf("action" to "/admin/users/0", "withRole" to true))
        assertTrue(superEdit.contains("""<option value="ADMIN">Admin</option>"""))
    }

    @Test
    fun `the profile edit form always asks for the current password and has no role choice`() {
        val html = render(Role.SUPERADMIN, "fragments/edit-user-modal", setOf("editUserModal"), mapOf("action" to "/edit-profile", "withRole" to false))

        assertTrue(html.contains("""name="currentPassword" autocomplete="current-password" required"""))
        assertFalse(html.contains("""<select id="editRole""""))
    }
}
