package org.ratelog.home

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate

/** A poster tile on the home dashboard, with the viewer's own status and score when known. */
data class HomeTitle(
    val tmdbId: Int,
    val mediaType: MediaType,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val status: WatchStatus? = null,
    /** The viewer's own score (a show's is the average of its rated seasons). */
    val score: Double? = null,
    /** TMDB's date for TMDB rows ("Coming soon" sorts by it); the stored date for personal rows (Upcoming badge). */
    val releaseDate: LocalDate? = null,
    /** Fork (2026-11-24): on the viewer's watchlist (independent of [status], which is never Want to watch). */
    val onWatchlist: Boolean = status == WatchStatus.WANT_TO_WATCH,
)

/** What the viewer has done with a title: its status, its score, whether it's on their watchlist (any of them). */
data class TitleMark(val status: WatchStatus?, val score: Double?, val onWatchlist: Boolean = status == WatchStatus.WANT_TO_WATCH)

/** Read-only queries for the home dashboard's personal rows. Titles use `lang`, falling back to English, then the original title. */
interface HomeRepository {
    /** The user's titles with that status, most recently updated first. */
    fun findByStatus(userId: User.Id, status: WatchStatus, lang: Lang, limit: Int): List<HomeTitle>

    /** Fork (2026-11-24): the user's watchlist ("Up next"), seen titles (rewatches) included, most recently added first. */
    fun findWatchlist(userId: User.Id, lang: Lang, limit: Int): List<HomeTitle>

    /** The user's rated titles, newest rating first. A show counts once, at its latest season rating. */
    fun findRecentlyRated(userId: User.Id, lang: Lang, limit: Int): List<HomeTitle>

    /** The user's status, score and watchlist mark on these TMDB titles. Titles with none (or unknown to Ratelog) are left out. */
    fun findMarks(userId: User.Id, mediaType: MediaType, tmdbIds: Collection<Int>): Map<Int, TitleMark>
}
