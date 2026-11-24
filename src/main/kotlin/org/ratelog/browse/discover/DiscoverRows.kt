package org.ratelog.browse.discover

import org.ratelog.Genre
import org.ratelog.MediaType
import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowseGenres
import org.ratelog.browse.BrowsePopular
import org.ratelog.browse.BrowseSort
import org.ratelog.home.DiscoverFeed

/**
 * One category row on the Discover landing. [filters] are exactly the ones its "More" grid uses, so the row
 * and page 1 of that grid are the same (cached) TMDB request; except a [feed] row, whose titles come from that
 * TMDB list (cached by [org.ratelog.home.TmdbDiscoverClient]) and whose "More" opens the closest grid.
 */
data class DiscoverRowSpec(
    /** `popular`, `classics` or a genre slug (`horror`, `sci-fi-fantasy`): the HTML id suffix and `/discover/row?row=`. */
    val id: String,
    val filters: BrowseFilters,
    /** Fork: a fixed TMDB list instead of the [filters] grid (Classics, moved from Home on 2026-11-23). */
    val feed: DiscoverFeed? = null,
    /** The heading's message key; null: the genre's name, or "Popular now". */
    val titleKey: String? = null,
) {
    val genre: Genre? get() = filters.genre
}

/**
 * The Discover landing's rows, in display order: "Popular now", Classics (movies), then genres (TMDB's TV genres
 * differ from movies'). "Recommended for you" and "Official lists" are added after "Popular now" by their own
 * fragments. TV has no Popular TV row: its "Popular now" already is TMDB's TV popularity ordering.
 */
object DiscoverRows {
    const val POPULAR = "popular"
    const val CLASSICS = "classics"

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

    /**
     * Genre rows show well-known, well-liked titles from every era (most votes on TMDB, rated [GENRE_MIN_RATING]+),
     * not TMDB's "popularity", which is almost all current releases (user feedback 2026-11-23: "almost strictly 2026
     * movies"). "Popular now" stays the row for what's new.
     */
    const val GENRE_MIN_RATING = 7.0

    /** Every row for [type], carrying the viewer's "hide seen" choice into each row's filters (and so its links). */
    fun forType(type: MediaType, hideSeen: Boolean): List<DiscoverRowSpec> {
        val popular = DiscoverRowSpec(POPULAR, BrowseFilters(type = type, popular = BrowsePopular.NOW, hideSeen = hideSeen))
        // Home's Classics row (acclaimed films before 2000), moved here; "More" opens highest rated, as on Home.
        val classics = DiscoverRowSpec(
            CLASSICS,
            BrowseFilters(type = MediaType.movie, sort = BrowseSort.RATING, hideSeen = hideSeen),
            feed = DiscoverFeed.CLASSICS,
            titleKey = "home.row.classics",
        ).takeIf { type == MediaType.movie }
        return listOfNotNull(popular, classics) + genres(type).map { genre ->
            DiscoverRowSpec(
                BrowseGenres.slug(genre),
                BrowseFilters(type = type, genre = genre, popular = BrowsePopular.ALL_TIME, minRating = GENRE_MIN_RATING, hideSeen = hideSeen),
            )
        }
    }

    /** The row with this id for [type], or null (unknown id, or a genre the type's rows don't include). */
    fun find(type: MediaType, id: String, hideSeen: Boolean): DiscoverRowSpec? {
        val key = id.trim().lowercase()
        return forType(type, hideSeen).firstOrNull { it.id == key }
    }
}
