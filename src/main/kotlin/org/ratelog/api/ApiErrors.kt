package org.ratelog.api

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class ApiErrorBody(val error: String, val message: String)

class ApiException(val status: HttpStatus, val code: String, override val message: String) : RuntimeException(message)

fun notFound(message: String) = ApiException(HttpStatus.NOT_FOUND, "not_found", message)
fun badRequest(code: String, message: String) = ApiException(HttpStatus.BAD_REQUEST, code, message)
fun forbidden(message: String) = ApiException(HttpStatus.FORBIDDEN, "forbidden", message)

/** JSON errors for the /api/v1 controllers only; takes precedence over the app's GlobalExceptionHandler. */
@RestControllerAdvice(basePackages = ["org.ratelog.api"])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {
    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException) = ResponseEntity.status(e.status).body(ApiErrorBody(e.code, e.message))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException) =
        ResponseEntity.badRequest().body(ApiErrorBody("invalid_json", e.mostSpecificCause.message ?: "Malformed JSON body"))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class)
    fun handleParams(e: Exception) = ResponseEntity.badRequest().body(ApiErrorBody("invalid_parameter", e.message ?: "Invalid parameter"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethod(e: HttpRequestMethodNotSupportedException) =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiErrorBody("method_not_allowed", e.message ?: "Method not allowed"))

    /** TMDB failures. Never log the message: TMDB URLs carry api_key and I/O errors echo the URL. */
    @ExceptionHandler(RestClientException::class)
    fun handleTmdb(e: RestClientException): ResponseEntity<ApiErrorBody> {
        val status = (e as? RestClientResponseException)?.statusCode?.value()
        logger.warn("Upstream HTTP call failed: {} status={}", e.javaClass.simpleName, status ?: "n/a")
        return if (status == 401) {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiErrorBody("tmdb_not_configured", "TMDB rejected the API key (Admin > Configuration)"))
        } else {
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiErrorBody("tmdb_error", "TMDB lookup failed"))
        }
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception): ResponseEntity<ApiErrorBody> {
        // Fork: redacted copy, since upstream URLs in messages can carry API keys.
        logger.error("Unhandled API error: {}", e.javaClass.simpleName, org.ratelog.security.LogSafe.sanitized(e))
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorBody("internal_error", "Internal server error"))
    }
}
