package org.ratelog.watchlist.autowatched

import org.ratelog.MediaType
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

data class MarkWatchedOnRating(
    val userId: User.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id). */
    val mediaId: Long,
    /** Shows: every season the user has rated, after this save. */
    val ratedSeasons: Set<Int> = emptySet(),
    /** Fork: a show rated as a whole (one score, no seasons): it was seen, like a rated movie. */
    val wholeShow: Boolean = false,
)

/**
 * The seasons of a stored show that have aired: the title page's list (1 to the last season; season 0 / Specials is
 * never in it) minus the seasons at the end that haven't aired. The show only keeps the last season's air date and the
 * next future season date, so: the last season counts as not aired when its date is unknown or after today, and one
 * more season before it when a future season date (still after today) differs from it. TMDB only lists unaired
 * seasons at the end, and more than two of them are practically unheard of.
 */
object AiredSeasons {
    fun of(show: TvShow, today: LocalDate): Set<Int> {
        val last = show.lastSeasonNumber?.takeIf { it >= 1 } ?: return emptySet()
        val lastDate = show.lastSeasonAirDate
        val next = show.nextSeasonAirDate
        var unaired = 0
        if (lastDate == null || lastDate.isAfter(today)) unaired++
        if (next != null && next.isAfter(today) && next != lastDate) unaired++
        val lastAired = last - unaired
        return if (lastAired < 1) emptySet() else (1..lastAired).toSet()
    }
}

/**
 * Rating a title means it was (at least partly) seen:
 * - **Movies:** no status and Watching become Watched. Watched and Dropped are left as they are.
 * - **Shows** (rated per season): rating a season makes the show Watching; it becomes Watched only once every aired
 *   season ([AiredSeasons]) is rated. A Watched show is never downgraded and a Dropped one is never touched.
 * - **Shows rated as a whole** (fork, [MarkWatchedOnRating.wholeShow]): like a movie, no status and Watching
 *   become Watched; Dropped is left alone.
 * Deleting a rating never calls this (see AutoWatchedRatingRepositories).
 *
 * Fork (2026-11-24), the watchlist: when the status changes, a title that was put on the watchlist while unseen leaves it
 * (you've now seen it, or started it), as when Want to watch was a status. A rewatch (added while already seen,
 * [org.ratelog.watchlist.WatchlistEntry.rewatch]) stays: rating it again is why it was put there.
 */
@Component
class MarkWatchedOnRatingHandler(
    private val mediaStatusRepository: MediaStatusRepository,
    private val tvShowRepository: TvShowRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /** @return true if the status changed. */
    @Transactional
    fun handle(command: MarkWatchedOnRating): Boolean {
        val current = mediaStatusRepository.find(command.userId, command.mediaType, command.mediaId)?.status
        val next = when (command.mediaType) {
            MediaType.movie -> if (current == null || current.isUnfinished) WatchStatus.WATCHED else return false
            MediaType.tvshow -> {
                if (current == WatchStatus.WATCHED || current == WatchStatus.DROPPED) return false
                if (command.wholeShow) {
                    WatchStatus.WATCHED
                } else {
                    val aired = tvShowRepository.findById(TvShow.Id(command.mediaId))
                        ?.let { AiredSeasons.of(it, LocalDate.now(clock)) }
                        .orEmpty()
                    if (aired.isNotEmpty() && command.ratedSeasons.containsAll(aired)) WatchStatus.WATCHED else WatchStatus.WATCHING
                }
            }
        }
        if (next == current) return false

        MediaStatus(
            userId = command.userId,
            mediaType = command.mediaType,
            mediaId = command.mediaId,
            status = next,
            updatedAtEpochMs = clock.millis(),
        ).let(mediaStatusRepository::save)
        mediaStatusRepository.findWatchlistEntry(command.userId, command.mediaType, command.mediaId)
            ?.takeIf { !it.rewatch }
            ?.let { mediaStatusRepository.removeFromWatchlist(command.userId, command.mediaType, command.mediaId) }
        return true
    }
}
