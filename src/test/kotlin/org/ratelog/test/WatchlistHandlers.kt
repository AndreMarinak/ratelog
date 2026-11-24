package org.ratelog.test

import org.ratelog.movie.MovieRepository
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.entry.SetWatchlistHandler
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
}
