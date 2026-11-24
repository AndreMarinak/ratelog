package org.ratelog.person

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.home.TitleMark
import org.ratelog.person.detail.PersonPage
import org.ratelog.person.detail.web.PersonPageView
import org.ratelog.person.search.SearchPersonView
import org.ratelog.watchlist.WatchStatus
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.LocalDate
import java.util.Locale

/** The person page and the search page's People row, rendered from the real templates with the real messages. */
class PersonTemplatesTest {
    private val appContext = StaticApplicationContext().apply { refresh() }
    private val engine = SpringTemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
            isCacheable = false
        })
        setTemplateEngineMessageSource(BrowseBarViewsTest.testMessageSource())
    }

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(template: String, selector: String, vars: Map<String, Any?>): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, setOf(selector), context)
    }

    private fun credit(id: Int, title: String, date: String?, department: String = ACTING, role: String? = null, type: MediaType = MediaType.movie) =
        PersonCredit(id, type, title, date?.let(LocalDate::parse), "/$id.jpg", department, role, 1.0, 10)

    private fun page(credits: List<PersonCredit>, bio: String? = "One. Two. Three. Four.", marks: Map<Pair<MediaType, Int>, TitleMark> = emptyMap()): PersonPage {
        val person = PersonDetails(31, "Tom Hanks", bio, LocalDate.parse("1956-07-09"), null, "Concord, California, USA", "/hanks.jpg", "Acting", credits)
        val departments = Filmography.departments(credits)
        val department = Filmography.pick(departments, null, person.knownForDepartment)
        val entries = department?.let { Filmography.entries(credits, it.name, FilmographyType.ALL, FilmographySort.NEWEST) }.orEmpty()
        return PersonPage(person, departments, department, FilmographyType.ALL, FilmographySort.NEWEST,
            FilmographyType.entries.associateWith { t -> entries.count { t.matches(it.type) } }, entries, marks)
    }

    private fun personHtml(page: PersonPage) =
        render("person", "article", mapOf("person" to PersonPageView.of(page, false, LocalDate.parse("2026-11-24"), Locale.ENGLISH)))

    @Test
    fun `the header shows photo, known for, born with age and place, bio with More`() {
        val html = personHtml(page(listOf(credit(13, "Forrest Gump", "1994-06-23", role = "Forrest Gump"))))

        assertTrue(html.contains("<h1 class=\"person-name\">Tom Hanks</h1>"), html)
        assertTrue(html.contains("https://image.tmdb.org/t/p/w185/hanks.jpg 185w, https://image.tmdb.org/t/p/h632/hanks.jpg 632w"), html)
        assertTrue(html.contains("Known for") && html.contains("<strong>Acting</strong>"), html)
        assertTrue(html.contains("July 9, 1956 (age 70)"), html)
        assertTrue(html.contains("Concord, California, USA"), html)
        assertTrue(html.contains("<p>One. Two. Three.</p>"), html)
        assertTrue(html.contains("<summary>More</summary>"), html)
        assertTrue(html.contains("<p>Four.</p>"), html)
    }

    @Test
    fun `the filmography has filters as in-place links and tiles with role, year and quick actions`() {
        val html = personHtml(page(
            listOf(
                credit(13, "Forrest Gump", "1994-06-23", role = "Forrest Gump"),
                credit(2574, "Bosom Buddies", "1980-11-27", role = "Kip Wilson", type = MediaType.tvshow),
                credit(99, "Toy Story 5", "2027-06-18", role = "Woody (voice)"),
                credit(4, "That Thing You Do!", "1996-10-04", "Directing", "Director"),
            ),
            marks = mapOf((MediaType.movie to 13) to TitleMark(WatchStatus.WATCHED, 8.5)),
        ))

        assertTrue(html.contains("id=\"personView\"") && html.contains("data-view"), html)
        assertTrue(html.contains("data-view-links=\"dept\"") && html.contains("data-view-links=\"type\"") && html.contains("data-view-links=\"sort\""), html)
        assertTrue(html.contains("href=\"/person/31?dept=directing\""), html)
        assertTrue(html.contains("href=\"/person/31?sort=popular\""), html)
        assertTrue(html.contains(">Most popular<"), html)
        assertTrue(html.contains("href=\"/movie/13\"") && html.contains("href=\"/tv/2574\""), html)
        assertTrue(html.contains("class=\"home-tile-meta person-tile-role\" title=\"Forrest Gump\">Forrest Gump</span>"), html)
        assertTrue(html.contains(">1994<"), html)
        assertTrue(html.contains("data-tile-actions"), html)
        assertTrue(html.contains("tile-rate-score\">8.5<"), html)
        assertTrue(html.contains("tile-upcoming"), html)
        // Newest first: Toy Story 5 (upcoming), then Forrest Gump, then Bosom Buddies.
        assertTrue(html.indexOf("Toy Story 5") < html.indexOf("Forrest Gump") && html.indexOf("Forrest Gump") < html.indexOf("Bosom Buddies"), html)
        assertFalse(html.contains("??"), html)
    }

    @Test
    fun `the appearances switch shows its count and pronoun, in place, off by default`() {
        val credits = listOf(credit(13, "Forrest Gump", "1994-06-23", role = "Forrest Gump"))
        val off = personHtml(page(credits).copy(appearanceCount = 84))
        assertTrue(off.contains("role=\"switch\"") && off.contains("aria-checked=\"false\"") && off.contains("data-view-links=\"appearances\""), off)
        assertTrue(off.contains("href=\"/person/31?appearances=1\""), off)
        assertTrue(off.contains(">Show 84 appearances as themselves<"), off)
        assertTrue(off.contains(">Talk shows, award shows and interviews<"), off)

        val her = page(credits).let { it.copy(person = it.person.copy(gender = 1), appearanceCount = 1, appearances = true) }
        val on = personHtml(her)
        assertTrue(on.contains(">Show 1 appearance as herself<") && on.contains("aria-checked=\"true\"") && on.contains("is-on"), on)

        assertFalse(personHtml(page(credits)).contains("data-appearances"))
    }

    @Test
    fun `one department is a heading, none says there are no credits`() {
        val one = personHtml(page(listOf(credit(13, "Forrest Gump", "1994-06-23"))))
        assertTrue(one.contains("class=\"person-credits-title\">Acting</h2>"), one)
        assertFalse(one.contains("data-view-links=\"dept\""), one)

        val none = personHtml(page(emptyList(), bio = null))
        assertTrue(none.contains("No credits on TMDB yet."), none)
        assertFalse(none.contains("person-bio"), none)
        assertFalse(none.contains("??"), none)
    }

    @Test
    fun `the People row links each person with their known-for line`() {
        val people = listOf(
            SearchPersonView.of(PersonSummary(31, "Tom Hanks", "/hanks.jpg", "Acting", listOf("Forrest Gump", "Toy Story"), 10.0)),
            SearchPersonView.of(PersonSummary(525, "Christopher Nolan", null, "Directing", emptyList(), 5.0)),
        )
        val html = render("fragments/search-people", "section", mapOf("people" to people))

        assertTrue(html.contains("href=\"/person/31\""), html)
        assertTrue(html.contains("Acting · Forrest Gump, Toy Story"), html)
        assertTrue(html.contains("href=\"/person/525\""), html)
        assertTrue(html.contains("class=\"search-person-initial\" aria-hidden=\"true\">C</span>"), html)
        assertTrue(html.contains(">Directing</span>"), html)
        assertEquals(2, Regex("class=\"search-person\"").findAll(html).count())
    }
}
