package org.ratelog.watchlist

import org.ratelog.MediaType
import org.ratelog.user.User

/**
 * A user's own progress on a movie or show. Having no status is modelled as "no row", not as an entry here.
 * This is separate from following a title, which only drives premiere tracking.
 *
 * Fork (2026-11-24): [WANT_TO_WATCH] is no longer stored as a status. "On the watchlist" is its own table
 * ([WatchlistEntry]), so a Watched title can be on the watchlist too. The value stays for the watchlist page's To watch
 * tab and the API, where a title only on the watchlist still reports `want_to_watch` ([effective]).
 */
enum class WatchStatus(val apiName: String, val icon: String) {
    // Fork: the eye means "Seen" (the Letterboxd-style toggle), so Watching uses a play icon.
    WANT_TO_WATCH("want_to_watch", "bookmark"),
    WATCHING("watching", "circle-play"),
    WATCHED("watched", "eye"),
    DROPPED("dropped", "circle-x");

    /** messages.properties key of the label. */
    val messageKey: String get() = "status.$apiName"

    /** Statuses that rating the title moves to [WATCHED]. */
    val isUnfinished: Boolean get() = this == WANT_TO_WATCH || this == WATCHING

    companion object {
        val DEFAULT_TAB = WANT_TO_WATCH

        /**
         * The one status the API and older readers see: the progress status, or [WANT_TO_WATCH] for a title that is only
         * on the watchlist. A Watched title on the watchlist stays `watched` (with `onWatchlist: true`).
         */
        fun effective(progress: WatchStatus?, onWatchlist: Boolean): WatchStatus? =
            progress ?: WANT_TO_WATCH.takeIf { onWatchlist }

        private val ALIASES = mapOf(
            "want" to WANT_TO_WATCH,
            "watchlist" to WANT_TO_WATCH,
            "plan_to_watch" to WANT_TO_WATCH,
            "seen" to WATCHED,
            "completed" to WATCHED,
        )

        /** Accepts api names (`want_to_watch`), enum names, `-`/space separators and a few aliases. Null if unknown. */
        fun parse(value: String?): WatchStatus? {
            val key = value?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')
            if (key.isNullOrEmpty()) return null
            return entries.find { it.apiName == key } ?: ALIASES[key]
        }
    }
}

/** The status one user has set on one title. Unique per user + title. */
data class MediaStatus(
    val userId: User.Id,
    val mediaType: MediaType,
    val mediaId: Long,
    val status: WatchStatus,
    val updatedAtEpochMs: Long,
)
