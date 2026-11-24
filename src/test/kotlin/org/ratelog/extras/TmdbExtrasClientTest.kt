package org.ratelog.extras

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.test.FakeHttpServer
import org.ratelog.test.FakeHttpServer.Reply
import org.ratelog.test.InMemoryGeneralConfigRepository
import java.time.LocalDate

class TmdbExtrasClientTest {

    private val server = FakeHttpServer()
    private val config = InMemoryGeneralConfigRepository()
    private val client = TmdbExtrasClient(config, baseUrl = server.baseUrl + "/3")

    private fun configureKey(value: String = "tmdb-key") =
        config.save(GeneralConfig(id = null, key = ConfigKey.TMDB_API_KEY, value = value, updatedAtEpochMs = 0L))

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `given no TMDB key then reports ApiKeyMissing without calling TMDB`() {
        assertEquals(TmdbExtrasError.ApiKeyMissing, client.fetch(MediaType.movie, TmdbId(603)).leftOrNull())
        configureKey(" ")
        assertEquals(TmdbExtrasError.ApiKeyMissing, client.fetch(MediaType.movie, TmdbId(603)).leftOrNull())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `given a movie then fetches details, videos and external ids in one call`() {
        // Given
        configureKey()
        server.reply = {
            Reply(
                200,
                """
                {"id":603,"title":"The Matrix","release_date":"1999-03-31","vote_average":8.2,"vote_count":26543,"runtime":136,
                 "imdb_id":"tt0133093",
                 "videos":{"results":[
                   {"key":"teaser00001","site":"YouTube","type":"Teaser","official":true,"iso_639_1":"en","size":1080},
                   {"key":"vKQi3bBA1y8","site":"YouTube","type":"Trailer","official":true,"iso_639_1":"en","size":1080}
                 ]},
                 "external_ids":{"imdb_id":"tt0133093","wikidata_id":"Q83495"}}
                """.trimIndent(),
            )
        }

        // When
        val extras = client.fetch(MediaType.movie, TmdbId(603)).getOrNull()

        // Then
        assertEquals(
            TmdbExtras(
                title = "The Matrix",
                latestReleaseDate = LocalDate.parse("1999-03-31"),
                voteAverage = 8.2,
                voteCount = 26543,
                runtimeMinutes = 136,
                numberOfSeasons = null,
                trailerYoutubeKey = "vKQi3bBA1y8",
                imdbId = "tt0133093",
                credits = TitleCredits(),
            ),
            extras,
        )
        val request = server.requests.single()
        assertTrue(request.startsWith("/3/movie/603?"), request)
        assertTrue(request.contains("append_to_response=videos,external_ids,credits,release_dates"), request)
        assertTrue(request.contains("api_key=tmdb-key"), request)
    }

    @Test
    fun `given a show then reads episode runtime, seasons and the last air date`() {
        // Given
        configureKey()
        server.reply = {
            Reply(
                200,
                """
                {"id":1399,"name":"Game of Thrones","first_air_date":"2011-04-17","last_air_date":"2019-05-19",
                 "vote_average":8.5,"vote_count":25000,"episode_run_time":[],"number_of_seasons":8,
                 "last_episode_to_air":{"runtime":80,"air_date":"2019-05-19"},
                 "videos":{"results":[]},"external_ids":{"imdb_id":"tt0944947"}}
                """.trimIndent(),
            )
        }

        // When
        val extras = client.fetch(MediaType.tvshow, TmdbId(1399)).getOrNull()!!

        // Then
        assertEquals("Game of Thrones", extras.title)
        assertEquals(LocalDate.parse("2019-05-19"), extras.latestReleaseDate)
        assertEquals(80, extras.runtimeMinutes)
        assertEquals(8, extras.numberOfSeasons)
        assertNull(extras.trailerYoutubeKey)
        assertEquals("tt0944947", extras.imdbId)
        assertTrue(server.requests.single().startsWith("/3/tv/1399?"))
        assertTrue(server.requests.single().contains("append_to_response=videos,external_ids,aggregate_credits,content_ratings"))
    }

    @Test
    fun `given missing or zero values then leaves them out`() {
        val extras = TmdbExtrasResponse(title = " ", releaseDate = "", voteAverage = 0.0, voteCount = 0, runtime = 0, imdbId = "")
            .let { with(TmdbExtrasClient) { it.toExtras(MediaType.movie) } }

        assertEquals(TmdbExtras(null, null, null, null, null, null, null, null, credits = TitleCredits()), extras)
    }

    @Test
    fun `given TMDB answers 404 then reports NotFound, other errors Failed`() {
        configureKey()
        server.reply = { Reply(404, """{"status_code":34}""") }
        assertEquals(TmdbExtrasError.NotFound, client.fetch(MediaType.movie, TmdbId(999999)).leftOrNull())

        server.reply = { Reply(503, "") }
        assertTrue(client.fetch(MediaType.movie, TmdbId(603)).leftOrNull() is TmdbExtrasError.Failed)
    }
}
