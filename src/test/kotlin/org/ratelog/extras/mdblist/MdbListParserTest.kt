package org.ratelog.extras.mdblist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.extras.ExternalScores

class MdbListParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/mdblist/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `given a full movie response when parsing then maps every tracked source`() {
        // When
        val scores = MdbListParser.parse(fixture("movie-603.json"))

        // Then
        assertEquals(
            ExternalScores(
                imdbId = "tt0133093",
                imdbRating = 8.7,
                imdbVotes = 2_134_567,
                rtCritics = 83,
                rtAudience = 85,
                metacritic = 73,
                letterboxd = 4.2,
            ),
            scores,
        )
    }

    @Test
    fun `given numbers as strings, zeros, N-A and unknown sources when parsing then reads them leniently`() {
        // When
        val scores = MdbListParser.parse(fixture("show-sparse.json"))!!

        // Then
        assertEquals("tt9999999", scores.imdbId) // only present under ids.imdb
        assertEquals(7.4, scores.imdbRating)
        assertEquals(1_234L, scores.imdbVotes)
        assertNull(scores.rtCritics, "a 0 with no votes is 'not rated yet'")
        assertEquals(91, scores.rtAudience, "tomatoesaudience is accepted as RT audience")
        assertNull(scores.metacritic, "N/A is no score")
        assertEquals(3.5, scores.letterboxd, "falls back to the 0-100 score when value is missing")
    }

    @Test
    fun `given an empty ratings array when parsing then has no scores`() {
        // When
        val scores = MdbListParser.parse("""{"title":"X","imdb_id":"tt0000001","ratings":[]}""")!!

        // Then
        assertFalse(scores.hasAnyScore())
        assertEquals("tt0000001", scores.imdbId)
    }

    @Test
    fun `given no ratings field at all when parsing then has no scores`() {
        assertFalse(MdbListParser.parse("""{"title":"X"}""")!!.hasAnyScore())
    }

    @Test
    fun `given an error body or invalid json when parsing then returns null`() {
        assertNull(MdbListParser.parse("""{"error":"Movie not found"}"""))
        assertNull(MdbListParser.parse("<html>Bad gateway</html>"))
        assertNull(MdbListParser.parse("[]"))
        assertNull(MdbListParser.parse(""))
    }

    @Test
    fun `given out of range values when parsing then drops them`() {
        // When
        val scores = MdbListParser.parse(
            """
            {"ratings":[
              {"source":"imdb","value":87,"score":87,"votes":10},
              {"source":"tomatoes","value":140,"votes":5},
              {"source":"metacritic","value":-1},
              {"source":"letterboxd","value":0,"score":0}
            ]}
            """.trimIndent()
        )!!

        // Then
        assertNull(scores.imdbRating)
        assertNull(scores.imdbVotes)
        assertNull(scores.rtCritics)
        assertNull(scores.metacritic)
        assertNull(scores.letterboxd)
        assertFalse(scores.hasAnyScore())
    }

    @Test
    fun `given a real zero percent with votes when parsing then keeps it`() {
        val scores = MdbListParser.parse("""{"ratings":[{"source":"tomatoes","value":0,"score":0,"votes":42}]}""")!!
        assertEquals(0, scores.rtCritics)
        assertTrue(scores.hasAnyScore())
    }

    @Test
    fun `given source names in another case and a malformed imdb id when parsing then normalizes`() {
        val scores = MdbListParser.parse("""{"imdb_id":"javascript:alert(1)","ratings":[{"source":"IMDB","value":6.04,"votes":12}]}""")
        assertNotNull(scores)
        assertEquals(6.0, scores!!.imdbRating)
        assertNull(scores.imdbId)
    }
}
