package org.ratelog.recommendations.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.browse.LibraryMark
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.related.RelatedTile
import org.ratelog.recommendations.related.RelatedTitles
import org.ratelog.recommendations.related.SeriesRow
import org.ratelog.watchlist.WatchStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class RelatedRowViewsTest {

    private val today = LocalDate.parse("2026-11-23")
    private val clock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC)
    private val views = RelatedRowViews(BrowseBarViewsTest.testMessageSource(), clock)
    private val en = Locale.ENGLISH

    private fun entry(id: Int, name: String, date: String?, seen: Boolean = false, current: Boolean = false, poster: String? = "/p$id.jpg") =
        RelatedTile(
            RelatedTitle(id, MediaType.movie, name, date?.let(LocalDate::parse), poster, 7.5, 900),
            mark = if (seen) LibraryMark(score = null, rated = false, status = WatchStatus.WATCHED) else null,
            current = current,
        )

    private fun toyStory(seen: Set<Int> = emptySet()) = listOf(
        entry(862, "Toy Story", "1995-11-22", seen = 862 in seen),
        entry(863, "Toy Story 2", "1999-10-30", seen = 863 in seen, current = true),
        entry(10193, "Toy Story 3", "2010-06-16", seen = 10193 in seen),
        entry(301528, "Toy Story 4", "2019-06-19", seen = 301528 in seen),
        entry(1084244, "Toy Story 5", "2026-06-17", seen = 1084244 in seen),
    )

    @Test
    fun `series headings read Part of the X without a doubled article`() {
        assertEquals("Part of the Toy Story Collection", views.seriesTitle("Toy Story Collection", en))
        assertEquals("Part of the Avengers Collection", views.seriesTitle("The Avengers Collection", en))
        assertEquals("Part of the Lord of the Rings Collection", views.seriesTitle(" The Lord of the Rings Collection ", en))
        assertEquals("Part of a series", views.seriesTitle("  ", en))
    }

    @Test
    fun `progress counts released entries and names what to watch next`() {
        assertEquals("5 films · Start with Toy Story", views.progress(toyStory(), today, en))
        assertEquals("You've seen 2 of 5 · Next: Toy Story 3", views.progress(toyStory(setOf(862, 863)), today, en))
        assertEquals("You've seen 1 of 5 · Next: Toy Story", views.progress(toyStory(setOf(10193)), today, en), "the first unseen one, in release order")
        assertEquals("You've seen all 5", views.progress(toyStory(setOf(862, 863, 10193, 301528, 1084244)), today, en))
    }

    @Test
    fun `upcoming entries don't count until they are out, and come next once the rest is seen`() {
        val avengers = listOf(
            entry(24428, "The Avengers", "2012-04-25", seen = true),
            entry(99861, "Avengers: Age of Ultron", "2015-04-22", seen = true),
            entry(1003596, "Avengers: Doomsday", "2026-12-16", current = true),
            entry(1003598, "Avengers: Secret Wars", "2027-12-15"),
        )
        assertEquals("You've seen all 2 · Next: Avengers: Doomsday, Dec 16", views.progress(avengers, today, en))

        val dune = listOf(entry(1, "Dune", "2021-09-15", seen = true), entry(2, "Dune: Part Three", null))
        assertEquals("You've seen 1 of 1 · Next: Dune: Part Three", views.progress(dune, today, en))

        assertEquals("1 film · Start with Dune", views.progress(listOf(entry(1, "Dune", "2021-09-15"), entry(2, "Dune: Part Three", "2026-12-15")), today, en))
        assertNull(views.progress(listOf(entry(1, "Announced", "2027-05-01"), entry(2, "Also announced", null)), today, en))
    }

    @Test
    fun `tiles carry the key, links, sharp posters, this title's marker and quick actions`() {
        val more = RelatedTile(
            RelatedTitle(585, MediaType.movie, "Monsters, Inc.", LocalDate.parse("2001-11-01"), "/mi.jpg", 7.8, 20000),
            LibraryMark(score = 8.25, rated = true, status = null),
        )
        val show = RelatedTile(RelatedTitle(60059, MediaType.tvshow, "Better Call Saul", null, null, null, 30), mark = null)
        val section = views.section(RelatedTitles(SeriesRow(10194, "Toy Story Collection", toyStory(setOf(863))), listOf(more, show)), en)

        assertEquals(listOf("related-series", "related-more"), section.rows.map { it.id })
        val series = section.rows[0]
        assertTrue(series.isSeries)
        assertEquals("Part of the Toy Story Collection", series.title)
        assertEquals("You've seen 1 of 5 · Next: Toy Story", series.progress)
        val current = series.tiles[1]
        assertTrue(current.current)
        assertTrue(current.seen)
        assertEquals("movie:863", current.key)
        assertFalse(series.tiles[0].current)

        val row = section.rows[1]
        assertFalse(row.isSeries)
        assertEquals("More like this", row.title)
        assertNull(row.progress)
        val tile = row.tiles[0]
        assertEquals("movie:585", tile.key)
        assertEquals("/movie/585", tile.href)
        assertEquals("2001", tile.meta)
        assertEquals("https://image.tmdb.org/t/p/w342/mi.jpg", tile.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w500/mi.jpg", tile.posterUrl2x)
        assertEquals("8.3", tile.userScore)
        assertTrue(tile.actions.seen)
        assertTrue(tile.actions.seenLocked, "rated movies are locked seen")
        assertEquals(8.25, tile.actions.score, "the poster ★ shows your score (tile rating)")

        val tv = row.tiles[1]
        assertEquals("tv:60059", tv.key)
        assertEquals("/tv/60059", tv.href)
        assertEquals("TBA", tv.meta)
        assertNull(tv.posterUrl)
        assertEquals("B", tv.initial)
        assertEquals("tv", tv.actions.type)
    }

    @Test
    fun `upcoming series entries get the Upcoming badge and no Seen toggle`() {
        val section = views.section(
            RelatedTitles(
                SeriesRow(1, "Dune Collection", listOf(entry(1, "Dune", "2021-09-15"), entry(2, "Dune: Part Three", "2026-12-15", current = true))),
                emptyList(),
            ),
            en,
        )
        assertEquals(1, section.rows.size, "no empty More like this row")
        val upcoming = section.rows.single().tiles[1].actions
        assertTrue(upcoming.upcoming)
        assertEquals("Dec 15", upcoming.upcomingDate)
        assertFalse(upcoming.canToggleSeen)
    }
}
