package org.ratelog.watchlist.seen

import org.ratelog.MediaType
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate
import java.util.Locale

/**
 * The title page's actions (`templates/fragments/title-actions.html`): Seen, Watchlist, Rate and Add to list as four
 * equal toggles, then the small extras: Watching / Dropped for shows and the follow bell (only where it does something).
 */
data class TitleActionsView(
    val isTv: Boolean,
    /** `movie` / `tvshow`: the `mediaType` of POST /status. */
    val mediaType: String,
    /** Local id (movies.id / tv.id). */
    val mediaId: Long,
    val tmdbId: Int,
    /** `movie` / `tv`: the `type` of POST /seen/toggle. */
    val tileType: String,
    /** The page itself, sent as `back` so every form returns here. */
    val back: String,
    /** The progress status (Watching / Watched / Dropped), never Want to watch. */
    val status: WatchStatus?,
    val seen: Boolean,
    val seenLocked: Boolean,
    /**
     * Fork (2026-11-24): independent of Seen, so the Watchlist toggle works on seen and rated titles too and never asks:
     * adding a seen title keeps it seen (it used to ask first, since the one status couldn't be both).
     */
    val onWatchlist: Boolean,
    val upcoming: Boolean,
    /** "Dec 18, 2026" (next to the year), or null when upcoming without a date (or not upcoming). */
    val upcomingDate: String?,
    /** "Dec 18": the tile that stands in for Seen ("Out Dec 18"). */
    val upcomingShortDate: String?,
    /** Shows only: the compact Watching / Dropped control. */
    val watching: Boolean,
    val dropped: Boolean,
    /** A movie marked Watching or Dropped (older data, or set through the API): shown with a way to clear it. */
    val movieExtraStatus: WatchStatus?,
    val showFollow: Boolean,
    val followed: Boolean,
) {
    val canToggleSeen: Boolean get() = !upcoming

    companion object {
        fun of(
            type: MediaType,
            mediaId: Long,
            tmdbId: Int,
            status: WatchStatus?,
            rated: Boolean,
            releaseDate: LocalDate?,
            tmdbStatus: String?,
            followed: Boolean,
            today: LocalDate,
            locale: Locale,
            onWatchlist: Boolean,
        ): TitleActionsView {
            val state = SeenState(status, rated, isTv = type == MediaType.tvshow, onWatchlist = onWatchlist)
            val upcoming = ReleaseRules.isUpcoming(type, releaseDate, tmdbStatus, today)
            val isTv = type == MediaType.tvshow
            return TitleActionsView(
                isTv = isTv,
                mediaType = type.name,
                mediaId = mediaId,
                tmdbId = tmdbId,
                tileType = if (isTv) "tv" else "movie",
                back = if (isTv) "/tv/$tmdbId" else "/movie/$tmdbId",
                status = status,
                seen = state.seen,
                seenLocked = state.seenLocked,
                onWatchlist = state.onWatchlist,
                upcoming = upcoming,
                upcomingDate = if (upcoming) releaseDate?.let { ReleaseRules.longDate(it, locale) } else null,
                upcomingShortDate = if (upcoming) releaseDate?.let { ReleaseRules.shortDate(it, today, locale) } else null,
                watching = isTv && status == WatchStatus.WATCHING,
                dropped = isTv && status == WatchStatus.DROPPED,
                movieExtraStatus = status?.takeIf { !isTv && (it == WatchStatus.WATCHING || it == WatchStatus.DROPPED) },
                showFollow = ReleaseRules.showFollow(type, releaseDate, tmdbStatus, today, followed),
                followed = followed,
            )
        }
    }
}
