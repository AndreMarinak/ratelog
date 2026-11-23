package org.ratelog.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.user.AppUserDetails
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.HtmlUtils
import java.util.Locale

/**
 * General request rate limit ("too many requests"), on top of the login throttle. Keys: the API token for `/api/v1`,
 * the signed-in account for browser sessions, else the client IP. Writes (POST, PUT, PATCH, DELETE) and reads have
 * separate buckets. Static files and `/api/health` are exempt.
 *
 * Over the limit: `429` with `Retry-After`, as the API's JSON error, the in-place scripts' JSON (`{ok:false, message}`,
 * shown as a toast), or a short page. Not a Spring bean on purpose (it belongs to the two security chains only).
 */
class RateLimitFilter(
    private val limiter: RequestRateLimiter,
    private val clientIp: ClientIpResolver,
    private val messages: MessageSource,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = SessionRevalidationFilter.pathOf(request)
        return path == "/api/health" || SessionRevalidationFilter.isStaticAsset(path)
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        when (val decision = limiter.tryAcquire(keyFor(request), isWrite(request))) {
            RequestRateLimiter.Decision.Allowed -> chain.doFilter(request, response)
            is RequestRateLimiter.Decision.Limited -> reject(request, response, decision.retryAfterSeconds)
        }
    }

    private fun keyFor(request: HttpServletRequest): String {
        (request.getAttribute(API_TOKEN_ID_ATTRIBUTE) as? Long)?.let { return "token:$it" }
        (SecurityContextHolder.getContext().authentication?.principal as? AppUserDetails)?.let { return "user:${it.id.value}" }
        return "ip:" + (clientIp.resolve(request) ?: "unknown")
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse, retryAfter: Long) {
        val locale = request.locale ?: Locale.ENGLISH
        val message = messages.getMessage("security.rate.limited", arrayOf(retryAfter), "Too many requests.", locale)!!
        response.status = TOO_MANY_REQUESTS
        response.setHeader("Retry-After", retryAfter.toString())
        response.characterEncoding = "UTF-8"
        val path = SessionRevalidationFilter.pathOf(request)
        when {
            path.startsWith("/api/") -> {
                response.contentType = "application/json"
                response.writer.write("""{"error":"rate_limited","message":"${json(message)}"}""")
            }
            wantsJson(request) -> {
                response.contentType = "application/json"
                response.writer.write("""{"ok":false,"message":"${json(message)}"}""")
            }
            else -> {
                response.contentType = "text/html"
                val text = HtmlUtils.htmlEscape(message)
                response.writer.write(
                    """<!DOCTYPE html><html lang="${HtmlUtils.htmlEscape(locale.language)}"><head><meta charset="UTF-8">""" +
                        """<meta name="viewport" content="width=device-width, initial-scale=1.0"><title>$text</title>""" +
                        """<link rel="stylesheet" href="/styles.css"></head><body><div class="page">""" +
                        """<div class="feedback error">$text</div><p><a class="link" href="/">Ratelog</a></p></div></body></html>""",
                )
            }
        }
    }

    private fun wantsJson(request: HttpServletRequest): Boolean =
        request.getHeader("X-Requested-With") == "fetch" || request.getHeader("Accept").orEmpty().contains("application/json")

    private fun isWrite(request: HttpServletRequest): Boolean = request.method.uppercase() in WRITE_METHODS

    private fun json(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        const val TOO_MANY_REQUESTS = 429
        /** Set by the API token filter: the token's id, so each token gets its own bucket. */
        const val API_TOKEN_ID_ATTRIBUTE = "org.ratelog.security.apiTokenId"
        private val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}

/** The shared limiter, configured by `ratelog.rate-limit.*` in application.yml. */
@Configuration
class RateLimitConfig {
    @Bean
    fun requestRateLimiter(
        @Value("\${ratelog.rate-limit.reads-per-minute:600}") readsPerMinute: Int,
        @Value("\${ratelog.rate-limit.read-burst:100}") readBurst: Int,
        @Value("\${ratelog.rate-limit.writes-per-minute:60}") writesPerMinute: Int,
        @Value("\${ratelog.rate-limit.write-burst:30}") writeBurst: Int,
    ): RequestRateLimiter = RequestRateLimiter(
        reads = RequestRateLimiter.Limit(readsPerMinute, readBurst),
        writes = RequestRateLimiter.Limit(writesPerMinute, writeBurst),
    )
}
