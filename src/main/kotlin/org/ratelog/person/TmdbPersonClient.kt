package org.ratelog.person

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import org.ratelog.Lang
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
import java.time.Clock
import java.time.Duration

sealed interface PersonLookupError {
    data object NotFound : PersonLookupError
    data object ApiKeyMissing : PersonLookupError
    data object ApiKeyRejected : PersonLookupError
    data class Failed(val reason: String) : PersonLookupError
}

/**
 * TMDB people: a person with all their credits (one request: `/person/{id}?append_to_response=combined_credits`) and
 * `/search/person`. Kept apart from [org.ratelog.tmdb.TmdbClient]; same API key lookup. Results are cached in memory
 * (a person for a day, a people search for half an hour); failures are returned, never thrown, and never cached.
 */
@Component
class TmdbPersonClient(
    private val generalConfigRepository: GeneralConfigRepository,
    @Value("\${ratelog.tmdb.base-url}") baseUrl: String = "https://api.themoviedb.org/3",
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(TmdbPersonClient::class.java)
    private val rateLimiter = TmdbRateLimiter(maxRequestsPerSecond = 40)

    private val personCache = TtlCache<String, PersonDetails>(PERSON_TTL, maxEntries = 300, clock)
    private val searchCache = TtlCache<String, List<PersonSummary>>(SEARCH_TTL, maxEntries = 300, clock)

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build()
            ).apply {
                setReadTimeout(Duration.ofSeconds(8))
            }
        )
        .build()

    fun person(id: Int, lang: Lang): Either<PersonLookupError, PersonDetails> {
        if (id <= 0) return PersonLookupError.NotFound.left()
        val apiKey = apiKey() ?: return PersonLookupError.ApiKeyMissing.left()
        return personCache.getOrLoad("$id|${lang.name}") {
            val params = mapOf("language" to lang.name, "append_to_response" to "combined_credits")
            either {
                val response = call("/person/$id", params, apiKey, TmdbPersonResponse::class.java).bind()
                PersonParsing.details(response) ?: raise(PersonLookupError.NotFound)
            }
        }
    }

    /** People named like [query], at most [limit], filtered as [PersonParsing.searchResults] describes. */
    fun search(query: String, lang: Lang, limit: Int = SEARCH_LIMIT): Either<PersonLookupError, List<PersonSummary>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList<PersonSummary>().right()
        val apiKey = apiKey() ?: return PersonLookupError.ApiKeyMissing.left()
        return searchCache.getOrLoad("${PersonParsing.normalize(q)}|${lang.name}|$limit") {
            val params = mapOf("query" to q, "language" to lang.name, "include_adult" to "false", "page" to "1")
            call("/search/person", params, apiKey, TmdbPersonSearchResponse::class.java)
                .map { PersonParsing.searchResults(it, q, limit) }
        }
    }

    private fun <T : Any> call(path: String, params: Map<String, String>, apiKey: String, type: Class<T>): Either<PersonLookupError, T> =
        try {
            rateLimiter.acquire()
            val body = restClient.get()
                .uri { builder ->
                    builder.path(path)
                    params.forEach { (k, v) -> builder.queryParam(k, "{${k}}") }
                    builder.queryParam("api_key", "{api_key}").build(params + ("api_key" to apiKey))
                }
                .retrieve()
                .body(type)
            body?.right() ?: PersonLookupError.Failed("empty body").left()
        } catch (e: HttpClientErrorException.NotFound) {
            PersonLookupError.NotFound.left()
        } catch (e: HttpClientErrorException.Unauthorized) {
            logger.warn("TMDB person request failed: {} status=401 (API key rejected)", e.javaClass.simpleName)
            PersonLookupError.ApiKeyRejected.left()
        } catch (e: Exception) {
            // Never log e.message or the path's query: TMDB URLs carry api_key and I/O errors echo the URL.
            val status = (e as? RestClientResponseException)?.statusCode?.value()
            logger.warn("TMDB person request failed: {} status={}", e.javaClass.simpleName, status ?: "n/a")
            PersonLookupError.Failed(e.javaClass.simpleName).left()
        }

    private fun apiKey(): String? =
        generalConfigRepository.findByKey(ConfigKey.TMDB_API_KEY)?.value?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val SEARCH_LIMIT = 6
        private val PERSON_TTL: Duration = Duration.ofDays(1)
        private val SEARCH_TTL: Duration = Duration.ofMinutes(30)
    }
}
