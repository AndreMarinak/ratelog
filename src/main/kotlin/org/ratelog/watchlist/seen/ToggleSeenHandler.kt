package org.ratelog.watchlist.seen

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.MediaType
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieRepository
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/** The viewer's status and whether they rated the title (a show: at least one rated season). */
@Component
class SeenStateReader(
    private val mediaStatusRepository: MediaStatusRepository,
    private val ratingRepository: RatingRepository,
    private val tvRatingRepository: TvRatingRepository,
) {
    fun read(userId: User.Id, mediaType: MediaType, mediaId: Long): SeenState = SeenState(
        status = mediaStatusRepository.find(userId, mediaType, mediaId)?.status,
        rated = when (mediaType) {
            MediaType.movie -> ratingRepository.findByMovieIdAndUserId(Movie.Id(mediaId), userId) != null
            MediaType.tvshow -> tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(mediaId), userId)
                ?.seasonRatings?.isNotEmpty() == true
        },
        isTv = mediaType == MediaType.tvshow,
    )
}

data class ToggleSeen(
    val userId: User.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id), not the TMDB id. */
    val mediaId: Long,
)

enum class ToggleSeenOutcome(val messageKey: String) {
    /** Not seen -> Watched (from no status, Want to watch, Watching or Dropped). */
    MARKED_SEEN("seen.marked"),

    /** Watched (and not rated) -> no status. */
    UNMARKED("seen.unmarked"),

    /** A show with rated seasons: Watched -> Watching (you've clearly started it). */
    UNMARKED_TO_WATCHING("seen.unmarked.watching"),

    /** Rated, so it stays seen: nothing changed. Un-seeing would mean deleting the rating, which this never does. */
    LOCKED_BY_RATING("seen.locked.rated"),
}

data class ToggleSeenResult(
    val outcome: ToggleSeenOutcome,
    val previous: WatchStatus?,
    /** The state after the toggle. */
    val state: SeenState,
) {
    /** +1 / -1 / 0: how the title's "seen" changed, for list progress counters. */
    val seenDelta: Int get() = when (outcome) {
        ToggleSeenOutcome.MARKED_SEEN -> 1
        ToggleSeenOutcome.UNMARKED, ToggleSeenOutcome.UNMARKED_TO_WATCHING -> -1
        ToggleSeenOutcome.LOCKED_BY_RATING -> 0
    }
}

sealed interface ToggleSeenError {
    data object MediaNotFound : ToggleSeenError

    /** Released after today: you can't have seen it yet. Clearing a Watched status is still allowed. */
    data object NotReleased : ToggleSeenError
}

/**
 * The one-tap Seen toggle (title pages, poster tiles, list tick-off mode). "Seen" is [SeenState.seen].
 * - Not seen: sets Watched, replacing Want to watch / Watching / Dropped (refused while the title isn't out).
 * - Seen, not rated: clears the status (no status at all, not back to what it was before).
 * - A rated movie: locked. It stays seen and nothing changes; the caller explains that the rating would have to go.
 * - A seen show with rated seasons: back to Watching.
 */
@Component
class ToggleSeenHandler(
    private val mediaStatusRepository: MediaStatusRepository,
    private val movieRepository: MovieRepository,
    private val tvShowRepository: TvShowRepository,
    private val seenStateReader: SeenStateReader,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @Transactional
    fun handle(command: ToggleSeen): Either<ToggleSeenError, ToggleSeenResult> = either {
        val release = when (command.mediaType) {
            MediaType.movie -> movieRepository.findById(Movie.Id(command.mediaId))?.let { it.releaseDate to it.status?.value }
            MediaType.tvshow -> tvShowRepository.findById(TvShow.Id(command.mediaId))?.let { it.firstAirDate to it.status?.value }
        } ?: raise(ToggleSeenError.MediaNotFound)

        val before = seenStateReader.read(command.userId, command.mediaType, command.mediaId)
        when {
            before.seenLocked -> ToggleSeenResult(ToggleSeenOutcome.LOCKED_BY_RATING, before.status, before)

            before.seen && before.isTv && before.rated -> {
                save(command, WatchStatus.WATCHING)
                ToggleSeenResult(ToggleSeenOutcome.UNMARKED_TO_WATCHING, before.status, before.copy(status = WatchStatus.WATCHING))
            }

            before.seen -> {
                mediaStatusRepository.delete(command.userId, command.mediaType, command.mediaId)
                ToggleSeenResult(ToggleSeenOutcome.UNMARKED, before.status, before.copy(status = null))
            }

            else -> {
                val today = LocalDate.now(clock)
                if (ReleaseRules.isUpcoming(command.mediaType, release.first, release.second, today)) raise(ToggleSeenError.NotReleased)
                save(command, WatchStatus.WATCHED)
                ToggleSeenResult(ToggleSeenOutcome.MARKED_SEEN, before.status, before.copy(status = WatchStatus.WATCHED))
            }
        }
    }

    private fun save(command: ToggleSeen, status: WatchStatus) = MediaStatus(
        userId = command.userId,
        mediaType = command.mediaType,
        mediaId = command.mediaId,
        status = status,
        updatedAtEpochMs = clock.millis(),
    ).let(mediaStatusRepository::save)
}
