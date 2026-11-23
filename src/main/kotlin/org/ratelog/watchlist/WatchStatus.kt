package org.ratelog.watchlist

import org.ratelog.MediaType
import org.ratelog.user.User

/**
 * A user's own progress on a movie or show. Having no status is modelled as "no row", not as an entry here.
 * This is separate from following a title, which only drives premiere tracking.
 */
enum class WatchStatus(val apiName: String, val icon: String) {
    WANT_TO_WATCH("want_to_watch", "bookmark"),
    WATCHING("watching", "eye"),
    WATCHED("watched", "circle-check"),
    DROPPED("dropped", "circle-x");

    /** messages.properties key of the label. */
    val messageKey: String get() = "status.$apiName"

    /** Statuses that rating the title moves to [WATCHED]. */
    val isUnfinished: Boolean get() = this == WANT_TO_WATCH || this == WATCHING

    companion object {
        val DEFAULT_TAB = WANT_TO_WATCH

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
