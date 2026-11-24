package org.ratelog.feed.community.people.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Role
import org.ratelog.test.UserFactory
import org.ratelog.user.AppUserDetails
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

/** Renders the real Community and profile pages: the People directory and the Follow / Following button states. */
class CommunityPeopleTemplatesTest {

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

    private fun render(template: String, path: String, vars: Map<String, Any?>): String {
        val details = AppUserDetails.from(UserFactory.aUser(id = 5, username = "viewer", role = Role.USER))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(details, null, details.authorities)
        val exchange = JakartaServletWebApplication.buildApplication(servletContext)
            .buildExchange(MockHttpServletRequest(servletContext, "GET", path), MockHttpServletResponse())
        val context = WebContext(exchange, Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, context).also { assertFalse(it.contains("??"), "every message key resolves") }
    }

    private val people = CommunityPeopleView(
        people = listOf(
            CommunityPersonView(id = 7, username = "admin", initial = "A", ratingCount = 0, following = false),
            CommunityPersonView(id = 8, username = "marisol", initial = "M", ratingCount = 12, following = true),
            CommunityPersonView(id = 9, username = "zed", initial = "Z", ratingCount = 1, following = false),
        ),
        followingCount = 1,
    )

    private fun community(people: CommunityPeopleView?) =
        render("community", "/community", mapOf("feed" to emptyList<Any>(), "limit" to 10, "communityPeople" to people))

    /** The <form> of one person's follow button. */
    private fun followForm(html: String, id: Long): String {
        val start = html.indexOf("""<form id="follow-$id"""")
        assertTrue(start >= 0, "follow form for $id")
        return html.substring(start, html.indexOf("</form>", start))
    }

    @Test
    fun `Community lists every person with a profile link, an initial, a rating count and a follow button`() {
        val html = community(people)

        assertTrue(html.contains(">People<"))
        assertTrue(html.contains(">Activity<"))
        assertTrue(html.contains("""id="communityPeopleSummary""""))
        assertTrue(html.contains(">Following 1 of 3<"))
        listOf(7L, 8L, 9L).forEach { assertTrue(html.contains("""href="/profile/$it""""), "profile link $it") }
        assertTrue(html.contains(">No ratings yet<"))
        assertTrue(html.contains(">1 rating<"))
        assertTrue(html.contains(">12 ratings<"))
        assertTrue(html.contains("""id="user-search-input""""), "the user search stays")
        assertTrue(html.contains("""id="communityFeed""""))
        assertTrue(html.contains("/follow.css") && html.contains("/follow-button.js") && html.contains("/confirm.js"))
        assertFalse(html.contains("/user/follow\""), "the old toggle endpoint is no longer used on this page")
    }

    @Test
    fun `not following - a filled Follow button that posts following=true and saves in place`() {
        val form = followForm(community(people), 7)

        assertTrue(form.contains("""action="/user/follow/set""""))
        assertTrue(form.contains("""method="post""""))
        assertTrue(form.contains("""name="_csrf" value="tok123""""))
        assertTrue(form.contains("""name="id" value="7""""))
        assertTrue(form.contains("""name="following" value="true""""))
        assertTrue(form.contains("""name="back" value="/community?limit=10""""))
        assertTrue(form.contains("""data-inplace="#follow-7, #communityPeopleSummary, #communityFeed""""))
        assertTrue(form.contains("""data-inplace-done="Following admin.""""))
        assertFalse(form.contains("data-confirm-touch"), "following needs no confirmation")
        assertTrue(form.contains("""class="follow-btn is-follow""""))
        assertTrue(form.contains("""aria-label="Follow admin""""))
        assertTrue(form.contains(">Follow<") && form.contains(">Following<") && form.contains(">Unfollow<"),
            "all three labels are in the button, so it keeps one size")
    }

    @Test
    fun `following - an outlined Following button that posts following=false and asks first on touch screens`() {
        val form = followForm(community(people), 8)

        assertTrue(form.contains("""name="following" value="false""""))
        assertTrue(form.contains("""class="follow-btn is-following""""))
        assertTrue(form.contains("""aria-label="Following marisol, select to unfollow""""))
        assertTrue(form.contains("""data-confirm-touch="Unfollow marisol?""""))
        assertTrue(form.contains("""data-inplace-done="Unfollowed marisol.""""))
        assertFalse(form.contains("data-confirm=\""), "no confirmation with a mouse: the button already reads Unfollow")
    }

    @Test
    fun `an instance with nobody else shows an empty state instead of a list`() {
        val html = community(CommunityPeopleView(emptyList(), 0))

        assertTrue(html.contains("No one else has an account here yet."))
        assertFalse(html.contains("people-list"))
        assertFalse(html.contains("communityPeopleSummary"))
    }

    @Test
    fun `a follow error flashed by the redirect is shown on Community`() {
        val html = render("community", "/community",
            mapOf("feed" to emptyList<Any>(), "limit" to 10, "communityPeople" to people, "error" to "error.user.not.found"))

        assertTrue(html.contains("""<div class="feedback error">User not found.</div>"""))
    }

    private fun profile(isLoggedUser: Boolean, isFollowed: Boolean) = render(
        "profile", "/profile/8",
        mapOf(
            "followId" to 8L, "username" to "marisol", "email" to "m@example.com", "memberSince" to "2026-01-01",
            "currentLang" to "en", "currentMetadataLang" to "en", "isFollowed" to isFollowed, "isLoggedUser" to isLoggedUser,
            "feed" to emptyList<Any>(), "hasMore" to false, "limit" to 10, "lists" to emptyList<Any>(),
        ),
    )

    @Test
    fun `profiles use the same labelled button, swapping only itself`() {
        val notFollowing = followForm(profile(isLoggedUser = false, isFollowed = false), 8)
        assertTrue(notFollowing.contains("""class="follow-btn is-follow""""))
        assertTrue(notFollowing.contains("""name="back" value="/profile/8""""))
        assertTrue(notFollowing.contains("""data-inplace="#follow-8""""))

        val following = followForm(profile(isLoggedUser = false, isFollowed = true), 8)
        assertTrue(following.contains("""class="follow-btn is-following""""))
        assertTrue(following.contains("""data-confirm-touch="Unfollow marisol?""""))
    }

    @Test
    fun `your own profile has no follow button`() {
        val html = profile(isLoggedUser = true, isFollowed = false)

        assertFalse(html.contains("follow-btn"))
        assertFalse(html.contains("/follow-button.js"))
        assertEquals(-1, html.indexOf("/user/follow"))
    }
}
