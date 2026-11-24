package org.ratelog.test

import org.ratelog.movie.MovieRepository
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.rewatch.RewatchRepository
import org.ratelog.rewatch.delete.DeleteRewatchHandler
import org.ratelog.rewatch.log.LogRewatchHandler
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.entry.SetWatchlistHandler
import org.ratelog.watchlist.move.MoveWatchlistItemHandler
import org.ratelog.watchlist.seen.SeenStateReader
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import java.time.Clock

/** The status / watchlist handlers wired over in-memory fakes (fork, 2026-11-24: the watchlist has its own handler). */
object WatchlistHandlers {
    fun setWatchlist(
        statuses: MediaStatusRepository,
        movies: MovieRepository,
        shows: TvShowRepository,
        ratings: RatingRepository = InMemoryRatingRepository(),
        tvRatings: TvRatingRepository = InMemoryTvRatingRepository(),
        clock: Clock = Clock.systemUTC(),
    ) = SetWatchlistHandler(statuses, movies, shows, SeenStateReader(statuses, ratings, tvRatings), clock)

    fun setStatus(
        statuses: MediaStatusRepository,
        movies: MovieRepository,
        shows: TvShowRepository,
        ratings: RatingRepository = InMemoryRatingRepository(),
        tvRatings: TvRatingRepository = InMemoryTvRatingRepository(),
    ) = SetWatchStatusHandler(statuses, movies, shows, setWatchlist(statuses, movies, shows, ratings, tvRatings))

    /** Fork (2026-11-24, rewatch log): "Watched again" over the same fakes. */
    fun logRewatch(
        rewatches: RewatchRepository,
        statuses: MediaStatusRepository,
        movies: MovieRepository,
        shows: TvShowRepository,
        ratings: RatingRepository = InMemoryRatingRepository(),
        tvRatings: TvRatingRepository = InMemoryTvRatingRepository(),
        clock: Clock = Clock.systemUTC(),
    ) = LogRewatchHandler(rewatches, movies, shows, SeenStateReader(statuses, ratings, tvRatings), clock)

    fun move(
        statuses: MediaStatusRepository,
        movies: MovieRepository,
        shows: TvShowRepository,
        ratings: RatingRepository = InMemoryRatingRepository(),
        tvRatings: TvRatingRepository = InMemoryTvRatingRepository(),
        rewatches: RewatchRepository = InMemoryRewatchRepository(),
        clock: Clock = Clock.systemUTC(),
    ) = MoveWatchlistItemHandler(
        setStatus(statuses, movies, shows, ratings, tvRatings),
        setWatchlist(statuses, movies, shows, ratings, tvRatings, clock),
        statuses,
        logRewatch(rewatches, statuses, movies, shows, ratings, tvRatings, clock),
        DeleteRewatchHandler(rewatches),
    )
}
