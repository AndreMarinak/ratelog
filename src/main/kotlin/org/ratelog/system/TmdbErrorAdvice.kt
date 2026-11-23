package org.ratelog.system

import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.servlet.ModelAndView

/**
 * TMDB failures (bad/expired key, TMDB down, network) surface as RestClientException from TmdbClient.
 * Upstream turns those into a bare JSON 500 on HTML pages; show a readable page instead.
 * Ordered after the fork's API handler (HIGHEST_PRECEDENCE, JSON for /api/v1) and before GlobalExceptionHandler.
 */
@ControllerAdvice
@Order(0)
class TmdbErrorAdvice {
    private val logger = LoggerFactory.getLogger(TmdbErrorAdvice::class.java)

    @ExceptionHandler(RestClientException::class)
    fun handle(e: RestClientException): ModelAndView {
        val badKey = e is HttpClientErrorException.Unauthorized
        // Never log e.message: TMDB requests carry api_key in the URL, and I/O errors echo the URL.
        val status = (e as? RestClientResponseException)?.statusCode?.value()
        logger.warn("TMDB request failed: {} status={}{}", e.javaClass.simpleName, status ?: "n/a", if (badKey) " (API key rejected)" else "")
        return ModelAndView("tmdb-error", HttpStatus.BAD_GATEWAY).apply {
            addObject("badKey", badKey)
        }
    }
}
