package org.ratelog.customlist.progress

import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.movie.Movie
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryListProgressRepository
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.RatingFactory
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus

/** In-memory lists, titles, statuses and ratings wired the way the app wires them, for list progress tests. */
class ListProgressFixture {
    val lists = InMemoryCustomListRepository()
    val movies = InMemoryMovieRepository()
    val shows = InMemoryTvShowRepository()
    val statuses = InMemoryMediaStatusRepository()
    val ratings = InMemoryRatingRepository()
    val tvRatings = InMemoryTvRatingRepository()
    val progressRepository = InMemoryListProgressRepository(lists, statuses, ratings, tvRatings)
    val handler = GetListProgressHandler(lists, progressRepository)

    fun list(owner: User.Id, isPublic: Boolean = true, name: String = "Best Disney Movies - Top 50"): CustomList =
        lists.save(CustomList(null, owner, ListName(name), isPublic, createdAtEpochMs = 1_000L))

    fun movie(title: String): Movie = movies.save(MovieFactory.aMovie(originalTitle = title, releaseYear = 2000))

    fun show(name: String): TvShow = shows.save(TvShowFactory.aTvShow(originalName = name))

    /** Appends titles in order, positions 1..n like the app does. */
    fun add(list: CustomList, vararg titles: Any): List<CustomListItem> {
        val start = (lists.findById(list.id!!)!!.items.maxOfOrNull { it.position } ?: 0) + 1
        return titles.mapIndexed { index, title ->
            val (type, mediaId) = when (title) {
                is Movie -> MediaType.movie to title.id!!.value
                is TvShow -> MediaType.tvshow to title.id!!.value
                else -> error("not a title: $title")
            }
            lists.addItem(CustomListItem(null, list.id, mediaId, type, start + index, addedAtEpochMs = 2_000L))
        }
    }

    fun status(user: User.Id, movie: Movie, status: WatchStatus, at: Long = 5L) =
        statuses.save(MediaStatus(user, MediaType.movie, movie.id!!.value, status, at))

    fun status(user: User.Id, show: TvShow, status: WatchStatus, at: Long = 5L) =
        statuses.save(MediaStatus(user, MediaType.tvshow, show.id!!.value, status, at))

    fun statusOf(user: User.Id, movie: Movie): WatchStatus? = statuses.effective(user, MediaType.movie, movie.id!!.value)

    fun statusOf(user: User.Id, show: TvShow): WatchStatus? = statuses.effective(user, MediaType.tvshow, show.id!!.value)

    /** Saved straight to the rating fake, so (unlike the app) it does not auto-set Watched: a "legacy" rating. */
    fun rate(user: User.Id, movie: Movie, score: Double) =
        ratings.save(RatingFactory.aQuickRating(movieId = movie.id!!, userId = user, score = score))

    fun rate(user: User.Id, show: TvShow, score: Double) =
        tvRatings.save(
            TvRatingFactory.aTvRating(
                tvShowId = show.id!!,
                userId = user,
                seasonRatings = listOf(TvRatingFactory.aQuickSeasonRating(tvShowId = show.id!!, seasonNumber = 1, userId = user, score = score)),
            )
        )
}
