package org.ratelog.watchlist.seen

import org.ratelog.MediaType
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate
import java.util.Locale

/**
 * The quick actions on a poster tile (home rows, Discover rows and grid, search results, list pages), rendered by
 * `templates/fragments/tile-actions.html :: actions` and kept in sync in place by `static/tile-actions.js`.
 * Top-right of the poster, stacked: the watchlist bookmark (or a Watching / Dropped badge), then the Seen eye.
 * Upcoming titles get no Seen toggle (you can't have seen them) and an "Upcoming · Dec 18" badge instead.
 * Bottom-right (fork, tile rating): Add to list, then Rate (★, or "★ 8.5" once rated). Upcoming titles get no ★
 * and their list button moves up into the empty Seen slot, clear of the Upcoming badge.
 */
data class TileActionsView(
    val tmdbId: Int,
    /** `movie` or `tv`: what POST /watchlist/toggle and POST /seen/toggle expect. */
    val type: String,
    /** Api name of the viewer's status, or null. */
    val status: String?,
    val onWatchlist: Boolean,
    /** The bookmark is rendered either way (hidden when false) so the page can show it again after un-seeing. */
    val canToggleWatchlist: Boolean,
    val seen: Boolean,
    /** Rated: the eye shows seen and tapping it only explains why it can't be cleared. */
    val seenLocked: Boolean,
    val upcoming: Boolean,
    /** Short date for the Upcoming badge ("Dec 18"), null when not upcoming. */
    val upcomingDate: String?,
    /** Watching / Dropped (or Watched on a title with no Seen toggle): shown where the bookmark would be. */
    val badgeIcon: String?,
    val badgeKey: String?,
    /** The viewer's score: a movie's rating, a show's average over its rated seasons. Null when unrated. */
    val score: Double? = null,
    /**
     * A detailed (five-category) movie rating, when the page already knows it (the Ratings tab). Elsewhere the
     * fragment asks the page's `tileMarks` (TileMarks), which looks it up once per page.
     */
    val ratingDetailed: Boolean = false,
) {
    val canToggleSeen: Boolean get() = !upcoming

    /** ★ on every released title: movies open the quick-rating picker (tile-rate.js), shows link to their seasons. */
    val canRate: Boolean get() = !upcoming

    val isMovie: Boolean get() = type == "movie"

    /** `movie:603`: the same key as the stack's `data-key`, every copy of a title on the page shares it. */
    val key: String get() = "$type:$tmdbId"

    /** "8.5": one decimal, like the other score badges on posters ("10", not "10.0", so the chip fits small tiles). */
    val scoreLabel: String? get() = score?.let(::tileScoreLabel)

    val titleHref: String get() = if (isMovie) "/movie/$tmdbId" else "/tv/$tmdbId"

    /**
     * Where ★ goes without JS (and on a middle-click): a movie's page opens its Rate / Edit rating modal (`?rate=1`,
     * tile-rate.js), a show's page scrolls to its seasons (shows are rated per season).
     */
    val rateHref: String get() = if (isMovie) "$titleHref?rate=1" else "$titleHref#seasons"

    /** Where the list button goes without JS: the title page, which opens its add-to-list sheet (`?lists=1`). */
    val listsHref: String get() = "$titleHref?lists=1"

    /** Tooltip / label key for ★ (`{0}` is [scoreLabel]). */
    val rateLabelKey: String get() = when {
        !isMovie && score != null -> "tile.rate.on.tv"
        !isMovie -> "tile.rate.seasons"
        score != null -> "tile.rate.on"
        else -> "tile.rate.mark"
    }

    companion object {
        fun of(
            tmdbId: Int,
            mediaType: MediaType,
            status: WatchStatus?,
            rated: Boolean,
            releaseDate: LocalDate?,
            today: LocalDate,
            locale: Locale,
            score: Double? = null,
            detailed: Boolean = false,
        ): TileActionsView {
            val state = SeenState(status, rated, isTv = mediaType == MediaType.tvshow)
            val upcoming = ReleaseRules.isUpcoming(releaseDate, today)
            val badge = status?.takeIf {
                it == WatchStatus.WATCHING || it == WatchStatus.DROPPED || (it == WatchStatus.WATCHED && upcoming)
            }
            return TileActionsView(
                tmdbId = tmdbId,
                type = if (mediaType == MediaType.movie) "movie" else "tv",
                status = status?.apiName,
                onWatchlist = state.onWatchlist,
                canToggleWatchlist = state.canToggleWatchlist,
                seen = state.seen,
                seenLocked = state.seenLocked,
                upcoming = upcoming,
                upcomingDate = if (upcoming && releaseDate != null) ReleaseRules.shortDate(releaseDate, today, locale) else null,
                badgeIcon = badge?.icon,
                badgeKey = badge?.messageKey,
                score = score,
                ratingDetailed = detailed && score != null && mediaType == MediaType.movie,
            )
        }
    }
}

/** What POST /seen/toggle and POST /watchlist/toggle answer to `fetch` (header `X-Requested-With: fetch`). */
data class TileStateResponse(
    val ok: Boolean,
    val status: String?,
    val seen: Boolean,
    val seenLocked: Boolean,
    val onWatchlist: Boolean,
    val canToggleWatchlist: Boolean,
    /** +1 / -1 / 0: how "seen" changed, so list pages can update "You've seen 9 of 25" without a reload. */
    val seenDelta: Int,
    /** Localized, for the toast. */
    val message: String?,
) {
    companion object {
        fun of(state: SeenState, seenDelta: Int, message: String?, ok: Boolean = true) = TileStateResponse(
            ok = ok,
            status = state.status?.apiName,
            seen = state.seen,
            seenLocked = state.seenLocked,
            onWatchlist = state.onWatchlist,
            canToggleWatchlist = state.canToggleWatchlist,
            seenDelta = seenDelta,
            message = message,
        )
    }
}

/** A poster's score chip: one decimal ("8.5", "7.0"), except a perfect "10". */
fun tileScoreLabel(score: Double): String {
    val label = String.format(Locale.ROOT, "%.1f", score)
    return if (label == "10.0") "10" else label
}
