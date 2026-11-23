package org.ratelog.recommendations

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.test.FakeTmdbServer
import org.ratelog.test.FakeTmdbServer.Companion.movie
import org.ratelog.test.FakeTmdbServer.Companion.show
import org.ratelog.test.InMemoryGeneralConfigRepository
import org.ratelog.test.MutableClock
import java.time.Duration
import java.time.LocalDate

class TmdbRecommendationsClientTest {

    private val tmdb = FakeTmdbServer()
    private val config = InMemoryGeneralConfigRepository()
    private val clock = MutableClock()
    private val client = TmdbRecommendationsClient(config, tmdb.baseUrl, cacheHours = 24, collectionCacheDays = 7, clock = clock)

    @BeforeEach
    fun setUp() {
        config.save(GeneralConfig(id = null, key = ConfigKey.TMDB_API_KEY, value = "test-key", updatedAtEpochMs = 0))
    }

    @AfterEach
    fun tearDown() = tmdb.close()

    private fun recs(vararg results: String, page: Int = 1, totalPages: Int = 3) =
        """{"page":$page,"results":[${results.joinToString(",")}],"total_pages":$totalPages,"total_results":60}"""

    private fun movieWithRecs(collection: String?, vararg results: String, totalPages: Int = 3) =
        """{"id":863,"title":"Toy Story 2","belongs_to_collection":${collection ?: "null"},"recommendations":${recs(*results, totalPages = totalPages)}}"""

    private fun voted(json: String, votes: Int = 500, average: Double = 7.5) =
        json.removeSuffix("}") + ""","vote_count":$votes,"vote_average":$average}"""

    @Test
    fun `a movie's first page comes from the movie with recommendations appended, in the metadata language`() {
        tmdb.respond(
            "/movie/863",
            movieWithRecs(
                """{"id":10194,"name":"Toy Story Collection","poster_path":"/c.jpg"}""",
                voted(movie(10193, "Toy Story 3", releaseDate = "2010-06-16", poster = "/ts3.jpg"), votes = 16477, average = 7.8),
                movie(585, "Monsters, Inc.", releaseDate = "2001-11-01"),
                totalPages = 36,
            ),
        )

        val page = client.recommendations(MediaType.movie, 863, Lang.es).getOrNull()!!

        assertEquals(CollectionRef(10194, "Toy Story Collection"), page.collection)
        assertEquals(listOf(10193, 585), page.titles.map { it.tmdbId })
        val first = page.titles.first()
        assertEquals("Toy Story 3", first.title)
        assertEquals(LocalDate.of(2010, 6, 16), first.releaseDate)
        assertEquals(2010, first.year)
        assertEquals("/ts3.jpg", first.posterPath)
        assertEquals(16477, first.voteCount)
        assertEquals(7.8, first.voteAverage)
        assertEquals(0, page.titles[1].voteCount)
        assertNull(page.titles[1].voteAverage, "no votes, no average")
        assertEquals(1, page.page)
        assertEquals(36, page.totalPages)

        val query = tmdb.requestsTo("/movie/863").single().query
        assertTrue(query.contains("append_to_response=recommendations"), query)
        assertTrue(query.contains("language=es"), query)
        assertTrue(query.contains("api_key=test-key"), query)
    }

    @Test
    fun `later pages and shows use the recommendations endpoint`() {
        tmdb.respond("/movie/863/recommendations", recs(movie(12, "Finding Nemo"), page = 2, totalPages = 36))
        tmdb.respond("/tv/1396/recommendations", recs(show(60059, "Better Call Saul", firstAirDate = "2015-02-08"), totalPages = 36))

        val second = client.recommendations(MediaType.movie, 863, Lang.en, page = 2).getOrNull()!!
        val tv = client.recommendations(MediaType.tvshow, 1396, Lang.en).getOrNull()!!

        assertNull(second.collection)
        assertEquals(2, second.page)
        assertEquals("Finding Nemo", second.titles.single().title)
        assertTrue(tmdb.requestsTo("/movie/863/recommendations").single().query.contains("page=2"))
        assertNull(tv.collection)
        assertEquals(MediaType.tvshow, tv.titles.single().type)
        assertEquals("Better Call Saul", tv.titles.single().title)
        assertEquals(2015, tv.titles.single().year)
        assertTrue(tmdb.requestsTo("/movie/1396").isEmpty(), "shows never load the movie")
    }

    @Test
    fun `skips adult titles, results without an id or a name and the other media type, keeps posterless ones`() {
        tmdb.respond(
            "/movie/1/recommendations",
            recs(
                movie(2, "Adult", adult = true),
                """{"id":0,"title":"No id"}""",
                """{"id":3,"title":"  "}""",
                """{"id":4,"name":"A show","media_type":"tv"}""",
                movie(5, "No poster", poster = null),
                """{"id":6,"title":"Bad date","release_date":"soon","poster_path":"/p.jpg"}""",
            ),
        )

        val titles = client.recommendations(MediaType.movie, 1, Lang.en, page = 2).getOrNull()!!.titles

        assertEquals(listOf(5, 6), titles.map { it.tmdbId })
        assertNull(titles[0].posterPath)
        assertNull(titles[1].releaseDate)
    }

    @Test
    fun `collections list their parts`() {
        tmdb.respond(
            "/collection/10194",
            """{"id":10194,"name":"Toy Story Collection","parts":[${movie(862, "Toy Story", releaseDate = "1995-11-22")},${movie(1084244, "Toy Story 5", releaseDate = "")}]}""",
        )

        val collection = client.collection(10194, Lang.en).getOrNull()!!

        assertEquals("Toy Story Collection", collection.name)
        assertEquals(listOf("Toy Story", "Toy Story 5"), collection.parts.map { it.title })
        assertNull(collection.parts[1].releaseDate)
        assertTrue(tmdb.requestsTo("/collection/10194").single().query.contains("language=en"))
    }

    @Test
    fun `answers are cached per title, page and language until they expire`() {
        tmdb.respond("/movie/863", movieWithRecs(null, movie(585, "Monsters, Inc.")))
        tmdb.respond("/collection/10194", """{"id":10194,"name":"Toy Story Collection","parts":[]}""")

        repeat(3) { client.recommendations(MediaType.movie, 863, Lang.en) }
        repeat(3) { client.collection(10194, Lang.en) }
        assertEquals(1, tmdb.requestsTo("/movie/863").size)
        assertEquals(1, tmdb.requestsTo("/collection/10194").size)

        client.recommendations(MediaType.movie, 863, Lang.de)
        assertEquals(2, tmdb.requestsTo("/movie/863").size, "another language is another entry")

        clock.advance(Duration.ofHours(25))
        client.recommendations(MediaType.movie, 863, Lang.en)
        client.collection(10194, Lang.en)
        assertEquals(3, tmdb.requestsTo("/movie/863").size, "recommendations last a day")
        assertEquals(1, tmdb.requestsTo("/collection/10194").size, "collections last a week")

        clock.advance(Duration.ofDays(7))
        client.collection(10194, Lang.en)
        assertEquals(2, tmdb.requestsTo("/collection/10194").size)
    }

    @Test
    fun `a failure is returned, then not retried for a couple of minutes`() {
        tmdb.respond("/movie/863", """{"status_message":"boom"}""", status = 500)

        assertEquals(TmdbRecommendationsError.Unavailable, client.recommendations(MediaType.movie, 863, Lang.en).leftOrNull())
        assertEquals(TmdbRecommendationsError.Unavailable, client.recommendations(MediaType.movie, 863, Lang.en).leftOrNull())
        assertEquals(1, tmdb.requestsTo("/movie/863").size)

        tmdb.respond("/movie/863", movieWithRecs(null, movie(585, "Monsters, Inc.")))
        clock.advance(TmdbRecommendationsClient.FAILURE_PAUSE.plusSeconds(1))
        assertEquals(1, client.recommendations(MediaType.movie, 863, Lang.en).getOrNull()!!.titles.size)
        assertEquals(2, tmdb.requestsTo("/movie/863").size)
    }

    @Test
    fun `an unknown title is NotFound`() {
        assertEquals(TmdbRecommendationsError.NotFound, client.recommendations(MediaType.tvshow, 999999, Lang.en).leftOrNull())
        assertEquals(TmdbRecommendationsError.NotFound, client.collection(999999, Lang.en).leftOrNull())
    }

    @Test
    fun `without an API key nothing is requested`() {
        config.save(GeneralConfig(id = null, key = ConfigKey.TMDB_API_KEY, value = "  ", updatedAtEpochMs = 0))

        assertEquals(TmdbRecommendationsError.ApiKeyMissing, client.recommendations(MediaType.movie, 863, Lang.en).leftOrNull())
        assertEquals(TmdbRecommendationsError.ApiKeyMissing, client.collection(10194, Lang.en).leftOrNull())
        assertTrue(tmdb.requests.isEmpty())
    }
}
