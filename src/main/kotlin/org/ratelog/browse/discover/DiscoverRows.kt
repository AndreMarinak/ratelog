package org.ratelog.browse.discover

import org.ratelog.Genre
import org.ratelog.MediaType
import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowseGenres
import org.ratelog.browse.BrowsePopular

/**
 * One category row on the Discover landing. [filters] are exactly the ones its "More" grid uses, so the row
 * and page 1 of that grid are the same (cached) TMDB request.
 */
data class DiscoverRowSpec(
    /** `popular` or a genre slug (`horror`, `sci-fi-fantasy`): the HTML id suffix and `/discover/row?row=`. */
    val id: String,
    val filters: BrowseFilters,
) {
    val genre: Genre? get() = filters.genre
}

/** The Discover landing's rows, in display order: "Popular now", then genres (TMDB's TV genres differ from movies'). */
object DiscoverRows {
    const val POPULAR = "popular"

    val MOVIE_GENRES: List<Genre> = listOf(
        Genre.HORROR, Genre.COMEDY, Genre.ACTION, Genre.ANIMATION, Genre.DRAMA, Genre.SCIENCE_FICTION, Genre.THRILLER,
        Genre.ROMANCE, Genre.CRIME, Genre.FANTASY, Genre.MYSTERY, Genre.FAMILY, Genre.DOCUMENTARY,
    )

    /** News, Talk and Soap are left out: their posters are mostly hosts and logos. */
    val TV_GENRES: List<Genre> = listOf(
        Genre.DRAMA, Genre.COMEDY, Genre.CRIME, Genre.SCI_FI_FANTASY, Genre.ACTION_ADVENTURE, Genre.MYSTERY, Genre.ANIMATION,
        Genre.DOCUMENTARY, Genre.REALITY, Genre.FAMILY, Genre.KIDS, Genre.WAR_POLITICS, Genre.WESTERN,
    )

    fun genres(type: MediaType): List<Genre> = if (type == MediaType.movie) MOVIE_GENRES else TV_GENRES

    /** Every row for [type], carrying the viewer's "hide seen" choice into each row's filters (and so its links). */
    fun forType(type: MediaType, hideSeen: Boolean): List<DiscoverRowSpec> {
        val popular = DiscoverRowSpec(POPULAR, BrowseFilters(type = type, popular = BrowsePopular.NOW, hideSeen = hideSeen))
        return listOf(popular) + genres(type).map { genre ->
            DiscoverRowSpec(BrowseGenres.slug(genre), BrowseFilters(type = type, genre = genre, hideSeen = hideSeen))
        }
    }

    /** The row with this id for [type], or null (unknown id, or a genre the type's rows don't include). */
    fun find(type: MediaType, id: String, hideSeen: Boolean): DiscoverRowSpec? {
        val key = id.trim().lowercase()
        return forType(type, hideSeen).firstOrNull { it.id == key }
    }
}
