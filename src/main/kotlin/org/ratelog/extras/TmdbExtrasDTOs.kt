package org.ratelog.extras

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * `/movie/{id}` with `append_to_response=videos,external_ids,credits,release_dates`, or `/tv/{id}` with
 * `videos,external_ids,aggregate_credits,content_ratings`. Only the fields used here.
 */
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
    // Fork (2026-11-24): credits and the facts around the overview (TitleCreditsParser).
    @JsonProperty("tagline") val tagline: String? = null,
    @JsonProperty("budget") val budget: Long? = null,
    @JsonProperty("revenue") val revenue: Long? = null,
    @JsonProperty("production_companies") val productionCompanies: List<TmdbNamedRef>? = null,
    @JsonProperty("credits") val credits: TmdbCreditsResponse? = null,
    @JsonProperty("release_dates") val releaseDates: TmdbReleaseDatesResponse? = null,
    @JsonProperty("created_by") val createdBy: List<TmdbNamedRef>? = null,
    @JsonProperty("networks") val networks: List<TmdbNamedRef>? = null,
    @JsonProperty("number_of_episodes") val numberOfEpisodes: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("aggregate_credits") val aggregateCredits: TmdbAggregateCreditsResponse? = null,
    @JsonProperty("content_ratings") val contentRatings: TmdbContentRatingsResponse? = null,
)

/** A company, network or show creator: `{id, name}`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbNamedRef(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbCreditsResponse(
    @JsonProperty("cast") val cast: List<TmdbCastMember>? = null,
    @JsonProperty("crew") val crew: List<TmdbCrewMember>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbCastMember(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
    @JsonProperty("order") val order: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbCrewMember(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("job") val job: String? = null,
    @JsonProperty("department") val department: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbReleaseDatesResponse(
    @JsonProperty("results") val results: List<TmdbCountryReleases>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbCountryReleases(
    @JsonProperty("iso_3166_1") val country: String? = null,
    @JsonProperty("release_dates") val releaseDates: List<TmdbReleaseDate>? = null,
)

/** `type`: 1 premiere, 2 limited theatrical, 3 theatrical, 4 digital, 5 physical, 6 TV. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbReleaseDate(
    @JsonProperty("certification") val certification: String? = null,
    @JsonProperty("type") val type: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbAggregateCreditsResponse(
    @JsonProperty("cast") val cast: List<TmdbAggregateCastMember>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbAggregateCastMember(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
    @JsonProperty("roles") val roles: List<TmdbAggregateRole>? = null,
    @JsonProperty("total_episode_count") val totalEpisodeCount: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbAggregateRole(
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbContentRatingsResponse(
    @JsonProperty("results") val results: List<TmdbContentRating>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbContentRating(
    @JsonProperty("iso_3166_1") val country: String? = null,
    @JsonProperty("rating") val rating: String? = null,
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
