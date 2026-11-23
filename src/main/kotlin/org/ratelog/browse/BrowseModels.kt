package org.ratelog.browse

import org.ratelog.MediaType
import java.time.LocalDate

/** One title from a TMDB discover / trending page. */
data class BrowseTitle(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val releaseDate: LocalDate?,
    val posterPath: String?,
    val voteAverage: Double?,
    val voteCount: Int?,
    val genreIds: List<Int>,
    val originalLanguage: String?,
) {
    val year: Int? get() = releaseDate?.year
}

data class BrowseTitlePage(
    val items: List<BrowseTitle>,
    val page: Int,
    /** Capped at TMDB's page limit (500). */
    val totalPages: Int,
    val totalResults: Int,
)

/** A streaming service from `/watch/providers/{movie|tv}`, ordered by its display priority in the region. */
data class BrowseProvider(
    val id: Int,
    val name: String,
    val logoPath: String?,
    val priority: Int,
)
