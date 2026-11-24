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

/** Seen's menu on the title page (Watched again), the watch count line and the notice, from the real fragments and messages. */
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

    private fun render(a: TitleActionsView, notice: RewatchNotice? = null, fragment: String = "rewatch"): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            setVariable("a", a)
            setVariable("titleActions", a)
            setVariable("rewatchNotice", notice)
        }
        return engine.process("fragments/title-actions", setOf(fragment), context)
    }

    private fun menu(html: String) = html.substringAfter("<template data-seen-menu>", "").substringBefore("</template>")

    @Test
    fun `a seen title's Seen button opens a menu - Watched again saves the panel in place, then Mark as not seen`() {
        val html = render(view(), fragment = "seen")
        assertTrue(html.contains("""aria-haspopup="menu""""), html)
        assertTrue(html.contains("""aria-expanded="false""""))
        val menu = menu(html)
        assertTrue(menu.contains("""role="menu""""), html)
        assertTrue(menu.contains("""action="/rewatch""""))
        assertTrue(menu.contains("""data-inplace=".title-panel""""))
        assertTrue(menu.contains("""name="tmdbId" value="438631""""))
        assertTrue(menu.contains("""name="type" value="movie""""))
        assertTrue(menu.contains("""name="back" value="/movie/438631""""))
        assertTrue(menu.indexOf(">Watched again<") in 0 until menu.indexOf(">Mark as not seen<"), "Watched again first")
        assertEquals(2, Regex("""role="menuitem"""").findAll(menu).count())
        assertFalse(menu.contains("stays seen"))
        assertFalse(html.contains("??"))
    }

    @Test
    fun `a rated movie's menu says why it stays seen instead of offering to unmark it, a rated show can still be unmarked`() {
        val movie = menu(render(view(rated = true, rewatch = RewatchSummary(1, nov24)), fragment = "seen"))
        assertFalse(movie.contains("Mark as not seen"), movie)
        assertTrue(movie.contains("You rated it, so it stays seen"))
        assertTrue(movie.contains("Seen 2× · last Nov 24, 2026"), "the count in the menu's head")
        assertEquals(1, Regex("""role="menuitem"""").findAll(movie).count())

        val show = menu(render(view(type = MediaType.tvshow, rated = true), fragment = "seen"))
        assertTrue(show.contains(">Mark as not seen<"), show)
        assertTrue(show.contains("Moves it back to Watching"))
        assertTrue(show.contains("""name="type" value="tv""""))
    }

    @Test
    fun `an unseen title's Seen just marks it seen, an upcoming one has no menu`() {
        val unseen = render(view(status = null), fragment = "seen")
        assertFalse(unseen.contains("aria-haspopup"), unseen)
        assertTrue(unseen.contains("data-seen-menu"), "the menu is there for when it is marked seen in place")
        assertFalse(render(view(date = LocalDate.parse("2026-12-18")), fragment = "seen").contains("data-seen-menu"))
    }

    @Test
    fun `the quiet line counts the first watch too, once there is a rewatch, and hides while un-seen`() {
        assertFalse(render(view()).contains("title-rewatch"), "no line before a rewatch")
        val html = render(view(rated = true, rewatch = RewatchSummary(1, nov24)))
        assertTrue(html.contains("Seen 2× · last Nov 24, 2026"), html)
        assertFalse(Regex("""class="title-rewatch"[^>]*hidden""").containsMatchIn(html))
        assertEquals("Nov 24, 2026", view(rewatch = RewatchSummary(3, nov24)).lastRewatch)
        assertEquals(4, view(rewatch = RewatchSummary(3, nov24)).watchCount)

        val unseen = render(view(status = null, rewatch = RewatchSummary(2, nov24)))
        assertTrue(Regex("""class="title-rewatch"[^>]*hidden""").containsMatchIn(unseen), "kept, not counted while unseen")
        assertFalse(render(view(date = LocalDate.parse("2026-12-18"), rewatch = RewatchSummary(1, nov24))).contains("title-rewatch"))
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
