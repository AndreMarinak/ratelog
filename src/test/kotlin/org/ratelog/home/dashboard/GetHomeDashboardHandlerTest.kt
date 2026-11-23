package org.ratelog.home.dashboard

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.customlist.CustomList
import org.ratelog.home.DiscoverError
import org.ratelog.home.DiscoverFeed
import org.ratelog.home.DiscoverTitle
import org.ratelog.home.HomeTitle
import org.ratelog.home.TitleMark
import org.ratelog.home.TmdbDiscoverClient
import org.ratelog.test.FakeTmdbServer
import org.ratelog.test.FakeTmdbServer.Companion.movie
import org.ratelog.test.FakeTmdbServer.Companion.page
import org.ratelog.test.FakeTmdbServer.Companion.show
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryGeneralConfigRepository
import org.ratelog.test.InMemoryHomeRepository
import org.ratelog.test.InMemoryListSummaryRepository
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.UserFactory
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class GetHomeDashboardHandlerTest {

    private val home = InMemoryHomeRepository()
    private val users = InMemoryUserRepository()
    private val customLists = InMemoryCustomListRepository()
    private val listSummaries = InMemoryListSummaryRepository(customLists, users)
    private val discover: TmdbDiscoverClient = mock()
    private val today = LocalDate.of(2026, 11, 22)
    private val clock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC)
    private val handler = GetHomeDashboardHandler(home, listSummaries, discover, clock)

    private lateinit var me: User.Id
    private lateinit var other: User.Id

    @BeforeEach
    fun setUp() {
        me = users.save(UserFactory.aUser(username = "me")).id!!
        other = users.save(UserFactory.aUser(username = "other")).id!!
        whenever(discover.fetch(any(), any(), any())).thenReturn(emptyList<DiscoverTitle>().right())
    }

    private fun handle(lang: Lang = Lang.en) = handler.handle(GetHomeDashboard(me, lang))

    private fun local(tmdbId: Int, title: String, type: MediaType = MediaType.movie, score: Double? = null) =
        HomeTitle(tmdbId = tmdbId, mediaType = type, title = title, year = 2020, posterPath = "/$tmdbId.jpg", score = score)

    private fun tmdb(tmdbId: Int, title: String, type: MediaType = MediaType.movie, poster: String? = "/$tmdbId.jpg", releaseDate: LocalDate? = null) =
        DiscoverTitle(tmdbId = tmdbId, mediaType = type, title = title, year = releaseDate?.year, posterPath = poster, releaseDate = releaseDate)

    private fun feed(feed: DiscoverFeed, vararg titles: DiscoverTitle) {
        whenever(discover.fetch(eq(feed), any(), any())).thenReturn(titles.toList().right())
    }

    private fun list(owner: User.Id, name: String, isPublic: Boolean = false) =
        customLists.save(CustomList(id = null, userId = owner, name = ListName(name), isPublic = isPublic, createdAtEpochMs = 1))

    @Test
    fun `rows come in order, trending and theaters first, then personal, then popular TV`() {
        home.setStatusTitles(me, WatchStatus.WATCHING, listOf(local(1, "Severance", MediaType.tvshow)))
        home.setStatusTitles(me, WatchStatus.WANT_TO_WATCH, listOf(local(2, "Heat")))
        home.setRecentlyRated(me, listOf(local(3, "Alien", score = 9.0)))
        list(me, "Heists")
        feed(DiscoverFeed.TRENDING_MOVIES, tmdb(10, "Wicked: For Good"))
        feed(DiscoverFeed.TRENDING_TV, tmdb(11, "Pluribus", MediaType.tvshow))
        feed(DiscoverFeed.NOW_PLAYING, tmdb(12, "Zootopia 2"))
        feed(DiscoverFeed.UPCOMING, tmdb(13, "Avatar: Fire and Ash", releaseDate = today.plusDays(25)))
        feed(DiscoverFeed.POPULAR_TV, tmdb(14, "Stranger Things", MediaType.tvshow))
        feed(DiscoverFeed.CLASSICS, tmdb(15, "Casablanca"))

        val dashboard = handle()

        assertEquals(HomeRowKind.entries.toList(), dashboard.rows.map { it.kind })
        assertEquals(
            listOf(HomeRowKind.TRENDING_MOVIES, HomeRowKind.TRENDING_TV, HomeRowKind.IN_THEATERS, HomeRowKind.COMING_SOON, HomeRowKind.CONTINUE_WATCHING),
            dashboard.rows.take(5).map { it.kind },
        )
        assertEquals(HomeRowKind.POPULAR_TV, dashboard.rows.last().kind)
        assertEquals(DiscoverState.OK, dashboard.discover)
        assertTrue(dashboard.hasPersonalRows)
        assertEquals(listOf("Heists"), dashboard.rows.single { it.kind == HomeRowKind.YOUR_LISTS }.lists.map { it.name })
        assertEquals(listOf("Alien"), dashboard.rows.single { it.kind == HomeRowKind.RECENTLY_RATED }.titles.map { it.title })
    }

    @Test
    fun `personal rows with nothing in them are left out`() {
        home.setStatusTitles(me, WatchStatus.WANT_TO_WATCH, listOf(local(2, "Heat")))
        feed(DiscoverFeed.TRENDING_MOVIES, tmdb(10, "Wicked: For Good"))

        val dashboard = handle()

        assertEquals(listOf(HomeRowKind.TRENDING_MOVIES, HomeRowKind.UP_NEXT), dashboard.rows.map { it.kind })
    }

    @Test
    fun `a new user only gets TMDB rows`() {
        feed(DiscoverFeed.TRENDING_MOVIES, tmdb(10, "Wicked: For Good"))

        val dashboard = handle()

        assertFalse(dashboard.hasPersonalRows)
        assertEquals(listOf(HomeRowKind.TRENDING_MOVIES), dashboard.rows.map { it.kind })
    }

    @Test
    fun `your lists only has your own lists, private ones included`() {
        list(me, "Mine, private")
        list(me, "Mine, public", isPublic = true)
        list(other, "Someone else's", isPublic = true)

        val lists = handle().rows.single { it.kind == HomeRowKind.YOUR_LISTS }.lists

        assertEquals(setOf("Mine, private", "Mine, public"), lists.map { it.name }.toSet())
    }

    @Test
    fun `a failing TMDB feed only drops its own row`() {
        feed(DiscoverFeed.TRENDING_MOVIES, tmdb(10, "Wicked: For Good"))
        feed(DiscoverFeed.POPULAR_TV, tmdb(14, "Stranger Things", MediaType.tvshow))
        whenever(discover.fetch(eq(DiscoverFeed.NOW_PLAYING), any(), any())).thenReturn(DiscoverError.Unavailable.left())

        val dashboard = handle()

        assertEquals(listOf(HomeRowKind.TRENDING_MOVIES, HomeRowKind.POPULAR_TV), dashboard.rows.map { it.kind })
        assertEquals(DiscoverState.OK, dashboard.discover)
    }

    @Test
    fun `when every TMDB feed fails the personal rows still show`() {
        home.setStatusTitles(me, WatchStatus.WATCHING, listOf(local(1, "Severance", MediaType.tvshow)))
        whenever(discover.fetch(any(), any(), any())).thenReturn(DiscoverError.Unavailable.left())

        val dashboard = handle()

        assertEquals(listOf(HomeRowKind.CONTINUE_WATCHING), dashboard.rows.map { it.kind })
        assertEquals(DiscoverState.UNAVAILABLE, dashboard.discover)
    }

    @Test
    fun `without a TMDB key there are no TMDB rows and the state says so`() {
        whenever(discover.fetch(any(), any(), any())).thenReturn(DiscoverError.NotConfigured.left())

        val dashboard = handle()

        assertTrue(dashboard.rows.isEmpty())
        assertEquals(DiscoverState.NOT_CONFIGURED, dashboard.discover)
    }

    @Test
    fun `coming soon keeps future releases only, soonest first`() {
        feed(
            DiscoverFeed.UPCOMING,
            tmdb(1, "Out last week", releaseDate = today.minusDays(7)),
            tmdb(2, "Out today", releaseDate = today),
            tmdb(3, "In a month", releaseDate = today.plusMonths(1)),
            tmdb(4, "Tomorrow", releaseDate = today.plusDays(1)),
            tmdb(5, "No date"),
        )

        val row = handle().rows.single { it.kind == HomeRowKind.COMING_SOON }

        assertEquals(listOf("Tomorrow", "In a month"), row.titles.map { it.title })
        assertEquals(today.plusDays(1), row.titles.first().releaseDate)
    }

    @Test
    fun `TMDB rows drop titles without a poster and duplicates, and stop at 20`() {
        val many = (1..30).map { tmdb(it, "Movie $it") } + tmdb(1, "Movie 1 again") + tmdb(99, "No poster", poster = null)
        feed(DiscoverFeed.TRENDING_MOVIES, *many.toTypedArray())

        val titles = handle().rows.single { it.kind == HomeRowKind.TRENDING_MOVIES }.titles

        assertEquals(GetHomeDashboardHandler.ROW_LIMIT, titles.size)
        assertEquals((1..20).toList(), titles.map { it.tmdbId })
    }

    @Test
    fun `TMDB tiles carry your status and score, looked up once per media type`() {
        feed(DiscoverFeed.TRENDING_MOVIES, tmdb(10, "Wicked: For Good"), tmdb(11, "Heat"))
        feed(DiscoverFeed.NOW_PLAYING, tmdb(10, "Wicked: For Good"))
        feed(DiscoverFeed.TRENDING_TV, tmdb(10, "A show with the same id", MediaType.tvshow))
        home.setMark(me, MediaType.movie, 10, TitleMark(WatchStatus.WANT_TO_WATCH, null))
        home.setMark(me, MediaType.movie, 11, TitleMark(WatchStatus.WATCHED, 8.5))
        home.setMark(other, MediaType.tvshow, 10, TitleMark(WatchStatus.DROPPED, 2.0))

        val rows = handle().rows.associateBy { it.kind }

        val trending = rows.getValue(HomeRowKind.TRENDING_MOVIES).titles.associateBy { it.tmdbId }
        assertEquals(WatchStatus.WANT_TO_WATCH, trending.getValue(10).status)
        assertNull(trending.getValue(10).score)
        assertEquals(WatchStatus.WATCHED, trending.getValue(11).status)
        assertEquals(8.5, trending.getValue(11).score)
        assertEquals(WatchStatus.WANT_TO_WATCH, rows.getValue(HomeRowKind.IN_THEATERS).titles.single().status)
        // Same TMDB id, other media type, and another user's status: no badge.
        assertNull(rows.getValue(HomeRowKind.TRENDING_TV).titles.single().status)
        assertEquals(2, home.markQueries.get())
    }

    @Test
    fun `TMDB feeds get the metadata language and its region`() {
        handler.handle(GetHomeDashboard(me, Lang.fr))

        verify(discover).fetch(DiscoverFeed.NOW_PLAYING, Lang.fr, WatchRegion("FR"))
        verify(discover).fetch(DiscoverFeed.UPCOMING, Lang.fr, WatchRegion("FR"))
        verify(discover).fetch(DiscoverFeed.TRENDING_MOVIES, Lang.fr, WatchRegion("FR"))
    }

    @Test
    fun `a second home view is served from the TMDB cache`() {
        FakeTmdbServer().use { tmdb ->
            tmdb.respond("/trending/movie/week", page(movie(131631, "The Hunger Games: Sunrise on the Reaping")))
            tmdb.respond("/trending/tv/week", page(show(1, "Pluribus")))
            tmdb.respond("/movie/now_playing", page(movie(2, "Wicked: For Good")))
            tmdb.respond("/movie/upcoming", page(movie(3, "Avatar: Fire and Ash", releaseDate = "2026-12-19")))
            tmdb.respond("/tv/popular", page(show(4, "Stranger Things")))
            val config = InMemoryGeneralConfigRepository().apply {
                save(GeneralConfig(id = null, key = ConfigKey.TMDB_API_KEY, value = "test-key", updatedAtEpochMs = 0))
            }
            val realHandler = GetHomeDashboardHandler(home, listSummaries, TmdbDiscoverClient(config, tmdb.baseUrl, clock), clock)

            val first = realHandler.handle(GetHomeDashboard(me, Lang.en))
            val second = realHandler.handle(GetHomeDashboard(me, Lang.en))

            assertEquals(DiscoverFeed.entries.size, tmdb.requests.size) // one call per feed, then none
            assertEquals(first.rows.map { it.kind }, second.rows.map { it.kind })
            assertEquals(
                "The Hunger Games: Sunrise on the Reaping",
                second.rows.single { it.kind == HomeRowKind.TRENDING_MOVIES }.titles.single().title,
            )
            assertEquals(5, second.rows.size)
        }
    }
}
