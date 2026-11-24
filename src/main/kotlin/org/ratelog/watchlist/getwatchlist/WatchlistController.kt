package org.ratelog.watchlist.getwatchlist

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.move.WatchlistMove
import org.ratelog.watchlist.seen.ReleaseRules
import org.ratelog.watchlist.seen.TileActionsView
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.LocalDate
import java.util.Locale

data class WatchlistTabResponse(
    val labelKey: String,
    val icon: String,
    val count: Int,
    val href: String,
    val active: Boolean,
    /** Api name of the tab's status (watchlist.js moves counts between tabs by it). */
    val status: String = "",
)

data class WatchlistTypeFilterResponse(
    val labelKey: String,
    val href: String,
    val active: Boolean,
)

/** One quick-action button under a watchlist tile (POST /watchlist/item). */
data class WatchlistItemAction(
    /** The `to` field: seen | watching | dropped | remove. */
    val to: String,
    val labelKey: String,
    /** Accessible name with the title as {0}, e.g. `Mark "Dune" as seen`. */
    val ariaKey: String,
    val icon: String,
    /** CSS modifier: `is-seen`, `is-watching`, `is-dropped`, `is-remove`. */
    val kind: String,
)

data class WatchlistItemResponse(
    val href: String,
    val isMovie: Boolean,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val score: Double?,
    /** `movie` / `tvshow` and the local id, for the quick-action forms. */
    val mediaType: String = if (isMovie) "movie" else "tvshow",
    val mediaId: Long = 0,
    /** Short release date while it's upcoming ("Dec 18"), else null. */
    val upcomingDate: String? = null,
    val actions: List<WatchlistItemAction> = emptyList(),
    /** Fork (tile rating): the poster's Add to list and ★ (fragments/tile-actions.html); the buttons above cover the rest. */
    val tileActions: TileActionsView? = null,
    /** Slots in the action row: 3 on every tab but Seen (1), the same for movies and shows. */
    val slots: Int = 3,
    /** Shown in the slots no button can fill ("Out Dec 18" where Seen would be, "Rated" where Remove would be). */
    val noteKey: String? = null,
) {
    val upcoming: Boolean get() = upcomingDate != null

    /** How many slots the note takes. */
    val noteSpan: Int get() = (slots - actions.size).coerceAtLeast(1)

    /**
     * How many slots the first button takes: a movie has no Watching / Drop, so its Seen widens into that slot and
     * Remove stays in the last slot, lined up with the shows next to it.
     */
    val firstSpan: Int get() = if (noteKey != null) 1 else (slots - actions.size + 1).coerceAtLeast(1)
}

@Controller
class WatchlistController(
    private val handler: GetWatchlistHandler,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /**
     * `status`: want_to_watch (default) | watching | watched | dropped. `type`: movie | tv; without it the page opens on
     * Movies like Ratings and Discover (user, 2026-11-23), except the Watching tab, which opens on TV (only shows are
     * Watching). Movies | TV is a two-way switch, no "All", and every tab link keeps the type.
     */
    @GetMapping("/watchlist")
    fun watchlistPage(
        @CurrentUser user: User,
        @RequestParam("status", required = false) status: String?,
        @RequestParam("type", required = false) type: String?,
        model: Model,
    ): String {
        val selectedStatus = WatchStatus.parse(status) ?: WatchStatus.DEFAULT_TAB
        val selectedType = TYPE_FILTERS[type?.trim()?.lowercase()] ?: defaultType(selectedStatus)

        val watchlist = GetWatchlist(
            userId = user.id!!,
            status = selectedStatus,
            mediaType = selectedType,
            lang = user.metadataLang,
        ).let(handler::handle)

        val typeParam = typeParam(selectedType)
        model.addAttribute("tabs", WatchStatus.entries.map { s ->
            WatchlistTabResponse(
                labelKey = tabLabelKey(s),
                icon = s.icon,
                count = watchlist.counts[s] ?: 0,
                href = href(s, typeParam),
                active = s == selectedStatus,
                status = s.apiName,
            )
        })
        model.addAttribute("typeFilters", MediaType.entries.map { t ->
            WatchlistTypeFilterResponse(
                labelKey = when (t) {
                    MediaType.movie -> "watchlist.filter.movies"
                    MediaType.tvshow -> "watchlist.filter.tv"
                },
                href = href(selectedStatus, typeParam(t)),
                active = t == selectedType,
            )
        })
        val today = LocalDate.now(clock)
        val locale = LocaleContextHolder.getLocale()
        model.addAttribute("items", watchlist.items.map { toResponse(it, today, locale) })
        // Watching is for shows: on Movies it says so (a movie only has it from before, or through the API).
        val moviesWatching = selectedStatus == WatchStatus.WATCHING && selectedType == MediaType.movie
        model.addAttribute("emptyKey", if (moviesWatching) "watchlist.tab.empty.watching.movie" else "watchlist.tab.empty.${selectedStatus.apiName}")
        model.addAttribute("tabHintKey", if (moviesWatching) "watchlist.tab.hint.watching.movie" else "watchlist.tab.hint.${selectedStatus.apiName}")
        // Every quick action comes back to this tab and filter.
        model.addAttribute("watchlistSelf", href(selectedStatus, typeParam))

        return "watchlist"
    }

    private fun toResponse(item: WatchlistItem, today: LocalDate, locale: Locale): WatchlistItemResponse {
        val upcoming = ReleaseRules.isUpcoming(item.releaseDate, today)
        return WatchlistItemResponse(
            href = when (item.mediaType) {
                MediaType.movie -> "/movie/${item.tmdbId}"
                MediaType.tvshow -> "/tv/${item.tmdbId}"
            },
            isMovie = item.mediaType == MediaType.movie,
            title = item.title,
            year = item.year,
            posterPath = item.posterPath,
            score = item.score,
            mediaType = item.mediaType.name,
            mediaId = item.mediaId,
            upcomingDate = if (upcoming) item.releaseDate?.let { ReleaseRules.shortDate(it, today, locale) } else null,
            actions = actionsFor(item.status, item.mediaType == MediaType.tvshow, upcoming, rated = item.score != null),
            slots = slotsFor(item.status),
            noteKey = noteFor(item.status, upcoming, rated = item.score != null, hasDate = item.releaseDate != null),
            tileActions = TileActionsView.of(
                item.tmdbId, item.mediaType, item.status, rated = item.score != null, releaseDate = item.releaseDate,
                today = today, locale = locale, score = item.score,
            ),
        )
    }

    private fun href(status: WatchStatus, typeParam: String) =
        "/watchlist?status=${status.apiName}&type=$typeParam"

    private fun typeParam(type: MediaType) = when (type) {
        MediaType.movie -> "movie"
        MediaType.tvshow -> "tv"
    }

    companion object {
        private val TYPE_FILTERS = mapOf(
            "movie" to MediaType.movie,
            "movies" to MediaType.movie,
            "tv" to MediaType.tvshow,
            "tvshow" to MediaType.tvshow,
        )

        /** The type shown when the URL has none: Movies, or TV on the Watching tab (a series is what you're in the middle of). */
        fun defaultType(status: WatchStatus): MediaType =
            if (status == WatchStatus.WATCHING) MediaType.tvshow else MediaType.movie

        /** Tabs named after the title-page toggles: To watch, Watching, Seen, Dropped. */
        fun tabLabelKey(status: WatchStatus) = "watchlist.tab.${status.apiName}"

        /**
         * The buttons under a tile, per tab, always in the same order: Seen, then Watching / Drop (shows only: a series
         * is something you're in the middle of), then Remove. To watch and Dropped: Seen · Watching · Remove; Watching:
         * Seen · Drop · Remove; Seen: Remove. An upcoming title gets no Seen or Watching (it isn't out; [noteFor] fills the
         * slot). A rated title on the Seen tab gets no Remove: it would still count as seen through its rating.
         */
        fun actionsFor(status: WatchStatus, isTv: Boolean, upcoming: Boolean, rated: Boolean): List<WatchlistItemAction> {
            val seen = WatchlistItemAction(WatchlistMove.SEEN.param, "watchlist.action.seen", "watchlist.action.seen.aria", "eye", "is-seen")
            val watching = WatchlistItemAction(WatchlistMove.WATCHING.param, "watchlist.action.watching", "watchlist.action.watching.aria", "circle-play", "is-watching")
            val dropped = WatchlistItemAction(WatchlistMove.DROPPED.param, "watchlist.action.dropped", "watchlist.action.dropped.aria", "circle-x", "is-dropped")
            val remove = WatchlistItemAction(WatchlistMove.REMOVE.param, "watchlist.action.remove", "watchlist.action.remove.aria.${status.apiName}", "x", "is-remove")
            return when (status) {
                WatchStatus.WANT_TO_WATCH -> listOfNotNull(seen.takeIf { !upcoming }, watching.takeIf { isTv && !upcoming }, remove)
                WatchStatus.WATCHING -> listOfNotNull(seen.takeIf { !upcoming }, dropped.takeIf { isTv }, remove)
                WatchStatus.WATCHED -> listOfNotNull(remove.takeIf { !rated })
                WatchStatus.DROPPED -> listOfNotNull(seen.takeIf { !upcoming }, watching.takeIf { isTv && !upcoming }, remove)
            }
        }

        /** Every tile of a tab has the same number of slots, so the buttons line up across the grid. */
        fun slotsFor(status: WatchStatus): Int = if (status == WatchStatus.WATCHED) 1 else 3

        /** What fills the slots no button can: why Seen isn't offered (not out yet), or why Remove isn't (rated). */
        fun noteFor(status: WatchStatus, upcoming: Boolean, rated: Boolean, hasDate: Boolean): String? = when {
            status == WatchStatus.WATCHED -> if (rated) "watchlist.note.rated" else null
            upcoming -> if (hasDate) "watchlist.note.upcoming" else "watchlist.note.upcoming.soon"
            else -> null
        }
    }
}
