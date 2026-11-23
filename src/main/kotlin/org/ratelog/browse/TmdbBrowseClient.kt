package org.ratelog.browse

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfigRepository
import org.ratelog.tmdb.TmdbRateLimiter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

sealed interface TmdbBrowseError {
    data object ApiKeyMissing : TmdbBrowseError
    data object ApiKeyRejected : TmdbBrowseError
    data class Failed(val reason: String) : TmdbBrowseError
}

/**
 * TMDB discover / trending pages and the per-region streaming service list for the browse page.
 * Kept apart from [org.ratelog.tmdb.TmdbClient]; same API key lookup. Results are cached in memory
 * (pages for [pageCacheMinutes], provider lists for a day) and failures are returned, never thrown.
 */
@Component
class TmdbBrowseClient(
    private val generalConfigRepository: GeneralConfigRepository,
    @Value("\${ratelog.tmdb.base-url}") baseUrl: String = "https://api.themoviedb.org/3",
    @Value("\${ratelog.browse.cache-minutes:45}") pageCacheMinutes: Long = 45,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(TmdbBrowseClient::class.java)
    private val rateLimiter = TmdbRateLimiter(maxRequestsPerSecond = 40)

    private val pageCache = TtlCache<String, BrowseTitlePage>(Duration.ofMinutes(pageCacheMinutes), maxEntries = 500, clock)
    private val providerCache = TtlCache<String, List<BrowseProvider>>(PROVIDER_TTL, maxEntries = 64, clock)
    /** A failed provider lookup isn't retried for a few minutes, so pages that show the list don't wait on TMDB each time. */
    private val providerFailures = ConcurrentHashMap<String, Instant>()

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build()
            ).apply {
                setReadTimeout(Duration.ofSeconds(6))
            }
        )
        .build()

    fun page(request: TmdbListRequest): Either<TmdbBrowseError, BrowseTitlePage> {
        val apiKey = apiKey() ?: return TmdbBrowseError.ApiKeyMissing.left()
        return pageCache.getOrLoad(request.cacheKey) {
            call(request.path, request.params, apiKey, TmdbBrowsePageResponse::class.java).map { it.toPage(request.type) }
        }
    }

    /** Streaming services in [region], best display priority first. */
    fun providers(type: MediaType, region: WatchRegion, lang: Lang): Either<TmdbBrowseError, List<BrowseProvider>> {
        val apiKey = apiKey() ?: return TmdbBrowseError.ApiKeyMissing.left()
        val key = "${type.name}|${region.value}|${lang.name}"
        providerCache.get(key)?.let { return it.right() }
        providerFailures[key]?.let { until ->
            if (clock.instant().isBefore(until)) return TmdbBrowseError.Failed("recently failed").left()
            providerFailures.remove(key)
        }
        val path = if (type == MediaType.movie) "/watch/providers/movie" else "/watch/providers/tv"
        val params = sortedMapOf("language" to lang.name, "watch_region" to region.value)
        return call(path, params, apiKey, TmdbProviderListResponse::class.java)
            .map { parseProviders(it, region) }
            .onRight { providerCache.put(key, it) }
            .onLeft { providerFailures[key] = clock.instant().plus(PROVIDER_FAILURE_PAUSE) }
    }

    private fun <T : Any> call(path: String, params: Map<String, String>, apiKey: String, type: Class<T>): Either<TmdbBrowseError, T> =
        try {
            rateLimiter.acquire()
            val body = restClient.get()
                .uri { builder ->
                    builder.path(path)
                    params.forEach { (k, v) -> builder.queryParam(k, v) }
                    builder.queryParam("api_key", apiKey).build()
                }
                .retrieve()
                .body(type)
            body?.right() ?: TmdbBrowseError.Failed("empty body").left()
        } catch (e: HttpClientErrorException.Unauthorized) {
            logger.warn("TMDB browse request {} failed: {} status=401 (API key rejected)", path, e.javaClass.simpleName)
            TmdbBrowseError.ApiKeyRejected.left()
        } catch (e: Exception) {
            // Never log e.message: TMDB URLs carry api_key and I/O errors echo the URL.
            val status = (e as? RestClientResponseException)?.statusCode?.value()
            logger.warn("TMDB browse request {} failed: {} status={}", path, e.javaClass.simpleName, status ?: "n/a")
            TmdbBrowseError.Failed(e.javaClass.simpleName).left()
        }

    private fun apiKey(): String? =
        generalConfigRepository.findByKey(ConfigKey.TMDB_API_KEY)?.value?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val MAX_TMDB_PAGE = 500
        private val PROVIDER_TTL: Duration = Duration.ofHours(24)
        private val PROVIDER_FAILURE_PAUSE: Duration = Duration.ofMinutes(5)

        internal fun TmdbBrowsePageResponse.toPage(type: MediaType): BrowseTitlePage = BrowseTitlePage(
            items = results.orEmpty().mapNotNull { it.toTitle(type) },
            page = page ?: 1,
            totalPages = (totalPages ?: 1).coerceIn(0, MAX_TMDB_PAGE),
            totalResults = totalResults ?: 0,
        )

        internal fun TmdbBrowseItem.toTitle(type: MediaType): BrowseTitle? {
            val tmdbId = id?.takeIf { it > 0 } ?: return null
            if (adult == true) return null
            val name = when (type) {
                MediaType.movie -> listOf(title, originalTitle)
                MediaType.tvshow -> listOf(name, originalName)
            }.firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
            val date = if (type == MediaType.movie) releaseDate else firstAirDate
            return BrowseTitle(
                tmdbId = tmdbId,
                type = type,
                title = name,
                releaseDate = date?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
                posterPath = posterPath?.takeIf { it.isNotBlank() },
                voteAverage = voteAverage?.takeIf { (voteCount ?: 0) > 0 },
                voteCount = voteCount,
                genreIds = genreIds.orEmpty(),
                originalLanguage = originalLanguage?.takeIf { it.isNotBlank() },
            )
        }

        /** Region priority first (falls back to the global one), then name; duplicates and nameless entries dropped. */
        fun parseProviders(response: TmdbProviderListResponse, region: WatchRegion): List<BrowseProvider> =
            response.results.orEmpty()
                .mapNotNull { entry ->
                    val id = entry.providerId?.takeIf { it > 0 } ?: return@mapNotNull null
                    val name = entry.providerName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    BrowseProvider(
                        id = id,
                        name = name,
                        logoPath = entry.logoPath?.takeIf { it.isNotBlank() },
                        priority = entry.displayPriorities?.get(region.value) ?: entry.displayPriority ?: Int.MAX_VALUE,
                    )
                }
                .distinctBy { it.id }
                .sortedWith(compareBy<BrowseProvider> { it.priority }.thenBy { it.name.lowercase() })
    }
}
