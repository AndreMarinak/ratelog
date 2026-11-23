package org.ratelog.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import java.io.IOException

class LogSafeTest {

    private val url = "https://api.themoviedb.org/3/movie/603?api_key=SECRET123&language=en"

    @Test
    fun `api key query values are masked, other parameters kept`() {
        assertEquals(
            "I/O error on GET request for \"https://api.themoviedb.org/3/movie/603?api_key=***&language=en\": timeout",
            LogSafe.redact("I/O error on GET request for \"$url\": timeout"),
        )
        assertEquals("https://api.mdblist.com/tmdb/movie/603?apikey=***", LogSafe.redact("https://api.mdblist.com/tmdb/movie/603?apikey=abc"))
        assertEquals("no secrets here", LogSafe.redact("no secrets here"))
    }

    @Test
    fun `a sanitized copy keeps class names and stack traces of the whole chain but no secret`() {
        val original = IllegalStateException("wrapped", ResourceAccessException("I/O error on GET request for \"$url\"", IOException("reset")))

        val copy = LogSafe.sanitized(original)

        val chain = generateSequence(copy) { it.cause }.toList()
        assertEquals(3, chain.size)
        assertTrue(chain[0].message!!.startsWith("java.lang.IllegalStateException: wrapped"))
        assertTrue(chain[1].message!!.startsWith("org.springframework.web.client.ResourceAccessException"))
        assertTrue(chain[2].message!!.startsWith("java.io.IOException: reset"))
        assertTrue(chain.none { it.message!!.contains("SECRET123") })
        assertTrue(chain[0].stackTrace.contentEquals(original.stackTrace))
    }

    @Test
    fun `the HTTP status is added for response errors only`() {
        assertEquals(" status=404", LogSafe.statusSuffix(HttpClientErrorException(HttpStatus.NOT_FOUND)))
        assertEquals("", LogSafe.statusSuffix(ResourceAccessException("x")))
    }

    @Test
    fun `a self-referencing cause does not loop`() {
        val e = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        val copy = LogSafe.sanitized(e)

        assertFalse(copy.cause != null)
    }
}
