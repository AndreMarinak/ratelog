package org.ratelog.extras

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Director / writers / cast / age rating / tagline from TMDB's appended sub-resources (fork, 2026-11-24). */
class TitleCreditsParserTest {

    private val mapper = jacksonObjectMapper()
    private fun response(json: String): TmdbExtrasResponse = mapper.readValue(json)

    private val godfather = """
        {"id":238,"title":"The Godfather","tagline":"An offer you can't refuse.","budget":6000000,"revenue":245066411,
         "production_companies":[{"id":4,"name":"Paramount Pictures"},{"id":10211,"name":"Alfran Productions"},
                                 {"id":70,"name":"Albert S. Ruddy Productions"},{"id":71,"name":"A Fourth Company"}],
         "credits":{
           "cast":[
             {"id":1158,"name":"Al Pacino","character":"Michael Corleone","profile_path":"/m8.jpg","order":1},
             {"id":3084,"name":"Marlon Brando","character":"Don Vito Corleone","profile_path":"/yI.jpg","order":0},
             {"id":3085,"name":"James Caan","character":"Sonny Corleone","profile_path":null,"order":2},
             {"id":3087,"name":"Robert Duvall","character":"","profile_path":"/3t.jpg","order":3},
             {"id":3086,"name":"Richard S. Castellano","character":"Clemenza","order":4},
             {"id":3092,"name":"Diane Keaton","character":"Kay Adams","order":5},
             {"id":3094,"name":"Talia Shire","character":"Connie","order":6},
             {"id":3095,"name":"Gianni Russo","character":"Carlo Rizzi","order":7},
             {"id":3096,"name":"Sterling Hayden","character":"McCluskey","order":8},
             {"id":3097,"name":"John Marley","character":"Jack Woltz","order":9},
             {"id":3098,"name":"Richard Conte","character":"Barzini","order":10},
             {"id":3099,"name":" ","character":"Nobody","order":11}
           ],
           "crew":[
             {"id":1776,"name":"Francis Ford Coppola","job":"Screenplay","department":"Writing"},
             {"id":3083,"name":"Mario Puzo","job":"Novel","department":"Writing"},
             {"id":3083,"name":"Mario Puzo","job":"Screenplay","department":"Writing"},
             {"id":1776,"name":"Francis Ford Coppola","job":"Director","department":"Directing"},
             {"id":5000,"name":"Story Editor Person","job":"Story Editor","department":"Writing"},
             {"id":3101,"name":"Gordon Willis","job":"Director of Photography","department":"Camera"},
             {"id":1776,"name":"Francis Ford Coppola","job":"Director","department":"Directing"}
           ]},
         "release_dates":{"results":[
           {"iso_3166_1":"GB","release_dates":[{"certification":"15","type":3}]},
           {"iso_3166_1":"US","release_dates":[
             {"certification":"","type":1},
             {"certification":"PG-13","type":4},
             {"certification":"R","type":3}
           ]}]}}
    """.trimIndent()

    @Test
    fun `given a movie then reads director, writers with their jobs, cast in billing order and the US rating`() {
        val credits = TitleCreditsParser.movie(response(godfather))

        assertEquals("An offer you can't refuse.", credits.tagline)
        assertEquals("R", credits.certification, "the theatrical US rating beats the digital one, other countries are ignored")
        assertEquals(listOf(CreditPerson(1776, "Francis Ford Coppola")), credits.directors, "listed twice, shown once")
        assertEquals(
            listOf(
                CreditPerson(1776, "Francis Ford Coppola", listOf("Screenplay")),
                CreditPerson(3083, "Mario Puzo", listOf("Screenplay", "Novel")),
            ),
            credits.writers,
            "one entry per person, most important job first; story editors are left out",
        )
        assertEquals(10, credits.cast.size, "top 10 by billing order, nameless entries skipped")
        assertEquals(CastMember(3084, "Marlon Brando", "Don Vito Corleone", "/yI.jpg"), credits.cast[0])
        assertEquals("Al Pacino", credits.cast[1].name)
        assertNull(credits.cast[2].profilePath, "no photo")
        assertNull(credits.cast[3].character, "blank character")
        assertEquals(listOf("Paramount Pictures", "Alfran Productions", "Albert S. Ruddy Productions"), credits.studios)
        assertEquals(6_000_000L, credits.budget)
        assertEquals(245_066_411L, credits.revenue)
        assertTrue(credits.createdBy.isEmpty() && credits.networks.isEmpty())
        assertNull(credits.numberOfEpisodes)
    }

    @Test
    fun `given a movie TMDB knows little about then everything is empty instead of failing`() {
        val credits = TitleCreditsParser.movie(
            response("""{"id":1,"title":"Obscure","tagline":"  ","budget":0,"revenue":0,"credits":{"cast":[],"crew":[]},
                "release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"","type":3}]}]}}""")
        )

        assertEquals(TitleCredits(), credits)
        assertEquals(TitleCredits(), TitleCreditsParser.movie(response("""{"id":1}""")), "sub-resources missing entirely")
    }

    @Test
    fun `given a US release without a theatrical rating then any rated US release counts`() {
        val credits = TitleCreditsParser.movie(
            response("""{"release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"","type":3},{"certification":"NR","type":5}]}]}}""")
        )
        assertEquals("NR", credits.certification)
    }

    @Test
    fun `given a writer credited only as Writer then the job is kept for the API`() {
        val credits = TitleCreditsParser.movie(
            response("""{"credits":{"crew":[
                {"id":1,"name":"Jay Chandrasekhar","job":"Writer","department":"Writing"},
                {"id":2,"name":"Kevin Heffernan","job":"Writer","department":"Writing"},
                {"id":3,"name":"Ghost","job":"Characters","department":"Writing"},
                {"id":4,"name":"Steve Lemme","job":"Story","department":"Writing"}]}}""")
        )
        assertEquals(listOf("Jay Chandrasekhar", "Kevin Heffernan", "Steve Lemme", "Ghost"), credits.writers.map { it.name })
        assertEquals(listOf("Writer"), credits.writers[0].jobs)
    }

    @Test
    fun `given a show then reads creators, cast with their main character and episodes, network, years and rating`() {
        val credits = TitleCreditsParser.tv(
            response(
                """
                {"id":1396,"name":"Breaking Bad","tagline":"Change the equation.","first_air_date":"2008-01-20",
                 "last_air_date":"2013-09-29","number_of_episodes":62,"status":"Ended",
                 "created_by":[{"id":66633,"name":"Vince Gilligan"}],
                 "networks":[{"id":174,"name":"AMC"}],
                 "aggregate_credits":{"cast":[
                   {"id":17419,"name":"Bryan Cranston","profile_path":"/7J.jpg","total_episode_count":62,
                    "roles":[{"character":"Walter White","episode_count":62}]},
                   {"id":59410,"name":"Bob Odenkirk","profile_path":null,"total_episode_count":43,
                    "roles":[{"character":"Saul Goodman","episode_count":40},{"character":"Jimmy McGill","episode_count":3},{"character":"","episode_count":50}]},
                   {"id":1,"name":"No Roles","roles":[]}
                 ]},
                 "content_ratings":{"results":[{"iso_3166_1":"DE","rating":"16"},{"iso_3166_1":"US","rating":"TV-MA"}]}}
                """.trimIndent()
            )
        )

        assertEquals("Change the equation.", credits.tagline)
        assertEquals("TV-MA", credits.certification)
        assertEquals(listOf(CreditPerson(66633, "Vince Gilligan")), credits.createdBy)
        assertEquals(listOf("AMC"), credits.networks)
        assertEquals(2008, credits.firstAirYear)
        assertEquals(2013, credits.lastAirYear)
        assertEquals(62, credits.numberOfEpisodes)
        assertEquals("Ended", credits.status)
        assertEquals(CastMember(17419, "Bryan Cranston", "Walter White", "/7J.jpg", 62), credits.cast[0])
        assertEquals(CastMember(59410, "Bob Odenkirk", "Saul Goodman", null, 43), credits.cast[1], "the role with the most episodes")
        assertEquals(CastMember(1, "No Roles"), credits.cast[2])
        assertTrue(credits.directors.isEmpty() && credits.writers.isEmpty() && credits.studios.isEmpty())
    }

    @Test
    fun `given a show without US rating, creators or dates then they are left out`() {
        val credits = TitleCreditsParser.tv(
            response("""{"first_air_date":"","number_of_episodes":0,"content_ratings":{"results":[{"iso_3166_1":"GB","rating":"15"}]}}""")
        )
        assertEquals(TitleCredits(), credits)
    }

    @Test
    fun `given unsafe image paths then they are dropped`() {
        val credits = TitleCreditsParser.movie(
            response("""{"credits":{"cast":[{"id":1,"name":"A","profile_path":"https://evil.example/x.jpg"},{"id":2,"name":"B","profile_path":"/../x.jpg"}]}}""")
        )
        assertNull(credits.cast[0].profilePath)
        assertNull(credits.cast[1].profilePath)
    }

    @Test
    fun `credits survive the round trip through the cache column, and unreadable JSON counts as missing`() {
        val credits = TitleCreditsParser.movie(response(godfather))

        val json = TitleCredits.toJson(credits)
        assertEquals(credits, TitleCredits.fromJson(json))
        assertFalse(json.contains("\"networks\""), "empty values are left out to keep rows small: $json")

        assertEquals(TitleCredits(), TitleCredits.fromJson("{}"))
        assertNull(TitleCredits.fromJson(null))
        assertNull(TitleCredits.fromJson("not json"))
    }
}
