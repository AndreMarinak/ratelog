package org.ratelog.backnav.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.backnav.BackNavLabel
import org.ratelog.backnav.BackNavLink
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale

/** Renders the real back-link fragments with the real messages; the server's link must work without any JS. */
class BackNavTemplatesTest {

    private val messages = ResourceBundleMessageSource().apply {
        setBasenames("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    private val appContext = StaticApplicationContext().apply { refresh() }
    private val engine = SpringTemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
            isCacheable = false
        })
        setTemplateEngineMessageSource(messages)
    }

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(fragment: String, vars: Map<String, Any?>): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process("fragments/back-nav", setOf(fragment), context)
            .also { assertFalse(it.contains("??"), "every message key resolves: $it") }
    }

    private fun link(back: BackNavLink?) = render("link", mapOf("back" to back))
    private fun page(label: BackNavLabel?) = render("page", mapOf("label" to label))

    // --- Title pages ---

    @Test
    fun `the default is a plain link Home that works without JS`() {
        val html = link(BackNavLink("/", BackNavLabel.HOME, "Toy Story 3"))

        assertTrue(html.contains("""<link rel="stylesheet" href="/back-nav.css">"""))
        assertTrue(html.contains("""<nav class="back-nav" aria-label="Back" data-back-nav"""), html)
        assertTrue(html.contains("""<a class="back-nav-link" href="/" aria-label="Back to Home" data-back-origin>"""), html)
        assertTrue(html.contains("""data-lucide="arrow-left""""))
        assertTrue(html.contains("""<span class="back-nav-text" data-back-text>Home</span>"""), html)
        assertTrue(html.contains("""data-title="Toy Story 3""""))
        assertTrue(html.contains("""data-to="Back to {0}""""), "the script's template for its own labels")
        assertFalse(html.contains("data-pending"))
    }

    @Test
    fun `the extra step is rendered hidden and without a target, so without JS there is only one link`() {
        val html = link(BackNavLink("/", BackNavLabel.HOME, "Toy Story 3"))

        assertTrue(html.contains("""<span class="back-nav-step" data-back-step hidden>"""), html)
        assertTrue(html.contains("""<a class="back-nav-link back-nav-prev" data-back-prev>"""), html)
        assertEquals(1, Regex("""<a [^>]*href=""").findAll(html).count(), html)
    }

    @Test
    fun `a Referer page keeps its full URL, and names are escaped`() {
        val discover = link(BackNavLink("/discover?type=movie&genre=27", BackNavLabel.DISCOVER, "Coco"))
        assertTrue(discover.contains("""href="/discover?type=movie&amp;genre=27""""), discover)
        assertTrue(discover.contains(""">Discover</span>"""))
        assertTrue(discover.contains("""aria-label="Back to Discover""""))

        val list = link(BackNavLink("/list/7", BackNavLabel.list("Tom & Jerry's <best>"), "Coco"))
        assertTrue(list.contains(""">Tom &amp; Jerry&#39;s &lt;best&gt;</span>"""), list)
        assertTrue(list.contains("""aria-label="Back to Tom &amp; Jerry&#39;s &lt;best&gt;""""), list)

        val profile = link(BackNavLink("/profile/2", BackNavLabel.profile("marisol"), "Coco"))
        assertTrue(profile.contains(""">marisol&#39;s profile</span>"""), profile)

        assertTrue(link(BackNavLink("/?q=toy", BackNavLabel.SEARCH, "Coco")).contains(""">Search results</span>"""))
        assertTrue(link(BackNavLink("/profile/1", BackNavLabel.OWN_PROFILE, "Coco")).contains(""">Your profile</span>"""))
        assertTrue(link(BackNavLink("/lists/official", BackNavLabel.OFFICIAL_LISTS, "Coco")).contains(""">Official lists</span>"""))
    }

    @Test
    fun `opened from another title, the link waits for the script`() {
        val html = link(BackNavLink("/", BackNavLabel.HOME, "Coco", pending = true))
        assertTrue(html.contains("""data-pending="true""""), html)
    }

    @Test
    fun `no link without a title`() {
        val html = link(null)
        assertFalse(html.contains("back-nav"), html)
    }

    // --- Every page: the script and the page's own name ---

    @Test
    fun `every page loads the script and names itself when it is a page to come back to`() {
        val discover = page(BackNavLabel.DISCOVER)
        assertTrue(discover.contains("""<script src="/back-nav.js" defer data-back-nav-page data-label="Discover"></script>"""), discover)

        val list = page(BackNavLabel.list("Best \"Disney\" Movies"))
        assertTrue(list.contains("""data-label="Best &quot;Disney&quot; Movies""""), list)

        val profile = page(BackNavLabel.profile("marisol"))
        assertTrue(profile.contains("""data-label="marisol&#39;s profile""""), profile)

        val settings = page(null)
        assertTrue(settings.contains("""<script src="/back-nav.js" defer data-back-nav-page></script>"""), settings)
    }

    // --- Include lines ---

    @Test
    fun `the nav includes the script on every page and both title pages show the link above the layout`() {
        fun source(path: String) = javaClass.classLoader.getResource("templates/$path")!!.readText()

        assertTrue(source("fragments/nav.html").contains("~{fragments/back-nav :: page(\${backNavPage})}"))
        listOf("movie-detail.html", "tvshow-detail.html").forEach { page ->
            val html = source(page)
            val include = html.indexOf("~{fragments/back-nav :: link(\${backNav})}")
            assertTrue(include > 0, page)
            assertTrue(include < html.indexOf("class=\"title-layout\""), "$page: the link sits above the title")
            assertEquals(1, Regex("fragments/back-nav").findAll(html).count(), page)
        }
    }
}
