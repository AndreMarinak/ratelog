package org.ratelog.recommendations

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.ratelog.MediaType
import java.time.LocalDate

/** `/movie/{id}?append_to_response=recommendations`: only the collection and the recommendations are read. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbMovieWithRecommendationsDto(
    @JsonProperty("belongs_to_collection") val collection: TmdbCollectionRefDto? = null,
    @JsonProperty("recommendations") val recommendations: TmdbRelatedPageDto? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbCollectionRefDto(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
) {
    fun toRef(): CollectionRef? {
        val collectionId = id?.takeIf { it > 0 } ?: return null
        return CollectionRef(collectionId, name?.trim().orEmpty())
    }
}

/** `/{movie|tv}/{id}/recommendations` (and the appended `recommendations` block). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbRelatedPageDto(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("results") val results: List<TmdbRelatedItemDto>? = null,
    @JsonProperty("total_pages") val totalPages: Int? = null,
)

/** `/collection/{id}`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbCollectionDto(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("parts") val parts: List<TmdbRelatedItemDto>? = null,
)

/** A movie (title / release_date) or show (name / first_air_date) in a TMDB list. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbRelatedItemDto(
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
    @JsonProperty("adult") val adult: Boolean? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
) {
    /** Null for adult titles, results without an id or a name, and results of the other media type. */
    fun toTitle(type: MediaType): RelatedTitle? {
        val tmdbId = id?.takeIf { it > 0 } ?: return null
        if (adult == true) return null
        val expected = if (type == MediaType.movie) "movie" else "tv"
        if (!mediaType.isNullOrBlank() && mediaType != expected) return null
        val names = if (type == MediaType.movie) listOf(title, originalTitle, name) else listOf(name, originalName, title)
        val label = names.firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
        val date = if (type == MediaType.movie) releaseDate else firstAirDate
        val votes = voteCount ?: 0
        return RelatedTitle(
            tmdbId = tmdbId,
            type = type,
            title = label,
            releaseDate = date?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
            posterPath = posterPath?.takeIf { it.isNotBlank() },
            voteAverage = voteAverage?.takeIf { votes > 0 },
            voteCount = votes,
        )
    }
}
