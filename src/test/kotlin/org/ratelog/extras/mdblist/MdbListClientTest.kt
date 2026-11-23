package org.ratelog.extras.mdblist

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.test.FakeHttpServer
import org.ratelog.test.FakeHttpServer.Reply
import org.ratelog.test.MutableClock
import java.time.Duration

class MdbListClientTest {

    private val server = FakeHttpServer()
    private val clock = MutableClock()
    private val fixture = requireNotNull(javaClass.getResource("/mdblist/movie-603.json")).readText()

    private fun client(apiKey: String = "test-key") = MdbListClient(apiKey, baseUrl = server.baseUrl, clock = clock)

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `given no api key then reports NotConfigured without calling MDBList`() {
        // Given
        val client = client(apiKey = "  ")

        // When
        val result = client.fetch(MediaType.movie, TmdbId(603))

        // Then
        assertFalse(client.isConfigured())
        assertEquals(MdbListError.NotConfigured, result.leftOrNull())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `given a movie then calls the tmdb movie route with the key and parses the scores`() {
        // Given
        server.reply = { Reply(200, fixture) }

        // When
        val scores = client().fetch(MediaType.movie, TmdbId(603)).getOrNull()

        // Then
        assertEquals(8.7, scores?.imdbRating)
        assertEquals(83, scores?.rtCritics)
        assertEquals(listOf("/tmdb/movie/603?apikey=test-key"), server.requests)
    }

    @Test
    fun `given a tv show then uses the show route`() {
        server.reply = { Reply(200, """{"ratings":[]}""") }
        client().fetch(MediaType.tvshow, TmdbId(1399))
        assertEquals("/tmdb/show/1399?apikey=test-key", server.requests.single())
    }

    @Test
    fun `given a 404 then reports NotFound`() {
        server.reply = { Reply(404, """{"error":"Not found"}""") }
        assertEquals(MdbListError.NotFound, client().fetch(MediaType.movie, TmdbId(1)).leftOrNull())
    }

    @Test
    fun `given a 500 or an unreadable body then reports Failed`() {
        server.reply = { Reply(500, "oops") }
        assertTrue(client().fetch(MediaType.movie, TmdbId(1)).leftOrNull() is MdbListError.Failed)
    }

    @Test
    fun `given a 429 then stops calling MDBList until Retry-After has passed`() {
        // Given
        server.reply = { Reply(429, """{"error":"API rate limit exceeded!"}""", mapOf("Retry-After" to "300")) }
        val client = client()

        // When
        val first = client.fetch(MediaType.movie, TmdbId(603))
        server.reply = { Reply(200, fixture) }
        val second = client.fetch(MediaType.movie, TmdbId(604))
        val third = client.fetch(MediaType.tvshow, TmdbId(1399))

        // Then
        assertEquals(MdbListError.RateLimited(clock.now.plusSeconds(300)), first.leftOrNull())
        assertTrue(second.leftOrNull() is MdbListError.RateLimited)
        assertTrue(third.leftOrNull() is MdbListError.RateLimited)
        assertEquals(1, server.requests.size, "no calls while paused")
        assertTrue(client.isPaused())

        // And after the pause
        clock.advance(Duration.ofSeconds(300))
        assertFalse(client.isPaused())
        assertEquals(8.7, client.fetch(MediaType.movie, TmdbId(604)).getOrNull()?.imdbRating)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `given the daily limit 429 without Retry-After then pauses until the reset timestamp`() {
        val reset = clock.now.plus(Duration.ofHours(5)).epochSecond
        server.reply = { Reply(429, """{"error":"Daily API limit exceeded!"}""", mapOf("X-RateLimit-Reset" to reset.toString())) }

        val result = client().fetch(MediaType.movie, TmdbId(603))

        assertEquals(MdbListError.RateLimited(clock.now.plus(Duration.ofHours(5))), result.leftOrNull())
    }

    @Test
    fun `given a rejected key then pauses calls for an hour`() {
        server.reply = { Reply(401, """{"error":"Invalid API key!"}""") }
        val client = client()

        assertTrue(client.fetch(MediaType.movie, TmdbId(603)).leftOrNull() is MdbListError.Failed)
        assertTrue(client.fetch(MediaType.movie, TmdbId(604)).leftOrNull() is MdbListError.RateLimited)
        assertEquals(1, server.requests.size)
        clock.advance(Duration.ofHours(1))
        assertFalse(client.isPaused())
    }

    @Test
    fun `given MDBList is unreachable then reports Failed`() {
        val unreachable = MdbListClient("k", baseUrl = "http://127.0.0.1:1", clock = clock)
        assertTrue(unreachable.fetch(MediaType.movie, TmdbId(603)).leftOrNull() is MdbListError.Failed)
    }
}
