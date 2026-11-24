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

    /** A slice of TMDB's US list (ids checked 2026-11-24), in its display priority order. */
    private val usProviders = ProviderOptions(
        us,
        listOf(
            BrowseProvider(8, "Netflix", "/netflix.png", 3),
            BrowseProvider(9, "Amazon Prime Video", "/prime.png", 6),
            BrowseProvider(350, "Apple TV", "/apple.png", 7),
            BrowseProvider(337, "Disney Plus", "/disney.png", 8),
            BrowseProvider(15, "Hulu", "/hulu.png", 9),
            BrowseProvider(257, "fuboTV", "/fubo.png", 13),
            BrowseProvider(2303, "Paramount Plus Premium", "/pp.png", 19),
            BrowseProvider(2616, "Paramount Plus Essential", "/pe.png", 20),
            BrowseProvider(386, "Peacock Premium", "/peacock.png", 21),
            BrowseProvider(283, "Crunchyroll", "/crunchy.png", 40),
            BrowseProvider(1899, "HBO Max", "/max.png", 155),
            BrowseProvider(387, "Peacock Premium Plus", "/peacock2.png", 215),
            BrowseProvider(11, "MUBI", "/mubi.png", 300),
        ),
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
        assertEquals("/discover?type=tv", bar.typeLinks[1].href)
        assertEquals("Popularity", bar.sortMenu!!.value)
    }

    @Test
    fun `given the year menu then lists shortcuts, decades and the chosen decade's years`() {
        val today = LocalDate.parse("2026-11-22")
        val bar = build(BrowseFilters(decade = BrowseDecade.Of(2020)))
        val year = bar.menu("year")

        assertEquals(listOf("Any year", "This year", "Last year", "Upcoming"), year.sections[0].links.map { it.label })
        assertEquals("Decades", year.sections[1].heading)
        assertEquals(listOf("2020s", "2010s", "2000s", "1990s", "1980s", "1970s", "1960s", "1950s and earlier"), year.sections[1].links.map { it.label })
        assertEquals("2020s", year.value)
        assertEquals("Years in the 2020s", year.sections[2].heading)
        assertEquals((2020..today.year + 1).map { it.toString() }, year.sections[2].links.map { it.label })
        assertEquals("/discover?type=movie&decade=2020s&year=2024", year.sections[2].links[4].href)

        // TV has no Upcoming; "earlier" opens older decades.
        val tvEarlier = build(BrowseFilters(type = MediaType.tvshow, decade = BrowseDecade.Earlier)).menu("year")
        assertEquals(listOf("Any year", "This year", "Last year"), tvEarlier.sections[0].links.map { it.label })
        assertTrue(tvEarlier.sections[1].links.last().active)
        assertEquals(listOf("1950s", "1940s", "1930s", "1920s", "1910s", "1900s"), tvEarlier.sections[2].links.map { it.label })
        assertTrue(tvEarlier.sections[2].reveal)
    }

    @Test
    fun `given the year menu then a decade keeps the menu open on its years and a year closes it`() {
        val year = build(BrowseFilters()).menu("year")
        val decades = year.sections[1].links

        // No decade chosen yet: no years section.
        assertEquals(2, year.sections.size)
        assertTrue(decades.all { it.keepOpen })
        assertEquals("decade-2020", decades.first().option)
        assertEquals("decade-earlier", decades.last().option)
        assertTrue(year.sections[0].links.none { it.keepOpen })

        val years = build(BrowseFilters(decade = BrowseDecade.Of(2020))).menu("year").sections[2]
        assertTrue(years.reveal)
        assertEquals((2020..2027).map { "year-$it" }, years.links.map { it.option })
        assertTrue(years.links.none { it.keepOpen })
    }

    @Test
    fun `given This year and Last year then they pick that single year and replace another decade`() {
        val shortcuts = build(BrowseFilters(decade = BrowseDecade.Of(1990), genre = Genre.HORROR)).menu("year").sections[0].links

        val thisYear = shortcuts.first { it.label == "This year" }
        val lastYear = shortcuts.first { it.label == "Last year" }
        assertEquals("/discover?type=movie&year=2026&genre=horror", thisYear.href)
        assertEquals("2026", thisYear.hint)
        assertEquals("/discover?type=movie&year=2025&genre=horror", lastYear.href)
        assertEquals("2025", lastYear.hint)
        assertFalse(thisYear.active)

        val chosen = build(BrowseFilters(year = BrowseYear.Of(2025))).menu("year")
        assertTrue(chosen.sections[0].links.first { it.label == "Last year" }.active)
        assertFalse(chosen.sections[0].links.first { it.label == "This year" }.active)
        assertEquals("2025", chosen.value)
        // The year's decade and its years show too, for a one-tap change.
        assertTrue(chosen.sections[1].links.first { it.label == "2020s" }.active)
        assertTrue(chosen.sections[2].links.first { it.label == "2025" }.active)
    }

    @Test
    fun `given a year then the menu and the chip show the year and removing it keeps the decade`() {
        val bar = build(BrowseFilters(decade = BrowseDecade.Of(2010), year = BrowseYear.Of(2014)))

        assertEquals("2014", bar.menu("year").value)
        assertTrue(bar.menu("year").sections[1].links.first { it.label == "2010s" }.active)
        assertTrue(bar.menu("year").sections[2].links.first { it.label == "2014" }.active)
        val chip = bar.chips.single()
        assertEquals("2014", chip.label)
        assertEquals("/discover?type=movie&decade=2010s", chip.removeHref)
        assertEquals("Remove 2014", chip.removeLabel)
    }

    @Test
    fun `given every filter then each has a removable chip that drops only itself`() {
        val filters = BrowseFilters(
            popular = BrowsePopular.NOW, decade = BrowseDecade.Earlier, minRating = 7.0, genre = Genre.HORROR, providers = listOf(3),
            language = "ko", runtime = BrowseRuntime.SHORT, hideRated = true, hideWatchlist = true,
        )

        val bar = build(filters)

        assertEquals(
            listOf("Popular now", "1950s and earlier", "Rated 7+", "Horror", "Service 3", "Korean", "Under 90 min", "Hiding rated", "Hiding watchlist"),
            bar.chips.map { it.label },
        )
        assertTrue(bar.chips[3].removeHref.contains("genre=").not())
        assertTrue(bar.chips[3].removeHref.contains("provider=3"))
        assertEquals("/discover?type=movie", bar.clearHref)
        assertEquals("4", bar.menu("more").value)
        assertEquals("Popular now", bar.sortMenu!!.value)
    }

    @Test
    fun `given TV with a movie only genre then the type switch drops it`() {
        val bar = build(BrowseFilters(genre = Genre.HORROR, runtime = BrowseRuntime.LONG))

        assertEquals("/discover?type=tv", bar.typeLinks[1].href)
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
    fun `given the US provider list then the big services come first, in the user's order, with logos`() {
        val service = build(BrowseFilters(), usProviders).menu("service")
        val featured = service.sections[0]

        assertEquals("Streaming in United States", featured.heading)
        assertEquals(
            listOf("Any service", "Netflix", "Prime Video", "Disney+", "HBO Max", "Hulu", "Apple TV+", "Peacock", "Paramount+"),
            featured.links.map { it.label },
        )
        assertEquals("https://image.tmdb.org/t/p/w92/netflix.png", featured.links[1].logoUrl)
        assertTrue(featured.links.drop(1).all { it.checkbox && it.keepOpen && !it.active })
        assertTrue(featured.links[0].active && !featured.links[0].checkbox)
        // Each plan TMDB lists counts: Paramount+ is Premium 2303 + Essential 2616.
        assertEquals("/discover?type=movie&provider=2303%7C2616", featured.links.last().href)
        assertEquals("svc-paramount", featured.links.last().option)
        assertNull(service.value)
        assertNull(service.note)
    }

    @Test
    fun `given the full list then the other services sit behind Show all services by TMDB priority`() {
        val service = build(BrowseFilters(), usProviders).menu("service")
        val all = service.sections[1]

        assertEquals(listOf("fuboTV", "Crunchyroll", "MUBI"), all.links.map { it.label })
        assertEquals("services", all.layout)
        assertEquals("Show all services (3)", all.more!!.label)
        assertFalse(all.more!!.open)
        assertEquals("Find a service", all.more!!.searchLabel)
        assertEquals("/discover?type=movie&provider=283", all.links[1].href)

        // A chosen service from the full list opens it (also without JS) and is ticked there.
        val opened = build(BrowseFilters(providers = listOf(283)), usProviders).menu("service").sections[1]
        assertTrue(opened.more!!.open)
        assertTrue(opened.links.single { it.active }.label == "Crunchyroll")
    }

    @Test
    fun `given several services then each option toggles itself and the label reads Netflix + 2`() {
        val bar = build(BrowseFilters(providers = listOf(8, 15, 1899)), usProviders)
        val service = bar.menu("service")
        val byLabel = service.links().associateBy { it.label }

        assertEquals("Netflix + 2", service.value)
        assertEquals(listOf("Netflix", "HBO Max", "Hulu"), service.links().filter { it.active && it.checkbox }.map { it.label })
        // Unticking keeps the others; ticking adds; Any service clears all.
        assertEquals("/discover?type=movie&provider=15%7C1899", byLabel.getValue("Netflix").href)
        assertEquals("/discover?type=movie&provider=8%7C15%7C337%7C1899", byLabel.getValue("Disney+").href)
        assertEquals("/discover?type=movie", byLabel.getValue("Any service").href)
        assertFalse(byLabel.getValue("Any service").active)

        val chip = bar.chips.single()
        assertEquals("Netflix + 2", chip.label)
        assertEquals("/discover?type=movie", chip.removeHref)
        assertEquals("Remove Netflix + 2", chip.removeLabel)

        // A plan alone (an old link) still ticks its service, and unticking drops every plan.
        val peacock = build(BrowseFilters(providers = listOf(387)), usProviders).menu("service")
        assertEquals("Peacock", peacock.value)
        assertEquals("/discover?type=movie", peacock.links().single { it.label == "Peacock" }.href)
    }

    @Test
    fun `given another region then the featured row is topped up with its best-ranked services`() {
        // Spain: none of the US services' ids (fixture ids 101..125).
        val spain = ProviderOptions(WatchRegion("ES"), (1..25).map { BrowseProvider(100 + it, "Service $it", null, it) }, failed = false)
        val service = build(BrowseFilters(providers = listOf(123)), spain).menu("service")

        assertEquals("Streaming in Spain", service.sections[0].heading)
        assertEquals((1..8).map { "Service $it" }, service.sections[0].links.drop(1).map { it.label })
        assertEquals((9..25).map { "Service $it" }, service.sections[1].links.map { it.label })
        assertEquals("Service 23", service.value)
        assertTrue(service.sections[1].more!!.open)
    }

    @Test
    fun `given the provider list failed then the menu says so and the chip still works`() {
        val failed = ProviderOptions(us, emptyList(), failed = true)
        val bar = build(BrowseFilters(providers = listOf(8)), failed)

        assertEquals("Couldn't load streaming services right now. Try again later.", bar.menu("service").note)
        assertEquals("Service 8", bar.chips.single().label)
        // The chosen id stays in the menu so it can be unticked.
        assertEquals(listOf("Any service", "Service 8"), bar.menu("service").links().map { it.label })
        assertEquals(1, bar.menu("service").sections.size)
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
        assertEquals("/discover?type=movie&popular=week", toggles.links[0].href)
        assertEquals("/discover?type=movie&popular=week&hideRated=1&hideWatchlist=1", toggles.links[1].href)

        val sort = bar.sortMenu!!
        assertEquals("This week", sort.value)
        assertTrue(sort.links().none { it.active })
        assertEquals("/discover?type=movie&sort=title&hideRated=1", sort.links().first { it.label == "Title A-Z" }.href)
        assertEquals("/discover?type=movie&hideRated=1", sort.links().first { it.label == "Popularity" }.href)
        assertTrue(build(BrowseFilters(sort = BrowseSort.RATING)).menu("rating").links().last().active)
    }

    @Test
    fun `given seen titles are shown then every link, the chips and Clear all keep hideSeen=0`() {
        val bar = build(BrowseFilters(genre = Genre.HORROR, decade = BrowseDecade.Of(1980), hideSeen = false))

        val links = bar.typeLinks + bar.menus.flatMap { m -> m.sections.flatMap { it.links } } + bar.sortMenu!!.links()
        assertTrue(links.all { it.href.contains("hideSeen=0") }, links.filterNot { it.href.contains("hideSeen=0") }.toString())
        assertTrue(bar.chips.all { it.removeHref.endsWith("&hideSeen=0") })
        assertEquals("/discover?type=movie&hideSeen=0", bar.clearHref)
        assertEquals("/discover?type=movie", build(BrowseFilters(genre = Genre.HORROR, decade = BrowseDecade.Of(1980))).clearHref)
        assertTrue(bar.chips.none { it.label.contains("seen", ignoreCase = true) }, "the switch shows it, not a chip")
    }

    @Test
    fun `given seen titles are hidden then the More menu only offers hide rated to switch it off`() {
        val library = { f: BrowseFilters -> build(f).menu("more").sections.last().links.map { it.label } }

        assertEquals(listOf("Hide my watchlist"), library(BrowseFilters()))
        assertEquals(listOf("Hide titles I've rated", "Hide my watchlist"), library(BrowseFilters(hideSeen = false)))
        assertEquals(listOf("Hide titles I've rated", "Hide my watchlist"), library(BrowseFilters(hideRated = true)))
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
            assertEquals("https://image.tmdb.org/t/p/w500/m.jpg", it.posterUrl2x)
            assertTrue(it.canToggleWatchlist, "Watched: a seen title can go on the watchlist too (fork, 2026-11-24)")
            assertEquals("8.2", it.tmdbScore)
            assertEquals("8.5", it.userScore)
            assertEquals("eye", it.statusIcon)
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
            assertTrue(it.canToggleWatchlist)
            assertNull(it.statusIcon, "Want to watch is shown by the bookmark, not a badge")
        }
        tiles[2].let {
            assertTrue(it.canToggleWatchlist)
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
