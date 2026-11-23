package org.ratelog.watchlist.seen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate
import java.util.Locale

/** The poster tile and title page view rules. */
class ActionsViewTest {

    private val today = LocalDate.parse("2026-11-23")
    private val released = LocalDate.parse("2001-05-18")

    private fun tile(status: WatchStatus? = null, rated: Boolean = false, date: LocalDate? = released, type: MediaType = MediaType.movie) =
        TileActionsView.of(603, type, status, rated, date, today, Locale.ENGLISH)

    private fun title(
        type: MediaType = MediaType.movie,
        status: WatchStatus? = null,
        rated: Boolean = false,
        date: LocalDate? = released,
        tmdbStatus: String? = "Released",
        followed: Boolean = false,
    ) = TitleActionsView.of(type, 42, 603, status, rated, date, tmdbStatus, followed, today, Locale.ENGLISH)

    // --- SeenState ---

    @Test
    fun `seen is rated or watched, locked only by a rating`() {
        assertFalse(SeenState(null, false).seen)
        assertTrue(SeenState(WatchStatus.WATCHED, false).seen)
        assertFalse(SeenState(WatchStatus.WATCHED, false).seenLocked)
        assertTrue(SeenState(null, true).seen)
        assertTrue(SeenState(WatchStatus.DROPPED, true).seen, "a dropped title you rated counts as seen")
        assertTrue(SeenState(WatchStatus.DROPPED, true).seenLocked)
        listOf(WatchStatus.WANT_TO_WATCH, WatchStatus.WATCHING, WatchStatus.DROPPED).forEach { assertFalse(SeenState(it, false).seen, "$it") }
    }

    @Test
    fun `the bookmark toggles only from nothing or want to watch`() {
        assertTrue(SeenState(null, false).canToggleWatchlist)
        assertTrue(SeenState(WatchStatus.WANT_TO_WATCH, false).canToggleWatchlist)
        assertTrue(SeenState(WatchStatus.WANT_TO_WATCH, false).onWatchlist)
        listOf(WatchStatus.WATCHING, WatchStatus.WATCHED, WatchStatus.DROPPED).forEach { assertFalse(SeenState(it, false).canToggleWatchlist, "$it") }
    }

    // --- Tiles ---

    @Test
    fun `a released title gets both toggles and no Upcoming badge`() {
        val t = tile()
        assertEquals("movie", t.type)
        assertEquals(603, t.tmdbId)
        assertTrue(t.canToggleSeen)
        assertTrue(t.canToggleWatchlist)
        assertFalse(t.upcoming)
        assertNull(t.upcomingDate)
        assertNull(t.badgeIcon)
        assertEquals("tv", tile(type = MediaType.tvshow).type)
    }

    @Test
    fun `an upcoming title keeps the bookmark, loses the eye and gets a short date`() {
        val t = tile(status = WatchStatus.WANT_TO_WATCH, date = LocalDate.parse("2026-12-18"))
        assertTrue(t.upcoming)
        assertFalse(t.canToggleSeen)
        assertTrue(t.onWatchlist)
        assertEquals("Dec 18", t.upcomingDate)

        assertFalse(tile(date = today).upcoming, "out today")
        assertFalse(tile(date = null).upcoming, "no date")
        assertTrue(tile(date = today.plusDays(1), type = MediaType.tvshow).upcoming, "a show's first air date")
    }

    @Test
    fun `watching and dropped show as a badge where the bookmark would be`() {
        assertEquals("circle-play", tile(WatchStatus.WATCHING).badgeIcon)
        assertEquals("status.watching", tile(WatchStatus.WATCHING).badgeKey)
        assertEquals("circle-x", tile(WatchStatus.DROPPED).badgeIcon)
        assertNull(tile(WatchStatus.WATCHED).badgeIcon, "Watched shows as the eye")
        assertNull(tile(WatchStatus.WANT_TO_WATCH).badgeIcon, "Want to watch shows as the bookmark")
        assertEquals("eye", tile(WatchStatus.WATCHED, date = today.plusDays(3)).badgeIcon, "an upcoming title has no eye, so Watched needs the badge")
    }

    @Test
    fun `rated tiles are seen and locked`() {
        val t = tile(status = null, rated = true)
        assertTrue(t.seen)
        assertTrue(t.seenLocked)
        assertTrue(t.canToggleWatchlist, "a rated title with no status can still go on the watchlist (rewatch)")
    }

    // --- Title page ---

    @Test
    fun `title page toggles reflect the status and rating`() {
        assertTrue(title(status = WatchStatus.WATCHED).seen)
        assertFalse(title(status = WatchStatus.WATCHED).seenLocked)
        assertTrue(title(rated = true).seenLocked)
        assertTrue(title(status = WatchStatus.WANT_TO_WATCH).onWatchlist)
        assertEquals("/movie/603", title().back)
        assertEquals("movie", title().tileType)
        assertEquals("movie", title().mediaType)
        assertEquals("/tv/603", title(type = MediaType.tvshow).back)
        assertEquals("tvshow", title(type = MediaType.tvshow).mediaType)
        assertEquals("tv", title(type = MediaType.tvshow).tileType)
    }

    @Test
    fun `adding an unrated seen title to the watchlist asks first, a rated one doesn't`() {
        assertTrue(title(status = WatchStatus.WATCHED).watchlistReplacesSeen)
        assertFalse(title(status = WatchStatus.WATCHED, rated = true).watchlistReplacesSeen)
        assertFalse(title(status = null).watchlistReplacesSeen)
        assertFalse(title(status = WatchStatus.DROPPED).watchlistReplacesSeen)
    }

    @Test
    fun `upcoming title pages show the full date, or Upcoming alone when TMDB has none`() {
        val doomsday = title(date = LocalDate.parse("2026-12-18"), tmdbStatus = "Post Production")
        assertTrue(doomsday.upcoming)
        assertFalse(doomsday.canToggleSeen)
        assertEquals("Dec 18, 2026", doomsday.upcomingDate)
        assertEquals("Dec 18", doomsday.upcomingShortDate)

        val tba = title(date = null, tmdbStatus = "In Production")
        assertTrue(tba.upcoming)
        assertNull(tba.upcomingDate)

        assertFalse(title(date = null, tmdbStatus = "Released").upcoming)
    }

    @Test
    fun `watching and dropped are a show-only control, and a movie's leftover one is shown to clear`() {
        val show = title(type = MediaType.tvshow, status = WatchStatus.WATCHING)
        assertTrue(show.isTv)
        assertTrue(show.watching)
        assertFalse(show.dropped)
        assertNull(show.movieExtraStatus)
        assertTrue(title(type = MediaType.tvshow, status = WatchStatus.DROPPED).dropped)

        val movie = title(status = WatchStatus.DROPPED)
        assertFalse(movie.dropped)
        assertEquals(WatchStatus.DROPPED, movie.movieExtraStatus)
        assertEquals(WatchStatus.WATCHING, title(status = WatchStatus.WATCHING).movieExtraStatus)
        assertNull(title(status = WatchStatus.WATCHED).movieExtraStatus)
    }

    @Test
    fun `the bell follows ReleaseRules, with the follow kept visible`() {
        assertFalse(title().showFollow, "released movie")
        assertTrue(title(date = LocalDate.parse("2026-12-18")).showFollow, "upcoming movie")
        assertTrue(title(date = null, tmdbStatus = null).showFollow, "TBA movie")
        assertTrue(title(followed = true).showFollow, "already followed: kept so it can be undone")
        assertTrue(title(followed = true).followed)
        assertTrue(title(type = MediaType.tvshow, tmdbStatus = "Returning Series").showFollow)
        assertFalse(title(type = MediaType.tvshow, tmdbStatus = "Ended").showFollow)
    }
}
