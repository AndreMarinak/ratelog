package org.ratelog.ratings.unrated

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.movie.top.TopMovieController
import org.ratelog.tvshow.top.TopTvShowController
import org.ratelog.user.User
import org.ratelog.user.UserDetailsService
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.TileActionsView
import org.slf4j.LoggerFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.LocalDate
import java.util.Locale

/** What templates/ratings-unrated.html renders. */
data class UnratedSeenView(
    val tiles: List<UnratedTileView>,
    val tabs: List<UnratedTab>,
    /** Shown instead of the grid when there is nothing to rate under the current tab. */
    val emptyKey: String?,
)

data class UnratedTab(val labelKey: String, val count: Int, val href: String, val active: Boolean, val type: String)

data class UnratedTileView(
    val href: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val posterUrl2x: String?,
    val initial: String,
    val isMovie: Boolean,
    val actions: TileActionsView,
)

/**
 * GET /ratings/unrated (`type=movie|tv`, else both): the Ratings tab's "To rate" view (user request, 2026-11-23:
 * "show movies watched but haven't rated"). Every tile carries the poster quick actions, so ★ rates a movie in place
 * and the tile leaves the view (static/ratings-unrated.js); a show's ★ opens its seasons.
 */
@Controller
class UnratedSeenController(
    private val handler: GetUnratedSeenHandler,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @GetMapping("/ratings/unrated")
    fun unrated(
        @CurrentUser user: User,
        @RequestParam("type", required = false) type: String?,
        model: Model,
    ): String {
        val selected = parseType(type)
        val result = handler.handle(GetUnratedSeen(user.id!!, selected, user.metadataLang))
        model.addAttribute("unrated", view(result, selected, LocalDate.now(clock), LocaleContextHolder.getLocale()))
        return "ratings-unrated"
    }

    companion object {
        fun parseType(type: String?): MediaType? = when (type?.trim()?.lowercase()) {
            "movie", "movies" -> MediaType.movie
            "tv", "tvshow", "tvshows" -> MediaType.tvshow
            else -> null
        }

        fun view(result: UnratedSeen, selected: MediaType?, today: LocalDate, locale: Locale): UnratedSeenView {
            val count = result.count
            val tabs = listOf(
                UnratedTab("unrated.tab.all", count.total, "/ratings/unrated", selected == null, "all"),
                UnratedTab("unrated.tab.movies", count.movies, "/ratings/unrated?type=movie", selected == MediaType.movie, "movie"),
                UnratedTab("unrated.tab.tv", count.shows, "/ratings/unrated?type=tv", selected == MediaType.tvshow, "tv"),
            )
            return UnratedSeenView(
                tiles = result.titles.map { it.toTile(today, locale) },
                tabs = tabs,
                emptyKey = if (result.titles.isNotEmpty()) null else when (selected) {
                    null -> "unrated.empty"
                    MediaType.movie -> "unrated.empty.movies"
                    MediaType.tvshow -> "unrated.empty.tv"
                },
            )
        }

        private fun UnratedSeenTitle.toTile(today: LocalDate, locale: Locale) = UnratedTileView(
            href = if (type == MediaType.movie) "/movie/$tmdbId" else "/tv/$tmdbId",
            title = title,
            year = year,
            posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
            posterUrl2x = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            initial = title.trim().take(1).uppercase().ifEmpty { "?" },
            isMovie = type == MediaType.movie,
            actions = TileActionsView.of(tmdbId, type, WatchStatus.WATCHED, rated = false, releaseDate = releaseDate, today = today, locale = locale),
        )
    }
}

/**
 * `unratedSeenCount` for the Ratings tab's switch ("To rate 12") on the three Ratings pages. One count query per page
 * view; a failure only hides the number.
 */
@ControllerAdvice(assignableTypes = [TopMovieController::class, TopTvShowController::class, UnratedSeenController::class])
class UnratedSeenCountAdvice(
    private val repository: UnratedSeenRepository,
) {
    private val logger = LoggerFactory.getLogger(UnratedSeenCountAdvice::class.java)

    @ModelAttribute("unratedSeenCount")
    fun unratedSeenCount(): Int? {
        val userId = UserDetailsService.getCurrentUser()?.id ?: return null
        return try {
            repository.count(userId).total
        } catch (e: Exception) {
            logger.warn("Unrated seen count unavailable: {}", e.javaClass.simpleName)
            null
        }
    }
}
