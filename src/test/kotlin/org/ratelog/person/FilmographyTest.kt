package org.ratelog.person

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import java.time.LocalDate

class FilmographyTest {

    private fun credit(
        id: Int,
        title: String,
        date: String?,
        department: String = ACTING,
        role: String? = null,
        type: MediaType = MediaType.movie,
        votes: Int = 100,
        popularity: Double = 1.0,
    ) = PersonCredit(id, type, title, date?.let(LocalDate::parse), "/$id.jpg", department, role, popularity, votes)

    private val nolan = listOf(
        credit(27205, "Inception", "2010-07-15", "Directing", "Director", votes = 38000),
        credit(27205, "Inception", "2010-07-15", "Writing", "Writer", votes = 38000),
        credit(27205, "Inception", "2010-07-15", "Production", "Producer", votes = 38000),
        credit(155, "The Dark Knight", "2008-07-16", "Directing", "Director", votes = 34000),
        credit(155, "The Dark Knight", "2008-07-16", "Writing", "Screenplay", votes = 34000),
        credit(155, "The Dark Knight", "2008-07-16", "Writing", "Story", votes = 34000),
        credit(1368337, "The Odyssey", "2026-07-15", "Directing", "Director", votes = 50),
        credit(999, "Untitled Nolan Project", null, "Directing", "Director", votes = 0),
        credit(11660, "Following", "1999-04-02", ACTING, "Cameo", votes = 1500),
        credit(42, "Some Doc", "2015-01-01", "Camera", "Director of Photography"),
    )

    @Test
    fun `departments come in tab order and count each title once`() {
        val departments = Filmography.departments(nolan)

        assertEquals(listOf("acting", "directing", "writing", "production", "camera"), departments.map { it.slug })
        assertEquals(4, departments.first { it.name == "Directing" }.count)
        // The Dark Knight has two Writing credits (Screenplay, Story): one title.
        assertEquals(2, departments.first { it.name == "Writing" }.count)
    }

    @Test
    fun `the requested tab wins, else the known-for department, else the first`() {
        val departments = Filmography.departments(nolan)

        assertEquals("writing", Filmography.pick(departments, "writing", "Directing")?.slug)
        assertEquals("directing", Filmography.pick(departments, "nonsense", "Directing")?.slug)
        assertEquals("directing", Filmography.pick(departments, null, "directing")?.slug)
        assertEquals("acting", Filmography.pick(departments, null, "Sound")?.slug)
        assertNull(Filmography.pick(emptyList(), null, "Acting"))
    }

    @Test
    fun `several jobs on one title make one entry with the jobs joined`() {
        val writing = Filmography.entries(nolan, "Writing", FilmographyType.ALL, FilmographySort.NEWEST)

        assertEquals(listOf(27205, 155), writing.map { it.tmdbId })
        assertEquals(listOf("Screenplay", "Story"), writing[1].roles)
        assertEquals("Screenplay, Story", Filmography.roleLabel(writing[1], "Writing"))
    }

    @Test
    fun `repeated roles are listed once and characters join with a slash`() {
        val snl = listOf(
            credit(1667, "Saturday Night Live", "1975-10-11", role = "Host", type = MediaType.tvshow),
            credit(1667, "Saturday Night Live", "1975-10-11", role = "host", type = MediaType.tvshow),
            credit(1667, "Saturday Night Live", "1975-10-11", role = "Mr. Short-Term Memory", type = MediaType.tvshow),
        )
        val entries = Filmography.merge(snl)

        assertEquals(1, entries.size)
        assertEquals("Host / Mr. Short-Term Memory", Filmography.roleLabel(entries[0], ACTING))
    }

    @Test
    fun `newest first puts upcoming titles on top and undated ones last`() {
        val directing = Filmography.entries(nolan, "Directing", FilmographyType.ALL, FilmographySort.NEWEST)

        assertEquals(listOf("The Odyssey", "Inception", "The Dark Knight", "Untitled Nolan Project"), directing.map { it.title })
    }

    @Test
    fun `most popular sorts by TMDB votes`() {
        val directing = Filmography.entries(nolan, "Directing", FilmographyType.ALL, FilmographySort.POPULAR)

        assertEquals(listOf("Inception", "The Dark Knight", "The Odyssey", "Untitled Nolan Project"), directing.map { it.title })
    }

    @Test
    fun `the type filter keeps movies or TV`() {
        val hanks = listOf(
            credit(13, "Forrest Gump", "1994-06-23", role = "Forrest Gump"),
            credit(2574, "Bosom Buddies", "1980-11-27", role = "Kip Wilson", type = MediaType.tvshow),
        )

        assertEquals(listOf(13), Filmography.entries(hanks, ACTING, FilmographyType.MOVIE, FilmographySort.NEWEST).map { it.tmdbId })
        assertEquals(listOf(2574), Filmography.entries(hanks, ACTING, FilmographyType.TV, FilmographySort.NEWEST).map { it.tmdbId })
        assertEquals(2, Filmography.entries(hanks, ACTING, FilmographyType.ALL, FilmographySort.NEWEST).size)
    }

    @Test
    fun `filters and sorts parse from the URL, anything else is the default`() {
        assertEquals(FilmographyType.MOVIE, FilmographyType.parse("movie"))
        assertEquals(FilmographyType.TV, FilmographyType.parse(" TV "))
        assertEquals(FilmographyType.ALL, FilmographyType.parse("films"))
        assertEquals(FilmographySort.POPULAR, FilmographySort.parse("popular"))
        assertEquals(FilmographySort.NEWEST, FilmographySort.parse(null))
        assertEquals("costume-make-up", Filmography.slug("Costume & Make-Up"))
    }
}
