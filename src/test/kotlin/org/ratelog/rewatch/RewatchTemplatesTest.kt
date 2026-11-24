package org.ratelog.rewatch

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.rewatch.log.RewatchNotice
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.TitleActionsView
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** The title page's "Watched again" block and its notice, rendered from the real fragment and messages. */
class RewatchTemplatesTest {
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
    private val today = LocalDate.parse("2026-11-24")
    private val nov24 = Instant.parse("2026-11-24T15:00:00Z").toEpochMilli()

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun view(
        status: WatchStatus? = WatchStatus.WATCHED,
        rated: Boolean = false,
        type: MediaType = MediaType.movie,
        date: LocalDate = LocalDate.parse("2021-09-15"),
        rewatch: RewatchSummary = RewatchSummary.NONE,
    ) = TitleActionsView.of(
        type, 42, 438631, status, rated, date, "Released", false, today, Locale.ENGLISH,
        onWatchlist = false, rewatch = rewatch, zone = ZoneOffset.UTC,
    )

    private fun render(a: TitleActionsView, notice: RewatchNotice? = null): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            setVariable("a", a)
            setVariable("titleActions", a)
            setVariable("rewatchNotice", notice)
        }
        return engine.process("fragments/title-actions", setOf("rewatch"), context)
    }

    @Test
    fun `a seen title gets Watched again, saving the panel in place, and no count before a rewatch`() {
        val html = render(view())
        assertTrue(html.contains("""action="/rewatch""""), html)
        assertTrue(html.contains("""data-inplace=".title-panel""""))
        assertTrue(html.contains("""name="tmdbId" value="438631""""))
        assertTrue(html.contains("""name="type" value="movie""""))
        assertTrue(html.contains("""name="back" value="/movie/438631""""))
        assertTrue(html.contains(">Watched again<"))
        assertFalse(Regex("""class="title-rewatch"[^>]*hidden""").containsMatchIn(html), "shown while seen")
        assertFalse(html.contains("title-rewatch-count"), "no line until there is a rewatch")
        assertFalse(html.contains("??"))
    }

    @Test
    fun `with a rewatch the quiet line counts the first watch too`() {
        val html = render(view(rated = true, rewatch = RewatchSummary(1, nov24)))
        assertTrue(html.contains("Seen 2× · last Nov 24, 2026"), html)
        assertEquals("Nov 24, 2026", view(rewatch = RewatchSummary(3, nov24)).lastRewatch)
        assertEquals(4, view(rewatch = RewatchSummary(3, nov24)).watchCount)
    }

    @Test
    fun `unseen titles render it hidden (title-actions js follows Seen), upcoming ones not at all`() {
        val unseen = render(view(status = null))
        assertTrue(Regex("""class="title-rewatch"[^>]*hidden""").containsMatchIn(unseen), unseen)

        val watching = render(view(status = WatchStatus.WATCHING, type = MediaType.tvshow, rated = true))
        assertTrue(Regex("""class="title-rewatch"[^>]*hidden""").containsMatchIn(watching), "a show you are watching is not seen")
        assertTrue(watching.contains("""name="type" value="tv""""))

        val upcoming = render(view(date = LocalDate.parse("2026-12-18")))
        assertFalse(upcoming.contains("title-rewatch"), upcoming)
    }

    @Test
    fun `the notice carries the toast text, an Undo form and the rating link`() {
        val html = render(view(), RewatchNotice("rewatch.notice.logged", "Dune", 7, "/movie/438631?rate=1", "rewatch.notice.rate.update"))
        assertTrue(html.contains("""<span data-toast-text>Logged a rewatch of Dune.</span>"""), html)
        assertTrue(html.contains("""action="/rewatch/delete""""))
        assertTrue(html.contains("""name="id" value="7""""))
        assertTrue(Regex("""<form[^>]*action="/rewatch/delete"[^>]*data-toast-action""").containsMatchIn(html))
        assertTrue(html.contains(">Undo<"))
        assertTrue(html.contains("""href="/movie/438631?rate=1""""))
        assertTrue(html.contains("data-toast-click="))
        assertTrue(html.contains(">Update your rating?<"))

        val removed = render(view(), RewatchNotice("rewatch.notice.removed", "Dune"))
        assertTrue(removed.contains("Removed the rewatch of Dune."))
        assertFalse(removed.contains("/rewatch/delete"))
        assertFalse(removed.contains("data-toast-click"))
    }
}
