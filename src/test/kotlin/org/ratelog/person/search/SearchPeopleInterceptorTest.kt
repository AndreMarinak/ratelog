package org.ratelog.person.search

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.person.PersonLookupError
import org.ratelog.person.PersonSummary
import org.ratelog.person.TmdbPersonClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.ModelAndView
import java.util.concurrent.Executor

class SearchPeopleInterceptorTest {
    private val client: TmdbPersonClient = mock()
    private val direct = Executor { it.run() }
    private val interceptor = SearchPeopleInterceptor(client, { null }, direct)
    private val hanks = PersonSummary(31, "Tom Hanks", "/hanks.jpg", "Acting", listOf("Forrest Gump"), 10.0)

    private fun request(vararg params: Pair<String, String>) = MockHttpServletRequest("GET", "/").apply {
        params.forEach { (k, v) -> addParameter(k, v) }
    }

    private fun run(request: MockHttpServletRequest, view: String = "search"): ModelAndView {
        val response = MockHttpServletResponse()
        val mav = ModelAndView(view)
        interceptor.preHandle(request, response, Any())
        interceptor.postHandle(request, response, Any(), mav)
        return mav
    }

    @Test
    fun `first page of Any results gets the People row`() {
        whenever(client.search("tom hanks", Lang.en)).thenReturn(listOf(hanks).right())

        val people = run(request("q" to " tom hanks ")).model["searchPeople"] as List<*>

        assertEquals("/person/31", (people.single() as SearchPersonView).href)
        assertEquals("person.dept.acting", (people.single() as SearchPersonView).knownForKey)
    }

    @Test
    fun `Movies or TV results, later pages and the home page get no People row`() {
        listOf(
            request("q" to "tom hanks", "mediaType" to "movie"),
            request("q" to "tom hanks", "page" to "2"),
            request(),
            request("q" to "  "),
        ).forEach { assertNull(run(it).model["searchPeople"]) }
        verify(client, never()).search(any(), any(), any())
        assertEquals("tom hanks", SearchPeopleInterceptor.wanted(request("q" to "tom hanks", "page" to "1", "mediaType" to "")))
    }

    @Test
    fun `no people or a TMDB failure only loses the row`() {
        whenever(client.search("nobody", Lang.en)).thenReturn(emptyList<PersonSummary>().right())
        whenever(client.search("broken", Lang.en)).thenReturn(PersonLookupError.Failed("x").left())

        assertFalse(run(request("q" to "nobody")).model.containsKey("searchPeople"))
        assertFalse(run(request("q" to "broken")).model.containsKey("searchPeople"))
    }

    @Test
    fun `another view (an error redirect) does not wait for the search`() {
        whenever(client.search("tom hanks", Lang.en)).thenReturn(listOf(hanks).right())
        assertNull(run(request("q" to "tom hanks"), view = "redirect:/").model["searchPeople"])
    }
}
