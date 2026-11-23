package org.ratelog.browse.discover

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

class BrowseRedirectControllerTest {

    private val controller = BrowseRedirectController()

    private fun redirect(query: String?): Pair<HttpStatus, String?> {
        val request = MockHttpServletRequest("GET", "/browse").apply { queryString = query }
        val response = controller.browse(request)
        return HttpStatus.valueOf(response.statusCode.value()) to response.headers.getFirst(HttpHeaders.LOCATION)
    }

    @Test
    fun `given an old browse link then redirects permanently to discover with the query string unchanged`() {
        assertEquals(
            HttpStatus.MOVED_PERMANENTLY to "/discover?type=tv&genre=sci-fi-fantasy&decade=2010s&hideRated=1&page=2",
            redirect("type=tv&genre=sci-fi-fantasy&decade=2010s&hideRated=1&page=2"),
        )
        // Percent-encoding is passed through as sent.
        assertEquals("/discover?language=ko&q=%C3%A9t%C3%A9", redirect("language=ko&q=%C3%A9t%C3%A9").second)
    }

    @Test
    fun `given no query string then redirects to the bare discover page`() {
        assertEquals(HttpStatus.MOVED_PERMANENTLY to "/discover", redirect(null))
        assertEquals("/discover", redirect("").second)
    }

    @Test
    fun `given a query string with line breaks then drops it rather than echo it into a header`() {
        assertEquals("/discover", redirect("type=tv\r\nSet-Cookie: x=1").second)
    }
}
