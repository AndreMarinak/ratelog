package org.ratelog.person

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** `/person/{id}?append_to_response=combined_credits`: the person and every credit in one request. Only the fields used here. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbPersonResponse(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("biography") val biography: String? = null,
    @JsonProperty("birthday") val birthday: String? = null,
    @JsonProperty("deathday") val deathday: String? = null,
    @JsonProperty("place_of_birth") val placeOfBirth: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
    @JsonProperty("known_for_department") val knownForDepartment: String? = null,
    @JsonProperty("adult") val adult: Boolean? = null,
    @JsonProperty("combined_credits") val combinedCredits: TmdbCombinedCredits? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbCombinedCredits(
    @JsonProperty("cast") val cast: List<TmdbPersonCredit>? = null,
    @JsonProperty("crew") val crew: List<TmdbPersonCredit>? = null,
)

/** One cast or crew credit; `media_type` says whether `title` / `release_date` or `name` / `first_air_date` apply. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbPersonCredit(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("job") val job: String? = null,
    @JsonProperty("department") val department: String? = null,
    @JsonProperty("popularity") val popularity: Double? = null,
    @JsonProperty("vote_count") val voteCount: Int? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int>? = null,
    @JsonProperty("adult") val adult: Boolean? = null,
)

/** `/search/person`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbPersonSearchResponse(
    @JsonProperty("results") val results: List<TmdbPersonSearchItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbPersonSearchItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
    @JsonProperty("known_for_department") val knownForDepartment: String? = null,
    @JsonProperty("popularity") val popularity: Double? = null,
    @JsonProperty("adult") val adult: Boolean? = null,
    @JsonProperty("known_for") val knownFor: List<TmdbPersonCredit>? = null,
)
