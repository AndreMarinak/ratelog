package org.ratelog.extras

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** `/movie/{id}` or `/tv/{id}` with `append_to_response=videos,external_ids`. Only the fields used here. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbExtrasResponse(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("last_air_date") val lastAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("vote_count") val voteCount: Int? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
    @JsonProperty("last_episode_to_air") val lastEpisodeToAir: TmdbEpisodeRef? = null,
    @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("videos") val videos: TmdbVideosResponse? = null,
    @JsonProperty("external_ids") val externalIds: TmdbExternalIdsResponse? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbEpisodeRef(
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("air_date") val airDate: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbVideosResponse(
    @JsonProperty("results") val results: List<TmdbVideo>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbVideo(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("official") val official: Boolean? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("iso_639_1") val language: String? = null,
    @JsonProperty("size") val size: Int? = null,
    @JsonProperty("published_at") val publishedAt: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbExternalIdsResponse(
    @JsonProperty("imdb_id") val imdbId: String? = null,
)
