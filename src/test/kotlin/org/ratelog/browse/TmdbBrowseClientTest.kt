package org.ratelog.browse

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Genre
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.test.FakeHttpServer
import org.ratelog.test.FakeHttpServer.Reply
import org.ratelog.test.InMemoryGeneralConfigRepository
import org.ratelog.test.MutableClock
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate

class TmdbBrowseClientTest {

    private val server = FakeHttpServer()
    private val config = InMemoryGeneralConfigRepository()
    private val clock = MutableClock()
    private val client = TmdbBrowseClient(config, baseUrl = server.baseUrl + "/3", pageCacheMinutes = 45, clock = clock)
    private val today = LocalDate.parse("2026-11-22")
    private val us = WatchRegion("US")

    private val logAppender = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = (LoggerFactory.getLogger(TmdbBrowseClient::class.java) as Logger).apply { addAppender(logAppender) }

    private fun configureKey(value: String = "tmdb-secret-key") =
        config.save(GeneralConfig(id = null, key = ConfigKey.TMDB_API_KEY, value = value, updatedAtEpochMs = 0L))

    private fun request(filters: BrowseFilters = BrowseFilters(), page: Int = 1) =
        BrowseQueryBuilder.build(filters, page, Lang.en, us, today)

    @AfterEach
    fun tearDown() {
        logger.detachAppender(logAppender)
        server.close()
    }

    private val moviePage = """
        {"page":1,"total_pages":812,"total_results":16240,"results":[
          {"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-31","poster_path":"/matrix.jpg",
           "vote_average":8.2,"vote_count":26543,"genre_ids":[28,878],"original_language":"en"},
          {"id":11,"title":"Unrated","release_date":"","poster_path":null,"vote_average":0.0,"vote_count":0},
          {"id":12,"title":"Hidden","adult":true},
          {"title":"No id"},
          {"id":13,"title":" ","original_title":"Fallback Title","release_date":"2001-07"}
        ]}
    """.trimIndent()

    @Test
    fun `given no TMDB key then reports ApiKeyMissing without calling TMDB`() {
        assertEquals(TmdbBrowseError.ApiKeyMissing, client.page(request()).leftOrNull())
        configureKey(" ")
        assertEquals(TmdbBrowseError.ApiKeyMissing, client.providers(MediaType.movie, us, Lang.en).leftOrNull())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `given a discover page then sends the filters with the key and parses the results`() {
        // Given
        configureKey()
        server.reply = { Reply(200, moviePage) }

        // When
        val page = client.page(request(BrowseFilters(genre = Genre.SCIENCE_FICTION, minRating = 7.0))).getOrNull()!!

        // Then
        val sent = server.requests.single()
        assertTrue(sent.startsWith("/3/discover/movie?"), sent)
        assertTrue(sent.contains("with_genres=878"), sent)
        assertTrue(sent.contains("vote_average.gte=7"), sent)
        assertTrue(sent.contains("api_key=tmdb-secret-key"), sent)

        assertEquals(500, page.totalPages)
        assertEquals(16240, page.totalResults)
        assertEquals(listOf(603, 11, 13), page.items.map { it.tmdbId })
        val matrix = page.items[0]
        assertEquals(
            BrowseTitle(603, MediaType.movie, "The Matrix", LocalDate.parse("1999-03-31"), "/matrix.jpg", 8.2, 26543, listOf(28, 878), "en"),
            matrix,
        )
        assertEquals(1999, matrix.year)
        assertNull(page.items[1].voteAverage, "no votes = no score")
        assertNull(page.items[1].releaseDate)
        assertEquals("Fallback Title", page.items[2].title)
        assertNull(page.items[2].releaseDate)
    }

    @Test
    fun `given a TV page then reads name and first air date`() {
        configureKey()
        server.reply = {
            Reply(200, """{"page":1,"total_pages":3,"total_results":55,"results":[{"id":1399,"name":"Game of Thrones","first_air_date":"2011-04-17","vote_average":8.5,"vote_count":25000}]}""")
        }

        val page = client.page(request(BrowseFilters(type = MediaType.tvshow))).getOrNull()!!

        assertTrue(server.requests.single().startsWith("/3/discover/tv?"))
        assertEquals(3, page.totalPages)
        val show = page.items.single()
        assertEquals("Game of Thrones", show.title)
        assertEquals(MediaType.tvshow, show.type)
        assertEquals(2011, show.year)
    }

    @Test
    fun `given the same query twice then TMDB is called once until the entry expires`() {
        // Given
        configureKey()
        server.reply = { Reply(200, moviePage) }

        // When
        client.page(request(BrowseFilters(genre = Genre.DRAMA)))
        client.page(request(BrowseFilters(genre = Genre.DRAMA)))
        client.page(request(BrowseFilters(genre = Genre.DRAMA), page = 2))
        clock.advance(Duration.ofMinutes(44))
        client.page(request(BrowseFilters(genre = Genre.DRAMA)))

        // Then
        assertEquals(2, server.requests.size)

        clock.advance(Duration.ofMinutes(2))
        client.page(request(BrowseFilters(genre = Genre.DRAMA)))
        assertEquals(3, server.requests.size)
    }

    @Test
    fun `given a failure then it is not cached and the next request retries`() {
        configureKey()
        server.reply = { Reply(503, "") }
        assertTrue(client.page(request()).leftOrNull() is TmdbBrowseError.Failed)

        server.reply = { Reply(200, moviePage) }
        assertTrue(client.page(request()).isRight())
        assertTrue(client.page(request()).isRight())
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `given TMDB errors then maps them and never logs the key or the URL`() {
        // Given
        configureKey()

        // When / Then
        server.reply = { Reply(401, """{"status_code":7,"status_message":"Invalid API key"}""") }
        assertEquals(TmdbBrowseError.ApiKeyRejected, client.page(request()).leftOrNull())

        server.reply = { Reply(500, """{"status_code":11}""") }
        assertTrue(client.page(request(page = 2)).leftOrNull() is TmdbBrowseError.Failed)

        server.reply = { Reply(200, "{not json") }
        assertTrue(client.page(request(page = 3)).leftOrNull() is TmdbBrowseError.Failed)

        val dead = FakeHttpServer()
        val deadUrl = dead.baseUrl
        dead.close()
        val unreachable = TmdbBrowseClient(config, baseUrl = "$deadUrl/3", clock = clock)
        assertTrue(unreachable.page(request()).leftOrNull() is TmdbBrowseError.Failed)

        val messages = logAppender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        assertEquals(4, messages.size, messages.toString())
        messages.forEach { message ->
            assertFalse(message.contains("tmdb-secret-key"), message)
            assertFalse(message.contains("api_key"), message)
            assertFalse(message.contains("127.0.0.1"), message)
        }
        assertTrue(messages[0].contains("status=401"), messages[0])
        assertTrue(messages[1].contains("status=500"), messages[1])
    }

    @Test
    fun `given the provider list then orders by the region's priority and caches it for a day`() {
        // Given
        configureKey()
        server.reply = {
            Reply(
                200,
                """
                {"results":[
                  {"provider_id":337,"provider_name":"Disney Plus","logo_path":"/disney.jpg","display_priority":1,"display_priorities":{"US":3,"ES":1}},
                  {"provider_id":8,"provider_name":"Netflix","logo_path":"/netflix.jpg","display_priority":5,"display_priorities":{"US":0}},
                  {"provider_id":9,"provider_name":"Amazon Prime Video","logo_path":"","display_priority":2},
                  {"provider_id":8,"provider_name":"Netflix duplicate","display_priorities":{"US":9}},
                  {"provider_id":0,"provider_name":"Broken"},
                  {"provider_id":15,"provider_name":"  "},
                  {"provider_id":99,"provider_name":"Zeta","display_priorities":{"US":3}}
                ]}
                """.trimIndent(),
            )
        }

        // When
        val providers = client.providers(MediaType.tvshow, us, Lang.en).getOrNull()!!
        client.providers(MediaType.tvshow, us, Lang.en)

        // Then
        assertEquals(listOf("Netflix", "Amazon Prime Video", "Disney Plus", "Zeta"), providers.map { it.name })
        assertEquals(listOf(8, 9, 337, 99), providers.map { it.id })
        assertNull(providers[1].logoPath)
        assertEquals("/netflix.jpg", providers[0].logoPath)
        val sent = server.requests.single()
        assertTrue(sent.startsWith("/3/watch/providers/tv?"), sent)
        assertTrue(sent.contains("watch_region=US"), sent)

        clock.advance(Duration.ofHours(25))
        client.providers(MediaType.tvshow, us, Lang.en)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `given the provider list fails then waits a few minutes before asking TMDB again`() {
        configureKey()
        server.reply = { Reply(502, "") }
        assertTrue(client.providers(MediaType.movie, us, Lang.en).isLeft())
        assertTrue(client.providers(MediaType.movie, us, Lang.en).isLeft())
        assertEquals(1, server.requests.size)

        clock.advance(Duration.ofMinutes(6))
        server.reply = { Reply(200, """{"results":[{"provider_id":8,"provider_name":"Netflix"}]}""") }
        assertEquals(listOf(8), client.providers(MediaType.movie, us, Lang.en).getOrNull()!!.map { it.id })
        assertEquals(2, server.requests.size)
    }
}
