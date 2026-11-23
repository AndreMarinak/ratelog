package org.ratelog.security

import org.springframework.web.client.RestClientResponseException

/**
 * Helpers for logging exceptions without secrets. Upstream HTTP clients put API keys in query strings
 * (TMDB `api_key=`, MDBList `apikey=`), and Spring's I/O errors repeat the full request URL in their message.
 */
object LogSafe {
    private val SECRET_PARAM = Regex("""(?i)([?&;](?:api[_-]?key|access[_-]?token|token|key|password)=)[^&\s"'<>]*""")
    private const val MAX_DEPTH = 10

    /** The message with every secret-looking query parameter value replaced by `***`. */
    fun redact(message: String?): String? = message?.replace(SECRET_PARAM, "$1***")

    /** " status=404" for an HTTP error response, else "". */
    fun statusSuffix(e: Throwable): String =
        (e as? RestClientResponseException)?.statusCode?.value()?.let { " status=$it" } ?: ""

    /**
     * A copy of [e] (and its causes) that keeps the stack traces but has redacted messages, so it can be passed to a
     * logger as the throwable argument. Each copy's message starts with the original class name.
     */
    fun sanitized(e: Throwable): Throwable = copy(e, 0)

    private fun copy(e: Throwable, depth: Int): Throwable {
        val cause = e.cause?.takeIf { it !== e && depth < MAX_DEPTH }?.let { copy(it, depth + 1) }
        val message = redact(e.message)?.let { "${e.javaClass.name}: $it" } ?: e.javaClass.name
        return RedactedException(message, cause).also { it.stackTrace = e.stackTrace }
    }

    class RedactedException(message: String, cause: Throwable?) : RuntimeException(message, cause)
}
