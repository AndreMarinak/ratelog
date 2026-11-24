package org.ratelog.extras

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfigRepository
import org.ratelog.tmdb.TmdbRateLimiter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.LocalDate

sealed interface TmdbExtrasError {
    data object ApiKeyMissing : TmdbExtrasError
    data object NotFound : TmdbExtrasError
    data class Failed(val reason: String) : TmdbExtrasError
}

/**
 * Trailer, runtime, vote count, IMDb id and credits for a title in ONE TMDB call
 * (`/movie/{id}` or `/tv/{id}` with `append_to_response`: `videos,external_ids` plus, since 2026-11-24,
 * `credits,release_dates` for movies or `aggregate_credits,content_ratings` for shows).
 * Kept apart from [org.ratelog.tmdb.TmdbClient]; same API key lookup.
 */
@Component
class TmdbExtrasClient(
    private val generalConfigRepository: GeneralConfigRepository,
    @Value("\${ratelog.tmdb.base-url}") private val baseUrl: String = "https://api.themoviedb.org/3",
) {
    private val logger = LoggerFactory.getLogger(TmdbExtrasClient::class.java)
    private val rateLimiter = TmdbRateLimiter(maxRequestsPerSecond = 40)

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

    fun fetch(type: MediaType, tmdbId: TmdbId): Either<TmdbExtrasError, TmdbExtras> {
        val apiKey = generalConfigRepository.findByKey(ConfigKey.TMDB_API_KEY)?.value?.trim().orEmpty()
        if (apiKey.isEmpty()) return TmdbExtrasError.ApiKeyMissing.left()

        val path = when (type) {
            MediaType.movie -> "/movie/{id}"
            MediaType.tvshow -> "/tv/{id}"
        }
        val append = when (type) {
            MediaType.movie -> "videos,external_ids,credits,release_dates"
            MediaType.tvshow -> "videos,external_ids,aggregate_credits,content_ratings"
        }
        return try {
            rateLimiter.acquire()
            val response = restClient.get()
                .uri { builder ->
                    builder.path(path)
                        .queryParam("api_key", apiKey)
                        .queryParam("language", "en-US")
                        .queryParam("append_to_response", append)
                        .queryParam("include_video_language", "en,null")
                        .build(tmdbId.value)
                }
                .retrieve()
                .body(TmdbExtrasResponse::class.java)
            response?.toExtras(type)?.right() ?: TmdbExtrasError.NotFound.left()
        } catch (e: HttpClientErrorException.NotFound) {
            TmdbExtrasError.NotFound.left()
        } catch (e: Exception) {
            // Exception messages can contain the request URL, which carries the API key: log the type only.
            logger.warn("TMDB extras lookup failed for {} {}: {}", type, tmdbId.value, e.javaClass.simpleName)
            TmdbExtrasError.Failed(e.javaClass.simpleName).left()
        }
    }

    companion object {
        private val IMDB_ID = Regex("^tt\\d{5,10}$")

        fun cleanImdbId(value: String?): String? = value?.trim()?.takeIf { IMDB_ID.matches(it) }

        internal fun TmdbExtrasResponse.toExtras(type: MediaType): TmdbExtras {
            val hasVotes = (voteCount ?: 0) > 0
            return when (type) {
                MediaType.movie -> TmdbExtras(
                    title = title?.takeIf { it.isNotBlank() },
                    latestReleaseDate = parseDate(releaseDate),
                    voteAverage = voteAverage.takeIf { hasVotes },
                    voteCount = voteCount.takeIf { hasVotes },
                    runtimeMinutes = runtime?.takeIf { it > 0 },
                    numberOfSeasons = null,
                    trailerYoutubeKey = TrailerSelector.select(videos?.results.orEmpty()),
                    imdbId = cleanImdbId(externalIds?.imdbId) ?: cleanImdbId(imdbId),
                    credits = TitleCreditsParser.movie(this),
                )
                MediaType.tvshow -> TmdbExtras(
                    title = name?.takeIf { it.isNotBlank() },
                    latestReleaseDate = parseDate(lastAirDate) ?: parseDate(lastEpisodeToAir?.airDate) ?: parseDate(firstAirDate),
                    voteAverage = voteAverage.takeIf { hasVotes },
                    voteCount = voteCount.takeIf { hasVotes },
                    runtimeMinutes = episodeRunTime.orEmpty().firstOrNull { it > 0 } ?: lastEpisodeToAir?.runtime?.takeIf { it > 0 },
                    numberOfSeasons = numberOfSeasons?.takeIf { it > 0 },
                    trailerYoutubeKey = TrailerSelector.select(videos?.results.orEmpty()),
                    imdbId = cleanImdbId(externalIds?.imdbId),
                    credits = TitleCreditsParser.tv(this),
                )
            }
        }

        private fun parseDate(value: String?): LocalDate? =
            value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    }
}
