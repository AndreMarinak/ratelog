package org.ratelog.tmdb

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.test.FakeHttpServer
import org.ratelog.test.FakeHttpServer.Reply
import org.ratelog.test.InMemoryGeneralConfigRepository

/** Fork (2026-11-24): the search page's Year box goes to TMDB as its own year parameters. */
class TmdbClientYearTest {
    private val server = FakeHttpServer().apply { reply = { Reply(200, """{"page":1,"total_pages":1,"results":[]}""") } }
    private val config = InMemoryGeneralConfigRepository().apply {
        save(GeneralConfig(id = null, key = ConfigKey.TMDB_API_KEY, value = "k", updatedAtEpochMs = 0L))
    }
    private val client = TmdbClient(config, baseUrl = server.baseUrl + "/3")

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `movies are narrowed with primary_release_year, shows with first_air_date_year`() {
        assertTrue(client.searchMovies("dune", Lang.en, 1, 1984).isRight())
        assertTrue(client.searchTvShows("dune", Lang.en, 1, 2021).isRight())

        assertTrue(server.requests[0].startsWith("/3/search/movie?"), server.requests[0])
        assertTrue(server.requests[0].contains("primary_release_year=1984"), server.requests[0])
        assertTrue(server.requests[1].startsWith("/3/search/tv?"), server.requests[1])
        assertTrue(server.requests[1].contains("first_air_date_year=2021"), server.requests[1])
    }

    @Test
    fun `without a year no year parameter is sent`() {
        client.searchMovies("dune", Lang.en)
        client.searchTvShows("dune", Lang.en)

        assertEquals(2, server.requests.size)
        server.requests.forEach { assertFalse(it.contains("year"), it) }
    }
}
