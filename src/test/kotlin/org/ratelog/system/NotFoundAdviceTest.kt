package org.ratelog.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.servlet.resource.NoResourceFoundException

class NotFoundAdviceTest {

    private val advice = NotFoundAdvice()

    private fun notFound(path: String): Pair<org.springframework.web.servlet.ModelAndView, MockHttpServletResponse> {
        val request = MockHttpServletRequest("GET", path)
        val response = MockHttpServletResponse()
        val mav = advice.notFound(NoResourceFoundException(HttpMethod.GET, path.removePrefix("/")), request, response)
        return mav to response
    }

    @Test
    fun `given an unknown page then renders the not found page with a 404`() {
        val (mav, _) = notFound("/tvshow/1396")
        assertEquals("not-found", mav.viewName)
        assertEquals(HttpStatus.NOT_FOUND, mav.status)
    }

    @Test
    fun `given an unknown api path then answers JSON in the API error format`() {
        val (mav, response) = notFound("/api/v1/nope")
        assertTrue(mav.isEmpty)
        assertEquals(404, response.status)
        assertTrue(response.contentType!!.startsWith("application/json"))
        assertEquals("""{"error":"not_found","message":"Not found"}""", response.contentAsString)
    }

    @Test
    fun `given a missing file then answers an empty 404 without rendering a page`() {
        val (mav, response) = notFound("/img/missing.png")
        assertTrue(mav.isEmpty)
        assertNull(mav.viewName)
        assertEquals(404, response.status)
        assertEquals("", response.contentAsString)
    }

    @Test
    fun `given a wrong method then answers 405 with the allowed methods`() {
        val request = MockHttpServletRequest("GET", "/watchlist/toggle")
        val response = MockHttpServletResponse()
        val mav = advice.methodNotAllowed(HttpRequestMethodNotSupportedException("GET", listOf("POST")), request, response)
        assertEquals("not-found", mav.viewName)
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, mav.status)
        assertEquals("POST", response.getHeader("Allow"))
    }
}
