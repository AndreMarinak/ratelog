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
)

/** What the viewer has done with a title: its status, its score, or both. */
data class TitleMark(val status: WatchStatus?, val score: Double?)

/** Read-only queries for the home dashboard's personal rows. Titles use `lang`, falling back to English, then the original title. */
interface HomeRepository {
    /** The user's titles with that status, most recently updated first. */
    fun findByStatus(userId: User.Id, status: WatchStatus, lang: Lang, limit: Int): List<HomeTitle>

    /** The user's rated titles, newest rating first. A show counts once, at its latest season rating. */
    fun findRecentlyRated(userId: User.Id, lang: Lang, limit: Int): List<HomeTitle>

    /** The user's status and score on these TMDB titles. Titles with neither (or unknown to Ratelog) are left out. */
    fun findMarks(userId: User.Id, mediaType: MediaType, tmdbIds: Collection<Int>): Map<Int, TitleMark>
}
