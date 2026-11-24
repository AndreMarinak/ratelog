package org.ratelog.home.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.browse.BrowseParamParser
import org.ratelog.browse.BrowseParams
import org.ratelog.browse.BrowseYear
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.ListSummary
import org.ratelog.home.HomeTitle
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate
import java.util.Locale

class HomeDashboardViewTest {

    private val today = LocalDate.of(2026, 11, 22)

    private fun title(tmdbId: Int, type: MediaType = MediaType.movie, status: WatchStatus? = null, score: Double? = null, releaseDate: LocalDate? = null) =
        HomeTitle(tmdbId, type, "Title $tmdbId", 2026, "/p$tmdbId.jpg", status, score, releaseDate)

    private fun view(vararg rows: HomeRow, discover: DiscoverState = DiscoverState.OK) =
        HomeDashboard(rows.toList(), discover).toView(Locale.ENGLISH, today)

    @Test
    fun `tiles link to the movie and tv detail pages`() {
        val tiles = view(HomeRow(HomeRowKind.TRENDING_MOVIES, titles = listOf(title(1), title(2, MediaType.tvshow)))).rows.single().tiles

        assertEquals(listOf("/movie/1", "/tv/2"), tiles.map { it.href })
        assertEquals("https://image.tmdb.org/t/p/w342/p1.jpg", tiles[0].posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w500/p1.jpg", tiles[0].posterUrl2x)
        assertEquals("2026", tiles[0].meta)
    }

    @Test
    fun `TMDB rows show your status, personal rows show the media type instead`() {
        val dashboard = view(
            HomeRow(HomeRowKind.UP_NEXT, titles = listOf(title(1, status = WatchStatus.WANT_TO_WATCH))),
            HomeRow(HomeRowKind.TRENDING_TV, titles = listOf(title(2, MediaType.tvshow, status = WatchStatus.WATCHING))),
        )

        val upNext = dashboard.rows[0].tiles.single()
        assertNull(upNext.statusIcon)
        assertTrue(upNext.showTypeTag)
        val popular = dashboard.rows[1].tiles.single()
        assertEquals("circle-play", popular.statusIcon)
        assertEquals("status.watching", popular.statusKey)
        assertFalse(popular.showTypeTag)
    }

    @Test
    fun `the watchlist bookmark shows for titles with no status or Want to watch only`() {
        val tiles = view(
            HomeRow(
                HomeRowKind.TRENDING_MOVIES,
                titles = listOf(
                    title(1),
                    title(2, status = WatchStatus.WANT_TO_WATCH),
                    title(3, status = WatchStatus.WATCHED),
                    title(4, MediaType.tvshow, status = WatchStatus.DROPPED),
                ),
            ),
        ).rows.single().tiles.associateBy { it.tmdbId }

        assertTrue(tiles.getValue(1).canToggleWatchlist)
        assertFalse(tiles.getValue(1).inWatchlist)
        assertEquals("movie", tiles.getValue(1).type)
        // Want to watch is shown by the filled bookmark, not by a second badge.
        assertTrue(tiles.getValue(2).inWatchlist)
        assertNull(tiles.getValue(2).statusIcon)
        assertFalse(tiles.getValue(3).canToggleWatchlist)
        assertEquals("eye", tiles.getValue(3).statusIcon)
        assertFalse(tiles.getValue(4).canToggleWatchlist)
        assertEquals("tv", tiles.getValue(4).type)
    }

    @Test
    fun `scores have one decimal`() {
        val tile = view(HomeRow(HomeRowKind.RECENTLY_RATED, titles = listOf(title(1, score = 8.25)))).rows.single().tiles.single()

        assertEquals("8.3", tile.scoreLabel)
    }

    @Test
    fun `coming soon shows the release date, with the year only when it isn't this year`() {
        val tiles = view(
            HomeRow(HomeRowKind.COMING_SOON, titles = listOf(title(1, releaseDate = LocalDate.of(2026, 12, 19)), title(2, releaseDate = LocalDate.of(2027, 1, 9)))),
        ).rows.single().tiles

        assertEquals("Dec 19", tiles[0].meta)
        assertEquals("Jan 9, 2027", tiles[1].meta)
    }

    @Test
    fun `more links point at the watchlist, lists and discover pages`() {
        val rows = view(
            HomeRow(HomeRowKind.CONTINUE_WATCHING, titles = listOf(title(1))),
            HomeRow(HomeRowKind.TRENDING_MOVIES, titles = listOf(title(2))),
            HomeRow(HomeRowKind.COMING_SOON, titles = listOf(title(3, releaseDate = today.plusDays(3)))),
        ).rows

        assertEquals("/watchlist?status=watching", rows[0].moreHref)
        assertEquals("home.row.see.all", rows[0].moreKey)
        assertEquals("/discover?type=movie&popular=week", rows[1].moreHref)
        assertEquals("home.row.more", rows[1].moreKey)
        assertEquals("/discover?type=movie&year=upcoming", rows[2].moreHref)
        assertEquals("row-coming-soon", rows[2].id)
    }

    @Test
    fun `Recommended for you is fetched by the page and has no More link`() {
        val row = view(HomeRow(HomeRowKind.RECOMMENDED, lazy = true)).rows.single()

        assertEquals("row-recommended", row.id)
        assertEquals("home.row.recommended", row.titleKey)
        assertEquals("/for-you/row", row.srcHref)
        assertEquals(null, row.moreHref)
        assertTrue(view(HomeRow(HomeRowKind.TRENDING_MOVIES, titles = listOf(title(1)))).rows.single().srcHref == null)
    }

    @Test
    fun `every TMDB row's More link is a Discover grid the page fully understands`() {
        HomeRowKind.entries.filter { !it.isPersonal }.forEach { kind ->
            val href = kind.moreHref!!
            assertTrue(href.startsWith("/discover?"), href)
            val query = href.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val parsed = BrowseParamParser.parse(
                BrowseParams(type = query["type"], year = query["year"], popular = query["popular"], sort = query["sort"], decade = query["decade"]),
                today,
            )
            assertEquals(emptyList<Any>(), parsed.invalid, href)
            assertFalse(parsed.filters.isUnfiltered, "$href must open a grid, not the landing")
        }
        assertEquals(BrowseYear.Upcoming, BrowseParamParser.parse(BrowseParams(year = "upcoming"), today).filters.year)
    }

    @Test
    fun `list cards always have four cover slots`() {
        val summary = ListSummary(CustomList.Id(7), User.Id(1), "me", "Heists", false, 2, listOf("/a.jpg", "/b.jpg"), 0)

        val card = view(HomeRow(HomeRowKind.YOUR_LISTS, lists = listOf(summary))).rows.single().lists.single()

        assertEquals("/list/7", card.href)
        assertEquals(listOf("https://image.tmdb.org/t/p/w342/a.jpg", "https://image.tmdb.org/t/p/w342/b.jpg", null, null), card.coverPosterUrls)
        assertNull(card.ownerUsername)
    }

    @Test
    fun `the welcome note shows until there is something personal, and TMDB problems are flagged`() {
        val empty = view(discover = DiscoverState.NOT_CONFIGURED)
        assertTrue(empty.showWelcome)
        assertTrue(empty.tmdbNotConfigured)
        assertFalse(empty.tmdbUnavailable)

        val personal = view(HomeRow(HomeRowKind.UP_NEXT, titles = listOf(title(1))), discover = DiscoverState.UNAVAILABLE)
        assertFalse(personal.showWelcome)
        assertTrue(personal.tmdbUnavailable)
    }

    @Test
    fun `tiles carry the Seen and Watchlist quick actions and the Upcoming badge`() {
        val tiles = view(
            HomeRow(HomeRowKind.TRENDING_MOVIES, titles = listOf(
                title(1003596, releaseDate = LocalDate.of(2026, 12, 18)),
                title(2, status = WatchStatus.WATCHED, releaseDate = LocalDate.of(2026, 11, 20)),
            )),
            HomeRow(HomeRowKind.RECENTLY_RATED, titles = listOf(title(3, score = 8.5, releaseDate = LocalDate.of(1999, 3, 31)))),
            HomeRow(HomeRowKind.UP_NEXT, titles = listOf(title(4, MediaType.tvshow, status = WatchStatus.WANT_TO_WATCH, releaseDate = LocalDate.of(2026, 11, 23)))),
        ).rows.flatMap { it.tiles }.associateBy { it.tmdbId }

        val doomsday = tiles.getValue(1003596).actions!!
        assertTrue(doomsday.upcoming)
        assertEquals("Dec 18", doomsday.upcomingDate)
        assertFalse(doomsday.canToggleSeen, "no eye on an upcoming title")
        assertTrue(doomsday.canToggleWatchlist)

        val watched = tiles.getValue(2).actions!!
        assertTrue(watched.seen)
        assertFalse(watched.canToggleWatchlist)
        assertFalse(watched.upcoming)

        assertTrue(tiles.getValue(3).actions!!.seenLocked, "rated")

        val tomorrow = tiles.getValue(4).actions!!
        assertEquals("tv", tomorrow.type)
        assertTrue(tomorrow.upcoming, "first air date tomorrow")
        assertTrue(tomorrow.onWatchlist)
    }
}
