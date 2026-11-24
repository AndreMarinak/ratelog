package org.ratelog.watchlist.seen

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.getwatchlist.WatchlistController
import org.ratelog.watchlist.getwatchlist.WatchlistItemResponse
import org.ratelog.watchlist.getwatchlist.WatchlistTabResponse
import org.ratelog.watchlist.move.WatchlistNotice
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.LocalDate
import java.util.Locale

/** Renders the real fragments with the real messages. */
class SeenTemplatesTest {

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
    private val csrf = DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123")
    private val today = LocalDate.parse("2026-11-23")

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(template: String, fragment: String?, vars: Map<String, Any?>, fragments: Set<String>? = null): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", csrf)
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, fragments ?: setOf(fragment!!), context)
    }

    private fun tile(status: WatchStatus? = null, rated: Boolean = false, date: LocalDate? = LocalDate.parse("2001-01-01"), type: MediaType = MediaType.movie, onWatchlist: Boolean = false) =
        TileActionsView.of(603, type, status, rated, date, today, Locale.ENGLISH, onWatchlist = onWatchlist)

    private fun title(type: MediaType = MediaType.movie, status: WatchStatus? = null, rated: Boolean = false, date: LocalDate? = LocalDate.parse("1994-06-24"), tmdbStatus: String? = "Released", followed: Boolean = false, onWatchlist: Boolean = false) =
        TitleActionsView.of(
            type, 42, 8587, status?.takeIf { it != WatchStatus.WANT_TO_WATCH }, rated, date, tmdbStatus, followed, today, Locale.ENGLISH,
            onWatchlist = onWatchlist || status == WatchStatus.WANT_TO_WATCH,
        )

    // --- Tiles ---

    @Test
    fun `tile actions are two plain POST forms with CSRF, a key and accessible labels`() {
        val html = render("fragments/tile-actions", "actions", mapOf("a" to tile(), "title" to "The Matrix"))

        assertTrue(html.contains("""data-key="movie:603""""), html)
        assertTrue(html.contains("""action="/watchlist/toggle""""))
        assertTrue(html.contains("""action="/seen/toggle""""))
        assertEquals(2, Regex("""name="_csrf" value="tok123"""").findAll(html).count())
        assertEquals(2, Regex("""name="tmdbId" value="603"""").findAll(html).count())
        assertTrue(html.contains("""aria-label="Add to watchlist: The Matrix""""))
        assertTrue(html.contains("""aria-label="Mark as seen: The Matrix""""))
        assertTrue(html.contains("""aria-pressed="false""""))
        assertFalse(Regex("""<form[^>]*\shidden[\s>=]""").containsMatchIn(html), "both visible")
        assertFalse(html.contains("??"))
    }

    @Test
    fun `a watched tile shows the eye on and the bookmark too (fork, 2026-11-24)`() {
        val html = render("fragments/tile-actions", "actions", mapOf("a" to tile(WatchStatus.WATCHED), "title" to "Heat"))
        assertFalse(Regex("""<form class="tile-act tile-watchlist[^"]*"[^>]*\shidden[\s>=]""").containsMatchIn(html), html)
        assertTrue(html.contains("""aria-label="Add to watchlist: Heat""""))
        assertTrue(html.contains("""class="tile-act tile-seen is-on""""))
        assertTrue(html.contains("""aria-label="Seen (tap to unmark): Heat""""))
    }

    @Test
    fun `watching shows a badge, upcoming has no eye`() {
        val watching = render("fragments/tile-actions", "actions", mapOf("a" to tile(WatchStatus.WATCHING, type = MediaType.tvshow), "title" to "The Bear"))
        assertTrue(watching.contains("tile-badge is-watching"))
        assertTrue(watching.contains("""aria-label="Watching""""))
        assertTrue(watching.contains("""name="type" value="tv""""))

        val upcoming = render("fragments/tile-actions", "actions", mapOf("a" to tile(WatchStatus.WANT_TO_WATCH, date = LocalDate.parse("2026-12-18")), "title" to "Doomsday"))
        assertFalse(upcoming.contains("tile-seen"))
        assertTrue(upcoming.contains("tile-watchlist is-on"))
    }

    @Test
    fun `the Upcoming badge renders only for upcoming titles`() {
        val badge = render("fragments/tile-actions", "upcoming", mapOf("a" to tile(date = LocalDate.parse("2026-12-18"))))
        assertTrue(badge.contains(">Upcoming<"))
        assertTrue(badge.contains(">Dec 18<"))
        assertFalse(render("fragments/tile-actions", "upcoming", mapOf("a" to tile())).contains("tile-upcoming"))
        assertFalse(render("fragments/tile-actions", "upcoming", mapOf("a" to null)).contains("tile-upcoming"))
        assertFalse(render("fragments/tile-actions", "actions", mapOf("a" to null, "title" to "x")).contains("tile-actions"))
    }

    // --- Title page ---

    @Test
    fun `seen on an unrated movie posts to the toggle, with the nudge ready but hidden`() {
        val a = title()
        val seen = render("fragments/title-actions", "seen", mapOf("a" to a))
        assertTrue(seen.contains("""action="/seen/toggle""""))
        assertTrue(seen.contains("""data-title-act="seen""""))
        assertTrue(seen.contains("""name="back" value="/movie/8587""""))
        assertTrue(seen.contains(">Mark seen<"))
        assertTrue(Regex("""class="title-toggle-note" hidden="hidden"""").containsMatchIn(seen))

        val nudge = render("fragments/title-actions", "rateNudge", mapOf("a" to a))
        assertTrue(Regex("""<div class="title-nudge" data-rate-nudge hidden""").containsMatchIn(nudge), nudge)
        assertTrue(nudge.contains("""action="/movie/rate""""))
        assertTrue(nudge.contains("""name="movieId" value="42""""))
        assertTrue(nudge.contains("""name="tmdbId" value="8587""""))
        assertEquals(10, Regex("""name="score" value="\d+"""").findAll(nudge).count())
        assertTrue(nudge.contains("Rate it?"))
        assertTrue(nudge.contains(">Skip<"))
        assertTrue(nudge.contains("""aria-label="Rate it 7 out of 10""""))
    }

    @Test
    fun `no nudge for rated movies or shows`() {
        assertFalse(render("fragments/title-actions", "rateNudge", mapOf("a" to title(rated = true))).contains("title-nudge"))
        assertFalse(render("fragments/title-actions", "rateNudge", mapOf("a" to title(type = MediaType.tvshow))).contains("title-nudge"))
    }

    @Test
    fun `a rated movie shows Seen, locked, with the note`() {
        val html = render("fragments/title-actions", "seen", mapOf("a" to title(rated = true)))
        assertTrue(html.contains("is-on is-locked"))
        assertTrue(html.contains(">Seen<"))
        assertTrue(html.contains(">Rated<"))
        assertFalse(Regex("""class="title-toggle-note" hidden""").containsMatchIn(html))
    }

    @Test
    fun `an upcoming movie gets the Out tile and the Upcoming line instead of Seen`() {
        val a = title(date = LocalDate.parse("2026-12-18"), tmdbStatus = "Post Production")
        val seen = render("fragments/title-actions", "seen", mapOf("a" to a))
        assertFalse(seen.contains("/seen/toggle"))
        assertTrue(seen.contains("Out Dec 18"))
        val upcoming = render("fragments/title-actions", "upcoming", mapOf("a" to a))
        assertTrue(upcoming.contains(">Upcoming<"))
        assertTrue(upcoming.contains(">Dec 18, 2026<"))
    }

    @Test
    fun `the watchlist toggle never asks, a seen title included (fork, 2026-11-24)`() {
        val plain = render("fragments/title-actions", "watchlist", mapOf("a" to title()))
        assertTrue(plain.contains("""action="/watchlist/set""""), plain)
        assertTrue(plain.contains("""name="on" value="1""""))
        assertFalse(plain.contains("data-confirm"))

        val seen = render("fragments/title-actions", "watchlist", mapOf("a" to title(status = WatchStatus.WATCHED)))
        assertFalse(seen.contains("data-confirm"), seen)
        assertTrue(seen.contains("""name="on" value="1""""))

        val rewatch = render("fragments/title-actions", "watchlist", mapOf("a" to title(status = WatchStatus.WATCHED, onWatchlist = true)))
        assertTrue(rewatch.contains("""name="on" value="0""""), "tapping again takes it off (the status stays)")
        assertTrue(rewatch.contains(">In watchlist<"))

        val on = render("fragments/title-actions", "watchlist", mapOf("a" to title(status = WatchStatus.WANT_TO_WATCH)))
        assertTrue(on.contains("""name="on" value="0""""), "tapping again takes it off")
        assertTrue(on.contains(">In watchlist<"))
    }

    @Test
    fun `extras - the bell only where it does something, Watching and Dropped only for shows`() {
        assertFalse(render("fragments/title-actions", "extras", mapOf("a" to title())).contains("title-extras"), "released movie: nothing")

        val upcoming = render("fragments/title-actions", "extras", mapOf("a" to title(date = LocalDate.parse("2026-12-18"))))
        assertTrue(upcoming.contains("""action="/movie/follow""""))
        assertTrue(upcoming.contains("""name="movieId" value="42""""))
        assertTrue(upcoming.contains("Notify me"))
        assertTrue(upcoming.contains("Adds release dates to Premieres"))
        assertTrue(upcoming.contains("""data-inplace=".title-panel""""), "saved in place by inplace.js")
        assertFalse(upcoming.contains("title-progress"))

        val show = render("fragments/title-actions", "extras", mapOf("a" to title(type = MediaType.tvshow, status = WatchStatus.WATCHING, tmdbStatus = "Returning Series")))
        assertTrue(show.contains("""action="/tvshow/follow""""))
        assertTrue(show.contains("""name="tvShowId" value="42""""))
        assertTrue(show.contains("""data-progress-value="watching""""))
        assertTrue(Regex("""name="status" value="" class="title-chip is-on" data-progress-value="watching"""").containsMatchIn(show), show)
        assertTrue(Regex("""name="status" value="dropped" class="title-chip" data-progress-value="dropped"""").containsMatchIn(show), show)

        val ended = render("fragments/title-actions", "extras", mapOf("a" to title(type = MediaType.tvshow, tmdbStatus = "Ended")))
        assertTrue(ended.contains("title-progress"))
        assertFalse(ended.contains("title-follow"))

        val followed = render("fragments/title-actions", "extras", mapOf("a" to title(followed = true)))
        assertTrue(followed.contains("Following"), "a followed released movie keeps its bell to unfollow")

        val dropped = render("fragments/title-actions", "extras", mapOf("a" to title(status = WatchStatus.DROPPED)))
        assertTrue(dropped.contains("Marked Dropped"))
        assertTrue(dropped.contains(">Clear<"))
        assertFalse(dropped.contains("??"))
    }

    // --- Watchlist page ---

    @Test
    fun `watchlist tiles carry remove, seen and watching forms back to the same tab, and the notice offers undo`() {
        val tabs = listOf(
            WatchlistTabResponse("watchlist.tab.want_to_watch", "bookmark", 2, "/watchlist?status=want_to_watch", true, "want_to_watch"),
            WatchlistTabResponse("watchlist.tab.watched", "eye", 0, "/watchlist?status=watched", false, "watched"),
        )
        val item = WatchlistItemResponse(
            href = "/tv/136315", isMovie = false, title = "The Bear", year = 2022, posterPath = null, score = null,
            mediaType = "tvshow", mediaId = 7, upcomingDate = null,
            actions = WatchlistController.actionsFor(WatchStatus.WANT_TO_WATCH, isTv = true, upcoming = false, rated = false),
        )
        val doom = WatchlistItemResponse(
            href = "/movie/1003596", isMovie = true, title = "Avengers: Doomsday", year = 2026, posterPath = null, score = null,
            mediaType = "movie", mediaId = 8, upcomingDate = "Dec 18",
            actions = WatchlistController.actionsFor(WatchStatus.WANT_TO_WATCH, isTv = false, upcoming = true, rated = false),
        )
        val html = render("watchlist", null, fragments = setOf("notice", "toolbar", "empty", "grid"), vars = mapOf(
            "tabs" to tabs, "typeFilters" to emptyList<Any>(), "items" to listOf(item, doom),
            "emptyKey" to "watchlist.tab.empty.want_to_watch", "tabHintKey" to "watchlist.tab.hint.want_to_watch",
            "watchlistSelf" to "/watchlist?status=want_to_watch&type=tv",
            "watchlistNotice" to WatchlistNotice("watchlist.notice.removed.want_to_watch", "Up", "movie", 3, "want_to_watch"),
        ))

        assertTrue(html.contains(">To watch<"))
        assertTrue(html.contains(">Seen<"))
        assertTrue(html.contains("""data-status="want_to_watch""""))
        assertTrue(Regex("""class="empty-state watchlist-empty" hidden""").containsMatchIn(html))
        assertEquals(4, Regex("""action="/watchlist/item"""").findAll(html).count() - 1, "Seen, Watching, Remove for The Bear + Remove for Doomsday (+ the undo form)")
        assertTrue(html.contains("""aria-label="Remove &quot;The Bear&quot; from your watchlist"""") || html.contains("""aria-label="Remove "The Bear" from your watchlist""""), html)
        assertTrue(html.contains("""name="back" value="/watchlist?status=want_to_watch&amp;type=tv""""))
        assertTrue(html.contains("""name="to" value="watching""""))
        assertTrue(html.contains("Upcoming"))
        assertTrue(html.contains("Removed &quot;Up&quot; from your watchlist.") || html.contains("Removed \"Up\" from your watchlist."))
        assertTrue(html.contains("""name="undo" value="1""""))
        assertFalse(html.contains("??"))
    }
}
