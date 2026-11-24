package org.ratelog.watchlist.getwatchlist

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.TvShowFactory
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.LocalDate
import java.util.Locale

/**
 * The /watchlist toolbar as rendered from the controller's real model (user, 2026-11-23: "watchlist tab should default
 * to movies like everything else"): Movies | TV only, Movies selected without a type, the type kept on every tab.
 */
class WatchlistTypeDefaultTemplateTest {

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

    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val page = WatchlistController(
        GetWatchlistHandler(statuses, movies, InMemoryMovieDescriptionRepository(), InMemoryRatingRepository(), shows, InMemoryTvDescriptionRepository(), InMemoryTvRatingRepository()),
        MutableClock(),
    )
    private val user = UserFactory.aUser(id = 1)

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        val dune = movies.save(MovieFactory.aMovie(tmdbId = 693134, originalTitle = "Dune: Part Two", releaseDate = LocalDate.parse("2024-02-27")))
        val bear = shows.save(TvShowFactory.aTvShow(tmdbId = 136315, originalName = "The Bear", firstAirDate = LocalDate.parse("2022-06-23")))
        statuses.save(MediaStatus(user.id!!, MediaType.movie, dune.id!!.value, WatchStatus.WANT_TO_WATCH, 1L))
        statuses.save(MediaStatus(user.id!!, MediaType.tvshow, bear.id!!.value, WatchStatus.WATCHING, 2L))
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(status: String?, type: String?): String {
        val model = ExtendedModelMap()
        page.watchlistPage(user, status, type, model)
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            model.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process("watchlist", setOf("toolbar", "empty", "grid"), context)
            .also { assertFalse(it.contains("??"), "every message key resolves: $it") }
    }

    private fun typeLinks(html: String): List<String> =
        Regex("""<nav class="status-segmented" aria-label="Filter by type"[^>]*>(.*?)</nav>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
            .let { Regex("""<a [^>]*>[^<]*</a>""").findAll(it).map { m -> m.value }.toList() }

    @Test
    fun `without a type the toolbar is Movies or TV with Movies selected, and every tab keeps type=movie`() {
        val html = render(null, null)

        val links = typeLinks(html)
        assertEquals(2, links.size, "no All: $links")
        assertTrue(links[0].contains(">Movies<") && links[0].contains("active") && links[0].contains("""aria-current="page""""), links[0])
        assertTrue(links[1].contains(">TV<") && !links[1].contains("aria-current"), links[1])
        assertTrue(links[1].contains("""href="/watchlist?status=want_to_watch&amp;type=tv""""), links[1])
        assertFalse(html.contains(">All<"))
        assertEquals(5, Regex("""href="/watchlist\?status=\w+&amp;type=movie" class="status-segment""").findAll(html).count(),
            "the four status tabs keep Movies, plus the Movies link itself")
        assertTrue(html.contains("Dune: Part Two"))
        assertFalse(html.contains("The Bear"))
        assertTrue(html.contains("""data-view-links="type""""), "the switch still applies in place")
    }

    @Test
    fun `TV shows Watching, and the Movies Watching tab explains that Watching is for shows`() {
        val tv = render("watching", "tv")
        assertTrue(tv.contains("The Bear"))
        assertTrue(typeLinks(tv)[1].contains("aria-current"))
        assertTrue(tv.contains(">Watching<"))

        val moviesWatching = render("watching", "movie")
        assertTrue(moviesWatching.contains(">Watching<"), "the tab stays")
        assertTrue(moviesWatching.contains("Only TV shows can be Watching. Switch to TV for the shows you&#39;re in the middle of."), moviesWatching)
        assertFalse(Regex("""class="empty-state watchlist-empty" hidden""").containsMatchIn(moviesWatching), "the empty state shows")
    }
}
