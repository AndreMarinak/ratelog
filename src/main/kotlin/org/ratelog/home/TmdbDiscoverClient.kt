package org.ratelog.home

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** The TMDB lists the home dashboard shows. `regional` feeds take the user's watch region. */
enum class DiscoverFeed(
    val path: String,
    val mediaType: MediaType,
    val regional: Boolean,
    /** Extra TMDB query parameters (for `/discover` feeds). */
    val params: Map<String, String> = emptyMap(),
    /** > 1: the feed walks pages 1..n, one per ISO week, so the row changes from week to week. */
    val rotatePages: Int = 1,
) {
    TRENDING_MOVIES("/trending/movie/week", MediaType.movie, regional = false),
    TRENDING_TV("/trending/tv/week", MediaType.tvshow, regional = false),
    NOW_PLAYING("/movie/now_playing", MediaType.movie, regional = true),
    UPCOMING("/movie/upcoming", MediaType.movie, regional = true),
    POPULAR_TV("/tv/popular", MediaType.tvshow, regional = false),

    /** Fork: acclaimed films from before 2000 (no documentaries), rotating weekly through the top ~60. */
    CLASSICS(
        "/discover/movie", MediaType.movie, regional = false,
        params = mapOf(
            "sort_by" to "vote_average.desc",
            "vote_count.gte" to "4000",
            "primary_release_date.lte" to "1999-12-31",
            "without_genres" to "99",
        ),
        rotatePages = 3,
    ),
}

data class DiscoverTitle(
    val tmdbId: Int,
    val mediaType: MediaType,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val releaseDate: LocalDate?,
)

sealed interface DiscoverError {
    /** No TMDB API key. */
    data object NotConfigured : DiscoverError

    /** TMDB rejected the request, timed out or sent something unreadable. */
    data object Unavailable : DiscoverError
}

/**
 * TMDB "discovery" lists (trending, now playing, upcoming, popular) for the home dashboard.
 * Kept apart from upstream's [org.ratelog.tmdb.TmdbClient] and cached in memory: results for 1 hour per
 * feed + language (+ region for regional feeds), failures for a couple of minutes so a TMDB outage
 * costs one slow request, not one per page view. A network failure also short-circuits the other feeds
 * for the same window.
 */
@Component
class TmdbDiscoverClient(
    private val generalConfigRepository: GeneralConfigRepository,
    @Value("\${ratelog.tmdb.base-url}") baseUrl: String = "https://api.themoviedb.org/3",
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(TmdbDiscoverClient::class.java)

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            ).apply { setReadTimeout(Duration.ofSeconds(6)) }
        )
        .build()

    private data class CacheKey(val feed: DiscoverFeed, val lang: Lang, val region: WatchRegion?)

    private class CacheEntry(val result: Either<DiscoverError, List<DiscoverTitle>>, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    @Volatile
    private var unreachableUntil: Instant = Instant.MIN

    /** Page 1 of the feed (TMDB sends 20). Titles without an id or name, and adult titles, are left out. */
    fun fetch(feed: DiscoverFeed, lang: Lang, region: WatchRegion): Either<DiscoverError, List<DiscoverTitle>> {
        val key = CacheKey(feed, lang, region.takeIf { feed.regional })
        val now = clock.instant()
        cache[key]?.takeIf { it.expiresAt.isAfter(now) }?.let { return it.result }

        val apiKey = generalConfigRepository.findByKey(ConfigKey.TMDB_API_KEY)?.value?.trim()
        if (apiKey.isNullOrEmpty()) return DiscoverError.NotConfigured.left()
        if (now.isBefore(unreachableUntil)) return DiscoverError.Unavailable.left()

        val result = load(feed, lang, key.region, apiKey, now)
        cache[key] = CacheEntry(result, now.plus(if (result.isRight()) SUCCESS_TTL else FAILURE_TTL))
        return result
    }

    private fun load(
        feed: DiscoverFeed,
        lang: Lang,
        region: WatchRegion?,
        apiKey: String,
        now: Instant,
    ): Either<DiscoverError, List<DiscoverTitle>> = try {
        val page = restClient.get()
            .uri { builder ->
                builder.path(feed.path)
                    .queryParam("api_key", apiKey)
                    .queryParam("language", lang.name)
                    .queryParam("page", pageFor(feed, now))
                    .also { if (region != null) it.queryParam("region", region.value) }
                    .also { feed.params.forEach { (name, value) -> it.queryParam(name, value) } }
                    .build()
            }
            .retrieve()
            .body(DiscoverPageDto::class.java)
        page?.results.orEmpty().mapNotNull { it.toTitle(feed.mediaType) }.right()
    } catch (e: RuntimeException) {
        // Never log e.message: the request URL carries api_key.
        if (e is ResourceAccessException) unreachableUntil = now.plus(FAILURE_TTL)
        val status = (e as? RestClientResponseException)?.statusCode?.value()
        logger.warn("TMDB discover feed {} failed: {} status={}", feed, e.javaClass.simpleName, status ?: "n/a")
        DiscoverError.Unavailable.left()
    }

    companion object {
        /** Page 1, or for rotating feeds the ISO week's page in 1..[DiscoverFeed.rotatePages]. */
        fun pageFor(feed: DiscoverFeed, now: Instant): Int {
            if (feed.rotatePages <= 1) return 1
            val week = now.atZone(java.time.ZoneOffset.UTC).get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            return week % feed.rotatePages + 1
        }

        val SUCCESS_TTL: Duration = Duration.ofHours(1)
        val FAILURE_TTL: Duration = Duration.ofMinutes(2)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DiscoverPageDto(
    @JsonProperty("results") val results: List<DiscoverResultDto>? = null,
)

/** One movie or show from a TMDB list endpoint. Movies use title/release_date, shows name/first_air_date. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DiscoverResultDto(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("adult") val adult: Boolean? = null,
) {
    fun toTitle(mediaType: MediaType): DiscoverTitle? {
        val tmdbId = id?.takeIf { it > 0 } ?: return null
        if (adult == true) return null
        val name = listOf(title, name, originalTitle, originalName).firstOrNull { !it.isNullOrBlank() }?.trim()
            ?: return null
        val date = parseDate(if (mediaType == MediaType.movie) releaseDate ?: firstAirDate else firstAirDate ?: releaseDate)
        return DiscoverTitle(
            tmdbId = tmdbId,
            mediaType = mediaType,
            title = name,
            year = date?.year,
            posterPath = posterPath?.takeIf { it.isNotBlank() },
            releaseDate = date,
        )
    }

    private fun parseDate(value: String?): LocalDate? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
}
