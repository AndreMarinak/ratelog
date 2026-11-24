package org.ratelog.test

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.home.HomeRepository
import org.ratelog.home.HomeTitle
import org.ratelog.home.TitleMark
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Canned answers per user; the real queries live in HomeDAO. Titles are returned in the order given. */
class InMemoryHomeRepository : HomeRepository {
    private val byStatus = ConcurrentHashMap<Pair<User.Id, WatchStatus>, List<HomeTitle>>()
    private val recentlyRated = ConcurrentHashMap<User.Id, List<HomeTitle>>()
    private val marks = ConcurrentHashMap<Triple<User.Id, MediaType, Int>, TitleMark>()

    /** findMarks calls, to check marks are looked up in one go per media type. */
    val markQueries = AtomicInteger()

    /** Want to watch: the watchlist ("Up next"), titles with no status and on the watchlist (fork, 2026-11-24). */
    fun setStatusTitles(userId: User.Id, status: WatchStatus, titles: List<HomeTitle>) {
        byStatus[userId to status] = titles.map {
            if (status == WatchStatus.WANT_TO_WATCH) it.copy(onWatchlist = true) else it.copy(status = status)
        }
    }

    fun setRecentlyRated(userId: User.Id, titles: List<HomeTitle>) {
        recentlyRated[userId] = titles
    }

    fun setMark(userId: User.Id, mediaType: MediaType, tmdbId: Int, mark: TitleMark) {
        marks[Triple(userId, mediaType, tmdbId)] = mark
    }

    override fun findByStatus(userId: User.Id, status: WatchStatus, lang: Lang, limit: Int): List<HomeTitle> =
        byStatus[userId to status].orEmpty().take(limit)

    override fun findWatchlist(userId: User.Id, lang: Lang, limit: Int): List<HomeTitle> =
        byStatus[userId to WatchStatus.WANT_TO_WATCH].orEmpty().take(limit)

    override fun findRecentlyRated(userId: User.Id, lang: Lang, limit: Int): List<HomeTitle> =
        recentlyRated[userId].orEmpty().take(limit)

    override fun findMarks(userId: User.Id, mediaType: MediaType, tmdbIds: Collection<Int>): Map<Int, TitleMark> {
        markQueries.incrementAndGet()
        return tmdbIds.mapNotNull { id -> marks[Triple(userId, mediaType, id)]?.let { id to it } }.toMap()
    }
}
