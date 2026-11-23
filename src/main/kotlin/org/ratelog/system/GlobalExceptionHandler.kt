package org.ratelog.system

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.security.LogSafe
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import java.util.NoSuchElementException

data class ApiError(val message: String)

@ControllerAdvice
class GlobalExceptionHandler {
    // Fork: failures used to leave no trace. Messages are redacted (LogSafe): upstream URLs can carry API keys.
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException, request: HttpServletRequest): ResponseEntity<ApiError> {
        logger.warn("{} on {} {}", e.javaClass.simpleName, request.method, request.requestURI)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError("Resource not found"))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<ApiError> {
        logger.warn("{} on {} {}", e.javaClass.simpleName, request.method, request.requestURI)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError("Invalid request"))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val errorMessage = e.bindingResult.fieldErrors
            .firstOrNull()
            ?.defaultMessage
            ?: "Validation error"
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError(errorMessage))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        if (e is ErrorResponse && e.statusCode.is4xxClientError) {
            // A malformed request (missing parameter, wrong content type): one line, no stack trace.
            logger.warn("{} on {} {}", e.javaClass.simpleName, request.method, request.requestURI)
        } else {
            logger.error("Unhandled {} on {} {}", e.javaClass.simpleName, request.method, request.requestURI, LogSafe.sanitized(e))
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError("Internal server error"))
    }
}
