package org.ratelog.system

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Unknown URLs, malformed ids in a path (`/movie/abc`) and wrong HTTP methods.
 * Upstream's GlobalExceptionHandler catches every Exception, so these came back as a JSON 500 "Internal server error".
 * Pages get a "Page not found" page, `/api/` paths a JSON error in the API's format, missing files an empty 404.
 * Ordered before GlobalExceptionHandler (like TmdbErrorAdvice); the API's own handler still wins inside its controllers.
 */
@ControllerAdvice
@Order(0)
class NotFoundAdvice {

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class, MethodArgumentTypeMismatchException::class)
    fun notFound(e: Exception, request: HttpServletRequest, response: HttpServletResponse): ModelAndView =
        respond(HttpStatus.NOT_FOUND, "not_found", "Not found", request, response)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun methodNotAllowed(e: HttpRequestMethodNotSupportedException, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        e.supportedMethods?.let { response.setHeader("Allow", it.joinToString(", ")) }
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed", request, response)
    }

    private fun respond(status: HttpStatus, code: String, message: String, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        val path = request.requestURI.removePrefix(request.contextPath)
        return when {
            path.startsWith("/api/") -> {
                response.status = status.value()
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.characterEncoding = "UTF-8"
                // Fixed strings only: nothing from the request is echoed back.
                response.writer.write("""{"error":"$code","message":"$message"}""")
                ModelAndView()
            }
            looksLikeFile(path) -> {
                response.status = status.value()
                ModelAndView()
            }
            else -> ModelAndView("not-found", status)
        }
    }

    /** `/styles.css`, `/img/x.png`: a missing file needs no HTML page. */
    private fun looksLikeFile(path: String): Boolean = path.substringAfterLast('/').let { it.contains('.') && !it.endsWith('.') }
}
