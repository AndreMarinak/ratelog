package org.ratelog.tile

import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.user.UserDetailsService
import org.ratelog.watchlist.seen.TileActionsView
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * What the poster tile fragment (`fragments/tile-actions.html`) asks about every tile, without each page's tile query
 * having to carry it: is the title in one of my lists (the list button's ticked state), and is my rating of this
 * movie a detailed one (the rating picker must not flatten it). Each answer set is read with **one query, on first
 * use**, then kept for the rest of the request, so a page with 200 tiles costs at most two queries and a page with
 * no tiles none. A failing query only loses the mark (logged), never the page.
 */
class TileMarks(
    private val repository: TileMarksRepository,
    private val currentUser: () -> User?,
) {
    private val logger = LoggerFactory.getLogger(TileMarks::class.java)

    private val listed: Set<TileKey> by lazy { load("lists") { repository.findListedTitles(it) } }
    private val detailedMovies: Set<Int> by lazy { load("detailed ratings") { repository.findDetailedMovieRatings(it) } }
    private val seasonRatedShows: Set<Int> by lazy { load("season ratings") { repository.findSeasonRatedShows(it) } }

    fun inList(type: MediaType, tmdbId: Int): Boolean = TileKey(type, tmdbId) in listed

    /** The tile's title is in at least one of the viewer's own lists. */
    fun inList(a: TileActionsView): Boolean = typeOf(a)?.let { inList(it, a.tmdbId) } ?: false

    /** A rated movie whose rating has all five categories (shows: never, see [ratedBySeason]). */
    fun detailed(a: TileActionsView): Boolean =
        a.ratingDetailed || (a.isMovie && a.score != null && a.tmdbId in detailedMovies)

    /**
     * Fork: a show with at least one rated season, so its score is their average and the ★ picker offers "Rate seasons"
     * instead of a whole-show score (which would not be used).
     */
    fun ratedBySeason(a: TileActionsView): Boolean =
        !a.isMovie && a.type == "tv" && a.score != null && a.tmdbId in seasonRatedShows

    private fun <T> load(what: String, query: (User.Id) -> Set<T>): Set<T> {
        val userId = currentUser()?.id ?: return emptySet()
        return try {
            query(userId)
        } catch (e: Exception) {
            logger.warn("Tile marks ({}) unavailable: {}", what, e.javaClass.simpleName)
            emptySet()
        }
    }

    private fun typeOf(a: TileActionsView): MediaType? = if (a.isMovie) MediaType.movie else if (a.type == "tv") MediaType.tvshow else null
}

/**
 * Puts a fresh, lazy [TileMarks] in the model of every page as `tileMarks`. Nothing is queried unless a tile renders,
 * so JSON endpoints and pages without posters pay nothing. Upstream controllers stay untouched.
 */
@ControllerAdvice
class TileMarksAdvice(
    private val repository: TileMarksRepository,
) {
    @ModelAttribute("tileMarks")
    fun tileMarks(): TileMarks = TileMarks(repository) { UserDetailsService.getCurrentUser() }
}
