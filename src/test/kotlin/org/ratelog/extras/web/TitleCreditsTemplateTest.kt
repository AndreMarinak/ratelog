package org.ratelog.extras.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.extras.CastMember
import org.ratelog.extras.CreditPerson
import org.ratelog.extras.TitleCredits
import org.ratelog.extras.gettitleinfo.TitleInfo
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale

/**
 * Credits on title pages (fragments/title-credits.html) with the real messages: the desktop Details and Cast cards,
 * the tablet / phone block, and names that only link to person pages when `ratelog.features.people` is on.
 */
class TitleCreditsTemplateTest {

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

    private val godfather = TitleCredits(
        tagline = "An offer you can't refuse.",
        certification = "R",
        directors = listOf(CreditPerson(1776, "Francis Ford Coppola")),
        writers = listOf(
            CreditPerson(3083, "Mario Puzo", listOf("Novel")),
            CreditPerson(1776, "Francis Ford Coppola", listOf("Screenplay")),
        ),
        cast = (1..10).map { CastMember(100 + it, "Actor $it", "Role $it", if (it == 2) null else "/p$it.jpg") },
        studios = listOf("Paramount Pictures", "Alfran Productions", "Albert S. Ruddy Productions"),
        budget = 6_000_000,
        revenue = 245_066_411,
    )

    private val breakingBad = TitleCredits(
        tagline = "Change the equation.",
        certification = "TV-MA",
        createdBy = listOf(CreditPerson(66633, "Vince Gilligan")),
        cast = listOf(CastMember(17419, "Bryan Cranston", "Walter White", "/7J.jpg", 62)),
        networks = listOf("AMC"),
        firstAirYear = 2008,
        lastAirYear = 2013,
        numberOfEpisodes = 62,
        status = "Ended",
    )

    private fun info(credits: TitleCredits?, type: MediaType = MediaType.movie) = TitleInfoView(
        TitleInfo(
            type = type, tmdbId = 238, title = "The Godfather", tmdbVoteAverage = 8.7, tmdbVoteCount = 21_000,
            runtimeMinutes = 175, numberOfSeasons = null, trailerYoutubeKey = null, imdbId = null,
            credits = credits, scores = null, scoresFetchedAt = null,
        )
    )

    private fun render(fragment: String, view: TitleInfoView?, people: Boolean = false): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("info", view)
            setVariable("peopleEnabled", people)
        }
        return engine.process("fragments/title-credits", setOf(fragment), context)
            .also { assertFalse(it.contains("??"), "every message key resolves: $it") }
    }

    private fun count(html: String, pattern: String) = Regex(pattern).findAll(html).count()

    @Test
    fun `desktop gets a Details card and a Cast card with six names showing and a Show all 10 toggle`() {
        val html = render("side", info(godfather))

        assertTrue(html.contains("""class="title-about title-about-details""""), html)
        assertTrue(html.contains(">Director</dt>"))
        assertTrue(html.contains(">Writers</dt>"))
        assertTrue(html.contains("""Mario Puzo</span><span class="credit-job">(novel)</span>, """), html)
        assertTrue(html.contains(">Studios</dt><dd>Paramount Pictures, Alfran Productions</dd>"), "two studios at most: $html")
        assertTrue(html.contains(">Budget</dt><dd>$6M</dd>"))
        assertTrue(html.contains(">Box office</dt><dd>$245.1M</dd>"))

        assertTrue(html.contains("""class="title-about title-about-cast""""))
        assertEquals(10, count(html, """<li class="cast-line"""))
        assertEquals(4, count(html, """<li class="cast-line is-extra""""), "names 7 to 10 fold away")
        assertTrue(html.contains("""data-more="Show all 10""""))
        assertTrue(html.contains("""<button type="button" class="cast-more" aria-expanded="false" hidden"""), "revealed by title-credits.js")
        assertTrue(html.contains("""<script src="/title-credits.js" defer></script>"""))
        assertTrue(html.contains("""src="https://image.tmdb.org/t/p/w185/p1.jpg" srcset="https://image.tmdb.org/t/p/w45/p1.jpg 1x, https://image.tmdb.org/t/p/w185/p1.jpg 2x""""), html)
        assertTrue(html.contains("""<span class="cast-photo cast-initial" aria-hidden="true">A</span>"""), "no photo: an initial")
    }

    @Test
    fun `with person pages off names are plain text carrying their TMDB id, with them on they link`() {
        val off = render("side", info(godfather)) + render("mobile", info(godfather))
        assertFalse(off.contains("<a "), "no links while person pages don't exist")
        assertFalse(off.contains("/person/"))
        assertTrue(off.contains("""<span class="person-name" data-person-id="1776">Francis Ford Coppola</span>"""), off)
        assertTrue(off.contains("""<span class="person-name" data-person-id="101">Actor 1</span>"""))

        val on = render("side", info(godfather), people = true)
        assertTrue(on.contains("""<a class="person-link" href="/person/1776" data-person-id="1776">Francis Ford Coppola</a>"""), on)
        assertTrue(on.contains("""href="/person/101""""))
    }

    @Test
    fun `tablet and phone get one credit line, a cast row with every name and a collapsed More details`() {
        val html = render("mobile", info(godfather))

        assertTrue(html.contains("""class="title-credits-mobile""""), html)
        assertTrue(html.contains(""">Directed by</span>"""))
        assertTrue(html.contains(""">Written by</span>"""))
        assertTrue(html.contains("""<ul class="cast-row" tabindex="0" aria-label="Cast">"""))
        assertEquals(10, count(html, """<li class="cast-chip">"""))
        assertFalse(html.contains("srcset"), "64px photos use w185 at every density")
        assertTrue(html.contains("""<details class="title-more-details">"""))
        assertFalse(html.contains("<details class=\"title-more-details\" open"), "collapsed")
        assertTrue(html.contains(">More details</span>"))
        assertTrue(html.contains(">Box office</dt>"))
        assertFalse(html.contains(">Writers</dt>"), "two writers fit on the line")
    }

    @Test
    fun `more than three writers then the line says how many more and More details lists them all`() {
        val many = godfather.copy(writers = (1..5).map { CreditPerson(it, "Writer $it", listOf("Writer")) })
        val html = render("mobile", info(many))

        assertTrue(html.contains("""<span class="credit-more">+2 more</span>"""), html)
        assertTrue(html.contains(">Writers</dt>"))
        assertEquals(1, count(html, ">Writer 5<"), "only under More details")
        assertFalse(html.contains("""class="credit-job""""), "a plain Writer credit has no job label")
    }

    @Test
    fun `a show gets Created by, network, the years it aired, episodes and episode counts per actor`() {
        val tv = info(breakingBad, MediaType.tvshow)
        val side = render("side", tv)
        val mobile = render("mobile", tv)

        assertTrue(side.contains(">Created by</dt>"), side)
        assertTrue(side.contains(">Network</dt><dd>AMC</dd>"))
        assertTrue(side.contains(">Aired</dt>"))
        assertTrue(side.contains("2008–2013"))
        assertTrue(side.contains(">Episodes</dt><dd>62</dd>"))
        assertTrue(side.contains("""Walter White<span class="cast-eps"><span class="sep"> · </span>62 ep</span>"""), side)
        assertFalse(side.contains("cast-more"), "one actor: nothing to expand")
        assertFalse(side.contains("title-credits.js"))

        assertTrue(mobile.contains(">Created by</span>"), mobile)
        assertFalse(mobile.contains("Directed by"))
    }

    @Test
    fun `a running show says since when, without thousands separators`() {
        val html = render("side", info(breakingBad.copy(status = "Returning Series", firstAirYear = 2019, lastAirYear = 2025), MediaType.tvshow))
        assertTrue(html.contains("2019–present"), html)
    }

    @Test
    fun `the age rating chip and the tagline`() {
        assertEquals("""<span class="title-cert" title="Rated R (US)">R</span>""", render("certification", info(godfather)).trim())
        assertEquals("""<p class="title-tagline">An offer you can&#39;t refuse.</p>""", render("tagline", info(godfather)).trim())
    }

    @Test
    fun `missing data renders nothing, never an empty card`() {
        for (view in listOf(null, info(null), info(TitleCredits()))) {
            for (fragment in listOf("side", "mobile", "certification", "tagline")) {
                assertEquals("", render(fragment, view).trim(), "$fragment for $view")
            }
        }

        // Only a tagline: no cards, no phone block
        val taglineOnly = info(TitleCredits(tagline = "Just this."))
        assertEquals("", render("side", taglineOnly).trim())
        assertEquals("", render("mobile", taglineOnly).trim())
        assertTrue(render("tagline", taglineOnly).contains("Just this."))

        // Cast without crew or facts: only the Cast card / cast row
        val castOnly = info(TitleCredits(cast = listOf(CastMember(null, "Someone", null, null))))
        val side = render("side", castOnly)
        assertFalse(side.contains("title-about-details"))
        assertTrue(side.contains("title-about-cast"))
        assertFalse(side.contains("cast-role"), "no character: no empty line")
        val mobile = render("mobile", castOnly)
        assertFalse(mobile.contains("title-credit-line"))
        assertFalse(mobile.contains("<details"))
        assertTrue(mobile.contains("cast-row"))
    }
}
