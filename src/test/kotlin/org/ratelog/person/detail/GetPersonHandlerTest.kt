package org.ratelog.person.detail

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.home.TitleMark
import org.ratelog.person.ACTING
import org.ratelog.person.FilmographySort
import org.ratelog.person.FilmographyType
import org.ratelog.person.PersonCredit
import org.ratelog.person.PersonDetails
import org.ratelog.person.PersonLookupError
import org.ratelog.person.TmdbPersonClient
import org.ratelog.person.detail.web.PersonPageView
import org.ratelog.test.InMemoryHomeRepository
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate
import java.util.Locale

class GetPersonHandlerTest {
    private val client: TmdbPersonClient = mock()
    private val home = InMemoryHomeRepository()
    private val handler = GetPersonHandler(client, home)
    private val me = User.Id(1)
    private val today = LocalDate.parse("2026-11-24")

    private fun credit(id: Int, title: String, date: String?, department: String = ACTING, role: String? = null, type: MediaType = MediaType.movie, votes: Int = 10) =
        PersonCredit(id, type, title, date?.let(LocalDate::parse), "/$id.jpg", department, role, 1.0, votes)

    private val hanks = PersonDetails(
        id = 31, name = "Tom Hanks",
        biography = "Thomas Jeffrey Hanks (born July 9, 1956) is an American actor. He is known for comedy. Hanks won two Oscars. He also directs.\n\nSecond paragraph.",
        birthday = LocalDate.parse("1956-07-09"), deathday = null, placeOfBirth = "Concord, California, USA",
        profilePath = "/hanks.jpg", knownForDepartment = "Acting",
        credits = listOf(
            credit(13, "Forrest Gump", "1994-06-23", role = "Forrest Gump", votes = 30000),
            credit(862, "Toy Story", "1995-11-22", role = "Woody (voice)", votes = 18000),
            credit(2574, "Bosom Buddies", "1980-11-27", role = "Kip Wilson", type = MediaType.tvshow),
            credit(1000001, "Toy Story 5", "2027-06-18", role = "Woody (voice)"),
            credit(4, "That Thing You Do!", "1996-10-04", "Directing", "Director"),
            credit(4, "That Thing You Do!", "1996-10-04", "Writing", "Writer"),
        ),
    )

    private fun query(department: String? = null, type: FilmographyType = FilmographyType.ALL, sort: FilmographySort = FilmographySort.NEWEST) =
        GetPersonQuery(31, me, Lang.en, department, type, sort)

    @Test
    fun `the known-for department is shown first, newest first, with the viewer's marks`() {
        whenever(client.person(31, Lang.en)).thenReturn(hanks.right())
        home.setMark(me, MediaType.movie, 13, TitleMark(WatchStatus.WATCHED, 9.0))
        home.setMark(me, MediaType.tvshow, 2574, TitleMark(WatchStatus.WANT_TO_WATCH, null))

        val page = handler.handle(query()).getOrNull()!!

        assertEquals("acting", page.department?.slug)
        assertEquals(listOf("Toy Story 5", "Toy Story", "Forrest Gump", "Bosom Buddies"), page.entries.map { it.title })
        assertEquals(mapOf(FilmographyType.ALL to 4, FilmographyType.MOVIE to 3, FilmographyType.TV to 1), page.typeCounts)
        assertEquals(9.0, page.marks[MediaType.movie to 13]?.score)
        assertEquals(WatchStatus.WANT_TO_WATCH, page.marks[MediaType.tvshow to 2574]?.status)
        assertEquals(2, home.markQueries.get()) // one per media type
    }

    @Test
    fun `another department, movies only, most popular`() {
        whenever(client.person(31, Lang.en)).thenReturn(hanks.right())

        assertEquals(listOf("That Thing You Do!"), handler.handle(query("directing")).getOrNull()!!.entries.map { it.title })
        val popular = handler.handle(query(type = FilmographyType.MOVIE, sort = FilmographySort.POPULAR)).getOrNull()!!
        assertEquals(listOf("Forrest Gump", "Toy Story", "Toy Story 5"), popular.entries.map { it.title })
    }

    @Test
    fun `lookup errors map to page errors`() {
        whenever(client.person(1, Lang.en)).thenReturn(PersonLookupError.NotFound.left())
        whenever(client.person(2, Lang.en)).thenReturn(PersonLookupError.ApiKeyMissing.left())
        whenever(client.person(3, Lang.en)).thenReturn(PersonLookupError.Failed("x").left())

        assertEquals(GetPersonError.NotFound, handler.handle(GetPersonQuery(1, me, Lang.en)).leftOrNull())
        assertEquals(GetPersonError.TmdbNotConfigured, handler.handle(GetPersonQuery(2, me, Lang.en)).leftOrNull())
        assertEquals(GetPersonError.TmdbFailed, handler.handle(GetPersonQuery(3, me, Lang.en)).leftOrNull())
    }

    @Test
    fun `the page view has links, roles, Upcoming, dates and the bio lead`() {
        whenever(client.person(31, Lang.en)).thenReturn(hanks.right())
        home.setMark(me, MediaType.movie, 13, TitleMark(WatchStatus.WATCHED, 9.0))
        val view = PersonPageView.of(handler.handle(query()).getOrNull()!!, showAll = false, today = today, locale = Locale.ENGLISH)

        assertEquals("/person/31", view.href)
        assertEquals("https://image.tmdb.org/t/p/w185/hanks.jpg", view.photoUrl)
        assertEquals("person.dept.acting", view.knownForKey)
        assertEquals("July 9, 1956", view.born)
        assertEquals(70, view.age)
        assertNull(view.died)
        // The default department needs no parameter; other tabs keep the current type and sort.
        assertEquals(listOf("/person/31", "/person/31?dept=directing", "/person/31?dept=writing"), view.departmentTabs.map { it.href })
        assertEquals(listOf("/person/31", "/person/31?type=movie", "/person/31?type=tv"), view.typeTabs.map { it.href })
        assertEquals(listOf("/person/31", "/person/31?sort=popular"), view.sortTabs.map { it.href })
        assertTrue(view.departmentTabs[0].active)

        val upcoming = view.tiles.first()
        assertEquals("Toy Story 5", upcoming.title)
        assertTrue(upcoming.actions.upcoming)
        val gump = view.tiles.first { it.title == "Forrest Gump" }
        assertEquals("/movie/13", gump.href)
        assertEquals("Forrest Gump", gump.role)
        assertTrue(gump.actions.seen)
        assertEquals(9.0, gump.actions.score)
        assertEquals("/tv/2574", view.tiles.first { it.title == "Bosom Buddies" }.href)

        assertEquals("Thomas Jeffrey Hanks (born July 9, 1956) is an American actor. He is known for comedy. Hanks won two Oscars.", view.bioLead)
        assertEquals(listOf("He also directs.", "Second paragraph."), view.bioRest)
        assertNull(view.showAllHref)
        assertNull(view.emptyKey)
    }

    @Test
    fun `a long filmography shows the first 60 and a Show all link`() {
        val many = hanks.copy(credits = (1..75).map { credit(it, "Film $it", "2000-01-01") })
        whenever(client.person(31, Lang.en)).thenReturn(many.right())
        val page = handler.handle(query(type = FilmographyType.MOVIE)).getOrNull()!!

        val view = PersonPageView.of(page, showAll = false, today = today, locale = Locale.ENGLISH)
        assertEquals(PersonPageView.PAGE_SIZE, view.tiles.size)
        assertEquals(75, view.total)
        assertEquals("/person/31?type=movie&all=1", view.showAllHref)
        assertEquals(75, PersonPageView.of(page, showAll = true, today = today, locale = Locale.ENGLISH).tiles.size)
    }

    @Test
    fun `no credits, or none for this filter, says so`() {
        whenever(client.person(31, Lang.en)).thenReturn(hanks.copy(credits = emptyList()).right())
        val empty = PersonPageView.of(handler.handle(query()).getOrNull()!!, false, today, Locale.ENGLISH)
        assertEquals("person.empty.none", empty.emptyKey)
        assertTrue(empty.departmentTabs.isEmpty())

        whenever(client.person(31, Lang.en)).thenReturn(hanks.right())
        val noTv = PersonPageView.of(handler.handle(query("directing", FilmographyType.TV)).getOrNull()!!, false, today, Locale.ENGLISH)
        assertEquals("person.empty.filter", noTv.emptyKey)
        // All and the active TV tab stay; Movies has titles so it stays too.
        assertEquals(listOf("all", "movie", "tv"), noTv.typeTabs.map { it.value })
    }

    @Test
    fun `the bio lead skips abbreviations and initials`() {
        val (lead, rest) = PersonPageView.bio("Robert Downey Jr. is an actor. He played Tony Stark in the M.C.U. films. J. R. R. Tolkien was not him. He lives in L.A. Fourth sentence.")
        assertEquals("Robert Downey Jr. is an actor. He played Tony Stark in the M.C.U. films. J. R. R. Tolkien was not him.", lead)
        assertEquals(listOf("He lives in L.A. Fourth sentence."), rest)

        assertEquals("Short bio." to emptyList<String>(), PersonPageView.bio("Short bio."))
        assertEquals(null to emptyList<String>(), PersonPageView.bio("  "))
        assertFalse(PersonPageView.bio("One. Two. Three.").second.isNotEmpty())
    }

    // --- Fork (2026-11-24): "Show N appearances as himself" ---

    private fun appearance(id: Int, title: String, role: String, type: MediaType = MediaType.tvshow) =
        PersonCredit(id, type, title, LocalDate.parse("2010-01-01"), "/$id.jpg", ACTING, role, 1.0, 10, appearance = true)

    private val hanksWithShows = hanks.copy(
        gender = 2,
        credits = hanks.credits + listOf(
            appearance(2518, "The Tonight Show", "Self - Guest"),
            appearance(27023, "The Oscars", "Self - Presenter"),
            appearance(99998, "A Documentary", "Self", MediaType.movie),
        ),
    )

    @Test
    fun `appearances are hidden by default, with the switch's count`() {
        whenever(client.person(31, Lang.en)).thenReturn(hanksWithShows.right())

        val page = handler.handle(query()).getOrNull()!!
        assertFalse(page.appearances)
        assertEquals(3, page.appearanceCount)
        assertEquals(listOf("Toy Story 5", "Toy Story", "Forrest Gump", "Bosom Buddies"), page.entries.map { it.title })
        assertEquals(4, page.departments.first { it.slug == "acting" }.count)

        val view = PersonPageView.of(page, false, today, Locale.ENGLISH)
        val switch = view.appearancesSwitch!!
        assertFalse(switch.on)
        assertEquals(3, switch.count)
        assertEquals("person.appearances.himself", switch.labelKey)
        assertEquals("/person/31?appearances=1", switch.href)
        // Movies only: just the documentary.
        assertEquals(1, handler.handle(query(type = FilmographyType.MOVIE)).getOrNull()!!.appearanceCount)
    }

    @Test
    fun `with the switch on, appearances join Acting with their role text, and every link keeps it`() {
        whenever(client.person(31, Lang.en)).thenReturn(hanksWithShows.right())

        val page = handler.handle(query().copy(appearances = true)).getOrNull()!!
        assertTrue(page.appearances)
        assertEquals(7, page.entries.size)
        assertEquals(7, page.departments.first { it.slug == "acting" }.count)
        assertEquals(mapOf(FilmographyType.ALL to 7, FilmographyType.MOVIE to 4, FilmographyType.TV to 3), page.typeCounts)

        val view = PersonPageView.of(page, false, today, Locale.ENGLISH)
        assertEquals("Self - Guest", view.tiles.first { it.title == "The Tonight Show" }.role)
        assertTrue(view.appearancesSwitch!!.on)
        assertEquals("/person/31", view.appearancesSwitch!!.href)
        assertEquals(listOf("/person/31?appearances=1", "/person/31?dept=directing&appearances=1", "/person/31?dept=writing&appearances=1"), view.departmentTabs.map { it.href })
        assertEquals("/person/31?type=tv&appearances=1", view.typeTabs.first { it.value == "tv" }.href)
        assertEquals("/person/31?sort=popular&appearances=1", view.sortTabs.first { it.value == "popular" }.href)

        val many = hanksWithShows.copy(credits = (1..75).map { credit(it, "Film $it", "2000-01-01") } + appearance(5, "Talk", "Self"))
        whenever(client.person(31, Lang.en)).thenReturn(many.right())
        val long = PersonPageView.of(handler.handle(query().copy(appearances = true)).getOrNull()!!, false, today, Locale.ENGLISH)
        assertEquals("/person/31?appearances=1&all=1", long.showAllHref)
    }

    @Test
    fun `no switch without appearances, nor on a crew tab`() {
        whenever(client.person(31, Lang.en)).thenReturn(hanks.right())
        assertNull(PersonPageView.of(handler.handle(query()).getOrNull()!!, false, today, Locale.ENGLISH).appearancesSwitch)

        whenever(client.person(31, Lang.en)).thenReturn(hanksWithShows.right())
        assertNull(PersonPageView.of(handler.handle(query("directing")).getOrNull()!!, false, today, Locale.ENGLISH).appearancesSwitch)
    }

    @Test
    fun `someone who only appears as herself gets the switch, which opens Acting`() {
        val host = hanks.copy(
            id = 99, name = "A Host", gender = 1, knownForDepartment = "Acting",
            credits = listOf(appearance(1, "Talk Show", "Herself - Host"), appearance(2, "Awards", "Herself"), credit(3, "A Special", "2020-01-01", "Production", "Producer")),
        )
        whenever(client.person(99, Lang.en)).thenReturn(host.right())

        val off = PersonPageView.of(handler.handle(GetPersonQuery(99, me, Lang.en)).getOrNull()!!, false, today, Locale.ENGLISH)
        assertEquals(listOf("production"), off.departmentTabs.map { it.value })
        assertEquals("person.appearances.herself", off.appearancesSwitch!!.labelKey)
        assertEquals(2, off.appearancesSwitch!!.count)
        assertEquals("/person/99?dept=acting&appearances=1", off.appearancesSwitch!!.href)

        val on = handler.handle(GetPersonQuery(99, me, Lang.en, department = "acting", appearances = true)).getOrNull()!!
        assertEquals("acting", on.department?.slug)
        assertEquals(listOf("Awards", "Talk Show"), on.entries.map { it.title })
    }
}
