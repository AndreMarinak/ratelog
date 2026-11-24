package org.ratelog.person

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.test.FakeHttpServer
import org.ratelog.test.FakeHttpServer.Reply
import org.ratelog.test.InMemoryGeneralConfigRepository
import org.ratelog.test.MutableClock
import java.net.URLDecoder
import java.time.Duration
import java.time.LocalDate

class TmdbPersonClientTest {

    private val server = FakeHttpServer()
    private val config = InMemoryGeneralConfigRepository()
    private val clock = MutableClock()
    private val client = TmdbPersonClient(config, baseUrl = server.baseUrl + "/3", clock = clock)

    @AfterEach
    fun tearDown() = server.close()

    private fun configureKey() =
        config.save(GeneralConfig(id = null, key = ConfigKey.TMDB_API_KEY, value = "tmdb-secret", updatedAtEpochMs = 0L))

    private val hanks = """
        {"id":31,"name":"Tom Hanks","known_for_department":"Acting","birthday":"1956-07-09","deathday":null,
         "place_of_birth":"Concord, California, USA","profile_path":"/hanks.jpg","biography":"Thomas Jeffrey Hanks is an actor.",
         "combined_credits":{
           "cast":[
             {"id":13,"media_type":"movie","title":"Forrest Gump","release_date":"1994-06-23","poster_path":"/fg.jpg","character":"Forrest Gump","vote_count":30959,"popularity":30.2},
             {"id":2574,"media_type":"tv","name":"Bosom Buddies","first_air_date":"1980-11-27","character":"Kip Wilson / Buffy","genre_ids":[35]},
             {"id":2518,"media_type":"tv","name":"The Tonight Show with Jay Leno","character":"Self","genre_ids":[10767,35]},
             {"id":27023,"media_type":"tv","name":"The Oscars","character":"Self - Presenter","genre_ids":[]},
             {"id":1667,"media_type":"tv","name":"Saturday Night Live","character":"Host","genre_ids":[35,10763]},
             {"id":99999,"media_type":"movie","title":"Making of Something","release_date":"2001-01-01","character":"Self"},
             {"id":99998,"media_type":"movie","title":"A Documentary","release_date":"2015-01-01","character":"Self (archive footage)","genre_ids":[99]},
             {"id":99997,"media_type":"movie","title":"Comedy Cameo","release_date":"2010-01-01","character":"Himself","genre_ids":[35]},
             {"id":5,"media_type":"movie","title":"Hidden","adult":true},
             {"media_type":"movie","title":"No id"},
             {"id":6,"media_type":"person","title":"Odd"}
           ],
           "crew":[
             {"id":4,"media_type":"movie","title":"That Thing You Do!","release_date":"1996-10-04","department":"Directing","job":"Director"},
             {"id":4,"media_type":"movie","title":"That Thing You Do!","release_date":"1996-10-04","department":"Writing","job":"Writer"},
             {"id":7,"media_type":"tv","name":"Band of Brothers","first_air_date":"2001-09-09","department":"Production","job":"Executive Producer"}
           ]
         }}
    """.trimIndent()

    @Test
    fun `a person and all their credits come in one request, cached for a day`() {
        configureKey()
        server.reply = { Reply(200, hanks) }

        val person = client.person(31, Lang.en).getOrNull()!!

        assertEquals("Tom Hanks", person.name)
        assertEquals(LocalDate.parse("1956-07-09"), person.birthday)
        assertNull(person.deathday)
        assertEquals("Acting", person.knownForDepartment)
        assertEquals(1, server.requests.size)
        val uri = URLDecoder.decode(server.requests[0], Charsets.UTF_8)
        assertTrue(uri.startsWith("/3/person/31?"), uri)
        assertTrue(uri.contains("append_to_response=combined_credits"), uri)
        assertTrue(uri.contains("language=en"), uri)

        client.person(31, Lang.en)
        assertEquals(1, server.requests.size)
        clock.advance(Duration.ofHours(25))
        client.person(31, Lang.en)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `TV appearances that are not roles are left out, movies are kept`() {
        configureKey()
        server.reply = { Reply(200, hanks) }

        val person = client.person(31, Lang.en).getOrNull()!!
        val acting = person.credits.filter { it.department == ACTING }

        // Talk / news shows and "Self" on TV go; "Self" in a documentary or genre-less film goes, playing yourself in a
        // comedy stays; adult, id-less and odd types go.
        assertEquals(listOf(13, 2574, 99997), acting.map { it.tmdbId })
        assertEquals(MediaType.tvshow, acting[1].type)
        assertEquals("Kip Wilson / Buffy", acting[1].role)
        assertEquals(listOf("Directing", "Writing", "Production"), person.credits.filter { it.department != ACTING }.map { it.department })
    }

    @Test
    fun `an unknown person is NotFound and a failure is not cached`() {
        configureKey()
        server.reply = { Reply(404, """{"status_code":34}""") }
        assertEquals(PersonLookupError.NotFound, client.person(1, Lang.en).leftOrNull())

        server.reply = { Reply(500, "{}") }
        assertTrue(client.person(2, Lang.en).leftOrNull() is PersonLookupError.Failed)
        server.reply = { Reply(200, hanks) }
        assertTrue(client.person(2, Lang.en).isRight())
    }

    @Test
    fun `no API key means no request`() {
        assertEquals(PersonLookupError.ApiKeyMissing, client.person(31, Lang.en).leftOrNull())
        assertEquals(PersonLookupError.ApiKeyMissing, client.search("tom hanks", Lang.en).leftOrNull())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `people search keeps the exact name and popular people with a photo`() {
        configureKey()
        server.reply = {
            Reply(200, """
                {"results":[
                  {"id":31,"name":"Tom Hanks","known_for_department":"Acting","popularity":10.0,"profile_path":"/hanks.jpg",
                   "known_for":[{"title":"Forrest Gump"},{"title":"Toy Story"},{"title":"The Green Mile"},{"title":"Cast Away"}]},
                  {"id":2,"name":"Tom Hanks Jr","popularity":0.2,"profile_path":"/x.jpg"},
                  {"id":3,"name":"Colin Hanks","popularity":3.0,"profile_path":null},
                  {"id":4,"name":"Chet Hanks","known_for_department":"Acting","popularity":2.1,"profile_path":"/chet.jpg","known_for":[{"name":"Your Honor"}]},
                  {"id":5,"name":"Adult Star","popularity":50,"profile_path":"/a.jpg","adult":true}
                ]}
            """.trimIndent())
        }

        val people = client.search("tom hanks", Lang.en).getOrNull()!!

        assertEquals(listOf(31, 4), people.map { it.id })
        assertEquals(listOf("Forrest Gump", "Toy Story", "The Green Mile"), people[0].knownFor)
        val uri = URLDecoder.decode(server.requests[0], Charsets.UTF_8)
        assertTrue(uri.startsWith("/3/search/person?"), uri)
        assertTrue(uri.contains("query=tom hanks"), uri)
        assertTrue(uri.contains("include_adult=false"), uri)

        client.search("Tom  Hanks", Lang.en)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `an obscure person whose name is exactly the query is still found`() {
        configureKey()
        server.reply = { Reply(200, """{"results":[{"id":9,"name":"Nicky Dune","popularity":0.07,"profile_path":null}]}""") }

        assertEquals(listOf(9), client.search("nicky dune", Lang.en).getOrNull()!!.map { it.id })
        assertTrue(client.search("dune", Lang.en).getOrNull()!!.isEmpty())
        assertFalse(server.requests.isEmpty())
    }

    @Test
    fun `query text with symbols is sent encoded`() {
        configureKey()
        server.reply = { Reply(200, """{"results":[]}""") }

        client.search("a&b=c", Lang.en)

        assertTrue(server.requests[0].contains("query=a%26b%3Dc"), server.requests[0])
    }
}
