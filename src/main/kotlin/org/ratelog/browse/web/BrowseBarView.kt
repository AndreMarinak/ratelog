package org.ratelog.browse.web

import org.ratelog.watchlist.seen.TileActionsView

/**
 * Everything `templates/fragments/browse-bar.html` renders, with labels already resolved.
 * Every option is a plain link to `/discover?...`, so the bar works without JavaScript.
 */
data class BrowseBarView(
    /** `movie` or `tv`. */
    val type: String,
    val typeLinks: List<BarLink>,
    val menus: List<BarMenu>,
    /** Null where the bar is only an entry point (e.g. included on the home page). */
    val sortMenu: BarMenu?,
    val chips: List<BarChip>,
    val clearHref: String?,
)

data class BarMenu(
    val id: String,
    val label: String,
    /** Lucide icon name. */
    val icon: String,
    /** The current choice, shown next to the label; null when the menu is at its default. */
    val value: String?,
    val sections: List<BarSection>,
    val note: String? = null,
) {
    val active: Boolean get() = value != null
}

data class BarSection(
    val heading: String?,
    val links: List<BarLink>,
    /** `list`, `grid`, `years`, `logos` or `toggles` (CSS layout of the links). */
    val layout: String = "list",
    /** Fork: scrolled into view when the menu re-opens after a pick (the Year menu's years of the chosen decade). */
    val reveal: Boolean = false,
    /** Fork: the links sit behind a "Show all" toggle with a search box (the Service menu's full list). */
    val more: BarMore? = null,
)

/** A collapsed part of a menu: a `<details>` (works without JS) whose search box browse-bar.js turns on. */
data class BarMore(
    val label: String,
    /** Rendered open, e.g. when it holds a chosen option. */
    val open: Boolean,
    val searchLabel: String,
    val noMatches: String,
)

data class BarLink(
    val label: String,
    val href: String,
    val active: Boolean,
    val hint: String? = null,
    val logoUrl: String? = null,
    /**
     * Fork: picking it re-opens the menu after the in-place swap (view-swap.js), for choices that are often refined
     * right away: a decade (then a year), a streaming service (then another).
     */
    val keepOpen: Boolean = false,
    /** Fork: a stable name for the option, so focus returns to it after the swap (`data-option`). */
    val option: String? = null,
    /** Fork: shows a checkbox (multi-select menus). */
    val checkbox: Boolean = false,
)

data class BarChip(
    val label: String,
    val removeHref: String,
    val removeLabel: String,
)

/** One poster on the Discover grid or in a Discover row. */
data class BrowseTileView(
    /** Stable id for de-duplicating appended pages, e.g. `movie-603`. */
    val key: String,
    val tmdbId: Int,
    /** `movie` or `tv`, as `POST /watchlist/toggle` expects. */
    val type: String,
    val href: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val initial: String,
    /** TMDB vote average, one decimal. */
    val tmdbScore: String?,
    /** The user's own score, one decimal. */
    val userScore: String?,
    /** The user's watch status (api name), drives the bookmark's toggle in browse.js. */
    val status: String?,
    /** "Want to watch": the bookmark button shows as set. */
    val onWatchlist: Boolean,
    /** Badge for the other statuses (Watching, Watched, Dropped); "Want to watch" is shown by the bookmark. */
    val statusIcon: String?,
    val statusLabel: String?,
    /** `w500` for 2x screens (`srcset`). */
    val posterUrl2x: String? = null,
    /** Fork: the Seen / Watchlist quick actions and the Upcoming badge (fragments/tile-actions.html). */
    val actions: TileActionsView? = null,
) {
    /** Rows show the one-click bookmark on any title but a Watching / Dropped one (fork, 2026-11-24: seen ones too). */
    val canToggleWatchlist: Boolean get() = status != "watching" && status != "dropped"
}
