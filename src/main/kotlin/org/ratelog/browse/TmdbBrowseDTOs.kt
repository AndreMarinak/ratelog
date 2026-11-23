package org.ratelog.browse

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** `/discover/{movie|tv}` and `/trending/{movie|tv}/week`. Only the fields used here. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbBrowsePageResponse(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("results") val results: List<TmdbBrowseItem>? = null,
    @JsonProperty("total_pages") val totalPages: Int? = null,
    @JsonProperty("total_results") val totalResults: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbBrowseItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("vote_count") val voteCount: Int? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int>? = null,
    @JsonProperty("original_language") val originalLanguage: String? = null,
    @JsonProperty("adult") val adult: Boolean? = null,
)

/** `/watch/providers/{movie|tv}?watch_region=XX`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbProviderListResponse(
    @JsonProperty("results") val results: List<TmdbProviderListEntry>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbProviderListEntry(
    @JsonProperty("provider_id") val providerId: Int? = null,
    @JsonProperty("provider_name") val providerName: String? = null,
    @JsonProperty("logo_path") val logoPath: String? = null,
    @JsonProperty("display_priority") val displayPriority: Int? = null,
    @JsonProperty("display_priorities") val displayPriorities: Map<String, Int>? = null,
)
