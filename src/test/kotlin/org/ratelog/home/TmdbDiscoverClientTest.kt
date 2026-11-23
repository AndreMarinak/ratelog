package org.ratelog.home

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.test.FakeTmdbServer
import org.ratelog.test.FakeTmdbServer.Companion.movie
import org.ratelog.test.FakeTmdbServer.Companion.page
import org.ratelog.test.FakeTmdbServer.Companion.show
import org.ratelog.test.InMemoryGeneralConfigRepository
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TmdbDiscoverClientTest {

    private val tmdb = FakeTmdbServer()
    private val config = InMemoryGeneralConfigRepository()
    private val clock = MutableClock(Instant.parse("2026-11-22T12:00:00Z"))
    private val client = TmdbDiscoverClient(config, tmdb.baseUrl, clock)
    private val us = WatchRegion("US")

    @BeforeEach
    fun setUp() {
        config.save(GeneralConfig(id = null, key = ConfigKey.TMDB_API_KEY, value = "test-key", updatedAtEpochMs = 0))
    }

    @AfterEach
    fun tearDown() = tmdb.close()

    @Test
    fun `parses movies with title, year, poster and release date`() {
        tmdb.respond(
            "/trending/movie/week",
            page(movie(131631, "The Hunger Games: Sunrise on the Reaping", releaseDate = "2026-11-20", poster = "/hg.jpg"), movie(2, "Dune", releaseDate = "")),
        )

        val titles = client.fetch(DiscoverFeed.TRENDING_MOVIES, Lang.en, us).getOrNull()!!

        assertEquals(2, titles.size)
        val first = titles[0]
        assertEquals(131631, first.tmdbId)
        assertEquals(MediaType.movie, first.mediaType)
        assertEquals("The Hunger Games: Sunrise on the Reaping", first.title)
        assertEquals(2026, first.year)
        assertEquals("/hg.jpg", first.posterPath)
        assertEquals(LocalDate.of(2026, 11, 20), first.releaseDate)
        assertNull(titles[1].year)
        assertNull(titles[1].releaseDate)
    }

    @Test
    fun `parses shows from name and first_air_date`() {
        tmdb.respond("/tv/popular", page(show(95396, "Severance", firstAirDate = "2022-02-17")))

        val title = client.fetch(DiscoverFeed.POPULAR_TV, Lang.en, us).getOrNull()!!.single()

        assertEquals(95396, title.tmdbId)
        assertEquals(MediaType.tvshow, title.mediaType)
        assertEquals("Severance", title.title)
        assertEquals(2022, title.year)
    }

    @Test
    fun `skips adult titles, titles without a name and results without an id`() {
        tmdb.respond(
            "/movie/now_playing",
            page(
                movie(1, "Fine"),
                movie(2, "Adult", adult = true),
                """{"id":3,"title":"","original_title":" "}""",
                """{"title":"No id"}""",
                """{"id":4,"original_title":"Only original"}""",
            ),
        )

        val titles = client.fetch(DiscoverFeed.NOW_PLAYING, Lang.en, us).getOrNull()!!

        assertEquals(listOf("Fine", "Only original"), titles.map { it.title })
    }

    @Test
    fun `sends the api key and language, and the region only for regional feeds`() {
        tmdb.respond("/movie/upcoming", page())
        tmdb.respond("/trending/tv/week", page())

        client.fetch(DiscoverFeed.UPCOMING, Lang.fr, WatchRegion("FR"))
        client.fetch(DiscoverFeed.TRENDING_TV, Lang.fr, WatchRegion("FR"))

        val upcoming = tmdb.requestsTo("/movie/upcoming").single().query
        assertTrue("api_key=test-key" in upcoming, upcoming)
        assertTrue("language=fr" in upcoming, upcoming)
        assertTrue("region=FR" in upcoming, upcoming)
        val trending = tmdb.requestsTo("/trending/tv/week").single().query
        assertTrue("language=fr" in trending, trending)
        assertTrue("region=" !in trending, trending)
    }

    @Test
    fun `serves repeat requests from the cache for an hour`() {
        tmdb.respond("/trending/movie/week", page(movie(1, "Heat")))

        client.fetch(DiscoverFeed.TRENDING_MOVIES, Lang.en, us)
        clock.advance(Duration.ofMinutes(59))
        val cached = client.fetch(DiscoverFeed.TRENDING_MOVIES, Lang.en, us)

        assertEquals(listOf("Heat"), cached.getOrNull()!!.map { it.title })
        assertEquals(1, tmdb.requests.size)

        clock.advance(Duration.ofMinutes(2))
        client.fetch(DiscoverFeed.TRENDING_MOVIES, Lang.en, us)
        assertEquals(2, tmdb.requests.size)
    }

    @Test
    fun `caches per language, and per region for regional feeds only`() {
        tmdb.respond("/trending/movie/week", page(movie(1, "Heat")))
        tmdb.respond("/movie/now_playing", page(movie(2, "Wicked")))

        client.fetch(DiscoverFeed.TRENDING_MOVIES, Lang.en, us)
        client.fetch(DiscoverFeed.TRENDING_MOVIES, Lang.en, WatchRegion("DE"))
        client.fetch(DiscoverFeed.TRENDING_MOVIES, Lang.de, us)
        client.fetch(DiscoverFeed.NOW_PLAYING, Lang.en, us)
        client.fetch(DiscoverFeed.NOW_PLAYING, Lang.en, WatchRegion("DE"))
        client.fetch(DiscoverFeed.NOW_PLAYING, Lang.en, us)

        assertEquals(2, tmdb.requestsTo("/trending/movie/week").size)
        assertEquals(2, tmdb.requestsTo("/movie/now_playing").size)
    }

    @Test
    fun `an error response is Unavailable, cached briefly, then retried`() {
        tmdb.respond("/tv/popular", """{"status_message":"boom"}""", status = 500)

        assertEquals(DiscoverError.Unavailable, client.fetch(DiscoverFeed.POPULAR_TV, Lang.en, us).leftOrNull())
        assertEquals(DiscoverError.Unavailable, client.fetch(DiscoverFeed.POPULAR_TV, Lang.en, us).leftOrNull())
        assertEquals(1, tmdb.requests.size)

        tmdb.respond("/tv/popular", page(show(1, "Severance")))
        clock.advance(TmdbDiscoverClient.FAILURE_TTL.plusSeconds(1))

        assertEquals(listOf("Severance"), client.fetch(DiscoverFeed.POPULAR_TV, Lang.en, us).getOrNull()!!.map { it.title })
        assertEquals(2, tmdb.requests.size)
    }

    @Test
    fun `a rejected key and unreadable JSON are Unavailable`() {
        tmdb.respond("/trending/movie/week", """{"status_code":7,"status_message":"Invalid API key"}""", status = 401)
        tmdb.respond("/trending/tv/week", "<html>not json</html>")

        assertEquals(DiscoverError.Unavailable, client.fetch(DiscoverFeed.TRENDING_MOVIES, Lang.en, us).leftOrNull())
        assertEquals(DiscoverError.Unavailable, client.fetch(DiscoverFeed.TRENDING_TV, Lang.en, us).leftOrNull())
    }

    @Test
    fun `without an api key it is NotConfigured and never calls TMDB`() {
        config.save(GeneralConfig(id = null, key = ConfigKey.TMDB_API_KEY, value = "  ", updatedAtEpochMs = 0))

        assertEquals(DiscoverError.NotConfigured, client.fetch(DiscoverFeed.TRENDING_MOVIES, Lang.en, us).leftOrNull())
        assertTrue(tmdb.requests.isEmpty())
    }

    @Test
    fun `an unreachable TMDB is Unavailable for every feed`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val offline = TmdbDiscoverClient(config, "http://127.0.0.1:$closedPort/3", clock)

        assertEquals(DiscoverError.Unavailable, offline.fetch(DiscoverFeed.TRENDING_MOVIES, Lang.en, us).leftOrNull())
        assertEquals(DiscoverError.Unavailable, offline.fetch(DiscoverFeed.POPULAR_TV, Lang.en, us).leftOrNull())
    }

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
