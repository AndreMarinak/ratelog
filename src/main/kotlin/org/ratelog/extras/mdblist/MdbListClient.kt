package org.ratelog.extras.mdblist

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.extras.ExternalScores
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant

sealed interface MdbListError {
    /** `MDBLIST_API_KEY` is not set: scores are simply not shown. */
    data object NotConfigured : MdbListError
    data class RateLimited(val until: Instant) : MdbListError
    data object NotFound : MdbListError
    data class Failed(val reason: String) : MdbListError
}

/**
 * IMDb / Rotten Tomatoes / Metacritic / Letterboxd scores by TMDB id from MDBList
 * (`GET https://api.mdblist.com/tmdb/{movie|show}/{id}?apikey=...`). The key comes from `MDBLIST_API_KEY`.
 */
@Component
class MdbListClient(
    @Value("\${MDBLIST_API_KEY:}") apiKey: String,
    @Value("\${ratelog.mdblist.base-url:https://api.mdblist.com}") baseUrl: String = "https://api.mdblist.com",
    clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(MdbListClient::class.java)
    private val apiKey = apiKey.trim()
    private val backoff = RateLimitBackoff(clock)

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build()
            ).apply {
                setReadTimeout(Duration.ofSeconds(5))
            }
        )
        .build()

    fun isConfigured(): Boolean = apiKey.isNotEmpty()

    /** True while a 429 (or a rejected key) has paused all MDBList calls. */
    fun isPaused(): Boolean = backoff.isBlocked()

    fun fetch(type: MediaType, tmdbId: TmdbId): Either<MdbListError, ExternalScores> {
        if (!isConfigured()) return MdbListError.NotConfigured.left()
        backoff.blockedUntil()?.let { return MdbListError.RateLimited(it).left() }

        val mdbType = when (type) {
            MediaType.movie -> "movie"
            MediaType.tvshow -> "show"
        }
        return try {
            restClient.get()
                .uri { builder ->
                    builder.path("/tmdb/{type}/{id}")
                        .queryParam("apikey", apiKey)
                        .build(mdbType, tmdbId.value)
                }
                .exchange { _, response ->
                    val status = response.statusCode.value()
                    when {
                        status == 429 -> {
                            val until = backoff.block(
                                retryAfter = response.headers.getFirst("Retry-After"),
                                rateLimitReset = response.headers.getFirst("X-RateLimit-Reset"),
                            )
                            logger.warn("MDBList rate limit hit; pausing MDBList calls until {}", until)
                            MdbListError.RateLimited(until).left()
                        }
                        status == 401 || status == 403 -> {
                            val until = backoff.block(null, null, fallback = Duration.ofHours(1))
                            logger.warn("MDBList rejected the API key (HTTP {}); pausing MDBList calls until {}", status, until)
                            MdbListError.Failed("HTTP $status").left()
                        }
                        status == 404 -> MdbListError.NotFound.left()
                        status in 200..299 -> {
                            val body = response.body.use { String(it.readAllBytes(), Charsets.UTF_8) }
                            MdbListParser.parse(body)?.right() ?: MdbListError.NotFound.left()
                        }
                        else -> MdbListError.Failed("HTTP $status").left()
                    }
                }
        } catch (e: Exception) {
            // Exception messages can contain the request URL, which carries the API key: log the type only.
            logger.warn("MDBList lookup failed for {} {}: {}", mdbType, tmdbId.value, e.javaClass.simpleName)
            MdbListError.Failed(e.javaClass.simpleName).left()
        }
    }
}
