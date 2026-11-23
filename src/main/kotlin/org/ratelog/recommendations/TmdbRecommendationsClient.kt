package org.ratelog.recommendations

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.browse.TtlCache
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
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** One movie or show from TMDB's recommendations or a collection's parts. */
data class RelatedTitle(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val releaseDate: LocalDate?,
    val posterPath: String?,
    val voteAverage: Double?,
    val voteCount: Int,
) {
    val year: Int? get() = releaseDate?.year
}

/** `belongs_to_collection` on `/movie/{id}`. */
data class CollectionRef(val id: Int, val name: String)

/** One page of TMDB recommendations; [collection] only on a movie's page 1 (it comes from the same call). */
data class RecommendationsPage(
    val collection: CollectionRef?,
    val titles: List<RelatedTitle>,
    val page: Int,
    val totalPages: Int,
)

/** `/collection/{id}`: a movie series, parts in TMDB's order (the handler sorts them by release date). */
data class TitleCollection(
    val id: Int,
    val name: String,
    val parts: List<RelatedTitle>,
)

sealed interface TmdbRecommendationsError {
    data object ApiKeyMissing : TmdbRecommendationsError

    /** TMDB has no such title / collection (404). */
    data object NotFound : TmdbRecommendationsError

    /** TMDB rejected the request, timed out or sent something unreadable. */
    data object Unavailable : TmdbRecommendationsError
}

/**
 * TMDB recommendations and collections for the title pages' "Part of the ... Collection" and "More like this" rows.
 * Kept apart from [org.ratelog.tmdb.TmdbClient]; same API key lookup, the metadata language for titles.
 *
 * - A movie's page 1 is `/movie/{id}?append_to_response=recommendations`: one call gives both the recommendations and
 *   the collection the movie belongs to. Later pages and shows use `/{movie|tv}/{id}/recommendations`.
 * - `/collection/{id}` lists the series.
 *
 * Results are cached in memory (recommendations for [cacheHours], collections for [collectionCacheDays]: both change
 * rarely). A failed request isn't retried for [FAILURE_PAUSE], so a TMDB outage costs one slow request per title, not
 * one per page view. Failures are returned, never thrown, and never logged with their message (the URL has the key).
 */
@Component
class TmdbRecommendationsClient(
    private val generalConfigRepository: GeneralConfigRepository,
    @Value("\${ratelog.tmdb.base-url}") baseUrl: String = "https://api.themoviedb.org/3",
    @Value("\${ratelog.recommendations.cache-hours:24}") cacheHours: Long = 24,
    @Value("\${ratelog.recommendations.collection-cache-days:7}") collectionCacheDays: Long = 7,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(TmdbRecommendationsClient::class.java)
    private val rateLimiter = TmdbRateLimiter(maxRequestsPerSecond = 40)

    private val pageCache = TtlCache<String, RecommendationsPage>(Duration.ofHours(cacheHours), maxEntries = 1000, clock)
    private val collectionCache = TtlCache<String, TitleCollection>(Duration.ofDays(collectionCacheDays), maxEntries = 300, clock)

    /** Keys that failed recently, with the time they may be tried again. */
    private val failures = ConcurrentHashMap<String, Pair<Instant, TmdbRecommendationsError>>()

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            ).apply { setReadTimeout(Duration.ofSeconds(6)) }
        )
        .build()

    /** TMDB's recommendations for a title (20 per page), adult titles and nameless results left out. */
    fun recommendations(type: MediaType, tmdbId: Int, lang: Lang, page: Int = 1): Either<TmdbRecommendationsError, RecommendationsPage> {
        val apiKey = apiKey() ?: return TmdbRecommendationsError.ApiKeyMissing.left()
        val key = "${type.name}|$tmdbId|${lang.name}|$page"
        pageCache.get(key)?.let { return it.right() }
        recentFailure(key)?.let { return it.left() }

        val result = if (type == MediaType.movie && page == 1) {
            call("/movie/{id}", tmdbId, mapOf("language" to lang.name, "append_to_response" to "recommendations"), apiKey, TmdbMovieWithRecommendationsDto::class.java)
                .map { movie ->
                    val recs = movie.recommendations
                    RecommendationsPage(
                        collection = movie.collection?.toRef(),
                        titles = recs?.results.orEmpty().mapNotNull { it.toTitle(type) },
                        page = recs?.page ?: 1,
                        totalPages = recs?.totalPages ?: 1,
                    )
                }
        } else {
            val path = if (type == MediaType.movie) "/movie/{id}/recommendations" else "/tv/{id}/recommendations"
            call(path, tmdbId, mapOf("language" to lang.name, "page" to page.toString()), apiKey, TmdbRelatedPageDto::class.java)
                .map { recs ->
                    RecommendationsPage(
                        collection = null,
                        titles = recs.results.orEmpty().mapNotNull { it.toTitle(type) },
                        page = recs.page ?: page,
                        totalPages = recs.totalPages ?: page,
                    )
                }
        }
        return remember(key, result) { pageCache.put(key, it) }
    }

    /** A movie series (TMDB collection), parts in TMDB's order. */
    fun collection(collectionId: Int, lang: Lang): Either<TmdbRecommendationsError, TitleCollection> {
        val apiKey = apiKey() ?: return TmdbRecommendationsError.ApiKeyMissing.left()
        val key = "collection|$collectionId|${lang.name}"
        collectionCache.get(key)?.let { return it.right() }
        recentFailure(key)?.let { return it.left() }

        val result = call("/collection/{id}", collectionId, mapOf("language" to lang.name), apiKey, TmdbCollectionDto::class.java)
            .map { dto ->
                TitleCollection(
                    id = dto.id ?: collectionId,
                    name = dto.name?.trim().orEmpty(),
                    parts = dto.parts.orEmpty().mapNotNull { it.toTitle(MediaType.movie) },
                )
            }
        return remember(key, result) { collectionCache.put(key, it) }
    }

    private fun <T> remember(
        key: String,
        result: Either<TmdbRecommendationsError, T>,
        store: (T) -> Unit,
    ): Either<TmdbRecommendationsError, T> = result
        .onRight { store(it); failures.remove(key) }
        .onLeft { failures[key] = clock.instant().plus(FAILURE_PAUSE) to it }

    private fun recentFailure(key: String): TmdbRecommendationsError? {
        val (until, error) = failures[key] ?: return null
        if (clock.instant().isBefore(until)) return error
        failures.remove(key)
        return null
    }

    private fun <T : Any> call(
        path: String,
        id: Int,
        params: Map<String, String>,
        apiKey: String,
        type: Class<T>,
    ): Either<TmdbRecommendationsError, T> = try {
        rateLimiter.acquire()
        val body = restClient.get()
            .uri { builder ->
                builder.path(path)
                params.forEach { (k, v) -> builder.queryParam(k, v) }
                builder.queryParam("api_key", apiKey).build(id)
            }
            .retrieve()
            .body(type)
        body?.right() ?: TmdbRecommendationsError.Unavailable.left()
    } catch (e: HttpClientErrorException.NotFound) {
        TmdbRecommendationsError.NotFound.left()
    } catch (e: Exception) {
        // Never log e.message: TMDB URLs carry api_key and I/O errors echo the URL.
        val status = (e as? RestClientResponseException)?.statusCode?.value()
        logger.warn("TMDB recommendations request {} {} failed: {} status={}", path, id, e.javaClass.simpleName, status ?: "n/a")
        TmdbRecommendationsError.Unavailable.left()
    }

    private fun apiKey(): String? =
        generalConfigRepository.findByKey(ConfigKey.TMDB_API_KEY)?.value?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        val FAILURE_PAUSE: Duration = Duration.ofMinutes(2)
    }
}
