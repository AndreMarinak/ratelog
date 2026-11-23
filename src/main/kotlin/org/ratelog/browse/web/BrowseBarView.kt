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
)

data class BarLink(
    val label: String,
    val href: String,
    val active: Boolean,
    val hint: String? = null,
    val logoUrl: String? = null,
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
    /** Rows show the one-click bookmark only when it can't downgrade a status (none yet, or Want to watch). */
    val canToggleWatchlist: Boolean get() = status == null || onWatchlist
}
