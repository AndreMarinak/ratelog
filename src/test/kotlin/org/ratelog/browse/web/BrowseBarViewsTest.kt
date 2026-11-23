package org.ratelog.browse.web

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Genre
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.browse.BrowseDecade
import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowsePopular
import org.ratelog.browse.BrowseProvider
import org.ratelog.browse.BrowseRuntime
import org.ratelog.browse.BrowseSort
import org.ratelog.browse.BrowseTitle
import org.ratelog.browse.BrowseYear
import org.ratelog.browse.LibraryMark
import org.ratelog.browse.TmdbBrowseClient
import org.ratelog.browse.TmdbBrowseError
import org.ratelog.browse.discover.BrowseTile
import org.ratelog.test.MutableClock
import org.ratelog.watchlist.WatchStatus
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.LocalDate
import java.util.Locale

class BrowseBarViewsTest {

    private val client: TmdbBrowseClient = mock()
    private val views = BrowseBarViews(testMessageSource(), client, MutableClock())
    private val us = WatchRegion("US")
    private val providers = ProviderOptions(
        us,
        (1..25).map { BrowseProvider(id = it, name = "Service $it", logoPath = "/logo$it.png", priority = it) },
        failed = false,
    )

    private fun build(filters: BrowseFilters, options: ProviderOptions = providers) = views.build(filters, options, locale = Locale.ENGLISH)

    private fun BrowseBarView.menu(id: String) = menus.first { it.id == id }

    private fun BarMenu.links() = sections.flatMap { it.links }

    @Test
    fun `given no filters then offers every menu at its default and no chips`() {
        val bar = build(BrowseFilters())

        assertEquals("movie", bar.type)
        assertEquals(listOf("year", "rating", "popular", "genre", "service", "more"), bar.menus.map { it.id })
        assertTrue(bar.menus.none { it.active })
        assertTrue(bar.chips.isEmpty())
        assertEquals(listOf("Movies" to true, "TV" to false), bar.typeLinks.map { it.label to it.active })
        assertEquals("/browse?type=tv", bar.typeLinks[1].href)
        assertEquals("Popularity", bar.sortMenu!!.value)
    }

    @Test
    fun `given the year menu then lists upcoming, decades and the chosen decade's years`() {
        val today = LocalDate.parse("2026-11-22")
        val bar = build(BrowseFilters(decade = BrowseDecade.Of(2020)))
        val year = bar.menu("year")

        val top = year.sections[0].links.map { it.label }
        assertEquals(listOf("Any year", "Upcoming", "2020s", "2010s", "2000s", "1990s", "1980s", "1970s", "1960s", "1950s and earlier"), top)
        assertEquals("2020s", year.value)
        assertEquals("Years in the 2020s", year.sections[1].heading)
        assertEquals((2020..today.year + 1).map { it.toString() }, year.sections[1].links.map { it.label })
        assertEquals("/browse?type=movie&decade=2020s&year=2024", year.sections[1].links[4].href)

        // TV has no Upcoming; "earlier" opens older decades.
        val tvEarlier = build(BrowseFilters(type = MediaType.tvshow, decade = BrowseDecade.Earlier)).menu("year")
        assertFalse(tvEarlier.sections[0].links.any { it.label == "Upcoming" })
        assertTrue(tvEarlier.sections[0].links.last().active)
        assertEquals(listOf("1950s", "1940s", "1930s", "1920s", "1910s", "1900s"), tvEarlier.sections[1].links.map { it.label })
    }

    @Test
    fun `given a year then the menu and the chip show the year and removing it keeps the decade`() {
        val bar = build(BrowseFilters(decade = BrowseDecade.Of(2010), year = BrowseYear.Of(2014)))

        assertEquals("2014", bar.menu("year").value)
        assertTrue(bar.menu("year").sections[0].links.first { it.label == "2010s" }.active)
        assertTrue(bar.menu("year").sections[1].links.first { it.label == "2014" }.active)
        val chip = bar.chips.single()
        assertEquals("2014", chip.label)
        assertEquals("/browse?type=movie&decade=2010s", chip.removeHref)
        assertEquals("Remove 2014", chip.removeLabel)
    }

    @Test
    fun `given every filter then each has a removable chip that drops only itself`() {
        val filters = BrowseFilters(
            popular = BrowsePopular.NOW, decade = BrowseDecade.Earlier, minRating = 7.0, genre = Genre.HORROR, provider = 3,
            language = "ko", runtime = BrowseRuntime.SHORT, hideRated = true, hideWatchlist = true,
        )

        val bar = build(filters)

        assertEquals(
            listOf("Popular now", "1950s and earlier", "Rated 7+", "Horror", "Service 3", "Korean", "Under 90 min", "Hiding rated", "Hiding watchlist"),
            bar.chips.map { it.label },
        )
        assertTrue(bar.chips[3].removeHref.contains("genre=").not())
        assertTrue(bar.chips[3].removeHref.contains("provider=3"))
        assertEquals("/browse?type=movie", bar.clearHref)
        assertEquals("4", bar.menu("more").value)
        assertEquals("Popular now", bar.sortMenu!!.value)
    }

    @Test
    fun `given TV with a movie only genre then the type switch drops it`() {
        val bar = build(BrowseFilters(genre = Genre.HORROR, runtime = BrowseRuntime.LONG))

        assertEquals("/browse?type=tv", bar.typeLinks[1].href)
        assertEquals(16, build(BrowseFilters(type = MediaType.tvshow)).menu("genre").links().size - 1)
        assertFalse(build(BrowseFilters(type = MediaType.tvshow)).menu("more").sections.any { it.heading == "Runtime" })
    }

    @Test
    fun `given genres then they are sorted by name and the chosen one is active`() {
        val genre = build(BrowseFilters(genre = Genre.WESTERN)).menu("genre")
        val labels = genre.links().map { it.label }

        assertEquals("Any genre", labels.first())
        assertEquals(labels.drop(1).sortedBy { it.lowercase() }, labels.drop(1))
        assertEquals("Western", genre.value)
        assertTrue(genre.links().single { it.active }.label == "Western")
    }

    @Test
    fun `given the provider list then shows the top 20 with logos plus a chosen one further down`() {
        val service = build(BrowseFilters(provider = 23)).menu("service")
        val links = service.links()

        assertEquals("Streaming in United States", service.sections[0].heading)
        assertEquals("Any service", links.first().label)
        assertEquals((1..20).map { "Service $it" } + "Service 23", links.drop(1).map { it.label })
        assertEquals("https://image.tmdb.org/t/p/w92/logo1.png", links[1].logoUrl)
        assertEquals("Service 23", service.value)
        assertNull(service.note)
    }

    @Test
    fun `given the provider list failed then the menu says so and the chip still works`() {
        val failed = ProviderOptions(us, emptyList(), failed = true)
        val bar = build(BrowseFilters(provider = 8), failed)

        assertEquals("Couldn't load streaming services right now. Try again later.", bar.menu("service").note)
        assertEquals("Service 8", bar.chips.single().label)
        assertEquals(listOf("Any service"), bar.menu("service").links().map { it.label })
    }

    @Test
    fun `given provider options then they come from TMDB for the user's region`() {
        whenever(client.providers(eq(MediaType.movie), eq(WatchRegion("ES")), eq(Lang.es))).thenReturn(listOf(BrowseProvider(8, "Netflix", null, 1)).right())
        whenever(client.providers(eq(MediaType.tvshow), any(), any())).thenReturn(TmdbBrowseError.Failed("x").left())

        assertEquals(ProviderOptions(WatchRegion("ES"), listOf(BrowseProvider(8, "Netflix", null, 1)), false), views.providerOptions(MediaType.movie, Lang.es))
        assertTrue(views.providerOptions(MediaType.tvshow, Lang.en).failed)
    }

    @Test
    fun `given library toggles and sorting then links flip them and sort clears popular`() {
        val bar = build(BrowseFilters(popular = BrowsePopular.WEEK, hideRated = true))
        val more = bar.menu("more")
        val toggles = more.sections.last()

        assertEquals("toggles", toggles.layout)
        assertEquals(listOf(true, false), toggles.links.map { it.active })
        assertEquals("/browse?type=movie&popular=week", toggles.links[0].href)
        assertEquals("/browse?type=movie&popular=week&hideRated=1&hideWatchlist=1", toggles.links[1].href)

        val sort = bar.sortMenu!!
        assertEquals("This week", sort.value)
        assertTrue(sort.links().none { it.active })
        assertEquals("/browse?type=movie&sort=title&hideRated=1", sort.links().first { it.label == "Title A-Z" }.href)
        assertEquals("/browse?type=movie&hideRated=1", sort.links().first { it.label == "Popularity" }.href)
        assertTrue(build(BrowseFilters(sort = BrowseSort.RATING)).menu("rating").links().last().active)
    }

    @Test
    fun `given tiles then links to detail pages and shows the user's score, status and bookmark state`() {
        val title = BrowseTitle(603, MediaType.movie, "The Matrix", LocalDate.parse("1999-03-31"), "/m.jpg", 8.234, 100, emptyList(), "en")
        val show = title.copy(tmdbId = 1399, type = MediaType.tvshow, title = "game of thrones", posterPath = null, voteAverage = null)

        val tiles = views.tiles(
            listOf(
                BrowseTile(title, LibraryMark(8.46, true, WatchStatus.WATCHED)),
                BrowseTile(show, LibraryMark(null, false, WatchStatus.WANT_TO_WATCH)),
                BrowseTile(title.copy(tmdbId = 604), null),
            ),
            Locale.ENGLISH,
        )

        tiles[0].let {
            assertEquals("movie-603", it.key)
            assertEquals("/movie/603", it.href)
            assertEquals("movie", it.type)
            assertEquals("https://image.tmdb.org/t/p/w342/m.jpg", it.posterUrl)
            assertEquals("8.2", it.tmdbScore)
            assertEquals("8.5", it.userScore)
            assertEquals("circle-check", it.statusIcon)
            assertEquals("Watched", it.statusLabel)
            assertFalse(it.onWatchlist)
            assertEquals("watched", it.status)
        }
        tiles[1].let {
            assertEquals("/tv/1399", it.href)
            assertEquals("tv", it.type)
            assertEquals("G", it.initial)
            assertNull(it.posterUrl)
            assertNull(it.tmdbScore)
            assertTrue(it.onWatchlist)
            assertNull(it.statusIcon, "Want to watch is shown by the bookmark, not a badge")
        }
        tiles[2].let {
            assertNull(it.status)
            assertNull(it.userScore)
            assertFalse(it.onWatchlist)
        }
    }

    @Test
    fun `given language codes and regions then shows their names`() {
        assertEquals("Japanese", BrowseBarViews.languageName("ja", Locale.ENGLISH))
        assertEquals("Japonés", BrowseBarViews.languageName("ja", Locale.of("es")))
        assertEquals("XX", BrowseBarViews.languageName("xx", Locale.ENGLISH))
        assertEquals("Spain", BrowseBarViews.countryName(WatchRegion("ES"), Locale.ENGLISH))
    }

    companion object {
        fun testMessageSource() = ResourceBundleMessageSource().apply {
            setBasenames("messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
        }
    }
}
