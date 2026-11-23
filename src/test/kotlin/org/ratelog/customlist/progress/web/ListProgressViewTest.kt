package org.ratelog.customlist.progress.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.customlist.progress.ListProgress
import org.ratelog.customlist.progress.ListProgressEntry
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus

class ListProgressViewTest {

    // 4 titles: #1 Watched, #2 nothing, #3 rated 7.25 (no status), #4 Dropped.
    private val progress = ListProgress(
        listId = CustomList.Id(7),
        ownerId = User.Id(1),
        entries = listOf(
            entry(1, WatchStatus.WATCHED, null, seen = true),
            entry(2, null, null, seen = false),
            entry(3, null, 7.25, seen = true),
            entry(4, WatchStatus.DROPPED, null, seen = false),
        ),
    )

    @Test
    fun `tabs link with the show parameter, carry counts and mark the active one`() {
        val view = progress.toView(ListFilter.UNSEEN)

        assertEquals(listOf("/list/7", "/list/7?show=unseen", "/list/7?show=seen"), view.tabs.map { it.href })
        assertEquals(listOf(4, 2, 2), view.tabs.map { it.count })
        assertEquals(listOf(false, true, false), view.tabs.map { it.active })
        assertEquals(listOf("list.progress.filter.all", "list.progress.filter.unseen", "list.progress.filter.seen"), view.tabs.map { it.labelKey })
        assertEquals("unseen", view.filterParam)
    }

    @Test
    fun `progress line and button numbers`() {
        val view = progress.toView(ListFilter.ALL)

        assertEquals(2, view.seen)
        assertEquals(4, view.total)
        assertEquals(50, view.percent)
        assertEquals(1, view.addableCount)
        assertEquals("/list/7/watchlist-unseen", view.addHref)
        assertNull(view.filterParam)
    }

    @Test
    fun `the filter hides items but never renumbers them`() {
        val view = progress.toView(ListFilter.UNSEEN)

        assertFalse(view.shows(101))
        assertTrue(view.shows(102))
        assertFalse(view.shows(103))
        assertTrue(view.shows(104))
        assertEquals(2, view.entry(102)!!.rank)
        assertEquals(4, view.entry(104)!!.rank)
        assertEquals(2, view.visibleCount)
    }

    @Test
    fun `badges carry the status icon, label, modifier and the viewer's score`() {
        val view = progress.toView(ListFilter.ALL)

        val watched = view.entry(101)!!
        assertEquals("circle-check", watched.statusIcon)
        assertEquals("status.watched", watched.statusKey)
        assertEquals("is-watched", watched.statusClass)
        assertNull(watched.scoreLabel)

        assertEquals("7.3", view.entry(103)!!.scoreLabel)
        assertNull(view.entry(103)!!.statusIcon)
        assertEquals("is-dropped", view.entry(104)!!.statusClass)
        assertNull(view.entry(102)!!.statusIcon)
    }

    @Test
    fun `want to watch gets its own badge`() {
        val view = ListProgress(CustomList.Id(1), User.Id(1), listOf(entry(1, WatchStatus.WANT_TO_WATCH, null, seen = false)))
            .toView(ListFilter.ALL)

        assertEquals("bookmark", view.entry(101)!!.statusIcon)
        assertEquals("is-want-to-watch", view.entry(101)!!.statusClass)
    }

    @Test
    fun `an item added after the progress was read only shows under All`() {
        assertTrue(progress.toView(ListFilter.ALL).shows(999))
        assertFalse(progress.toView(ListFilter.SEEN).shows(999))
        assertNull(progress.toView(ListFilter.ALL).entry(999))
    }

    @Test
    fun `empty filtered tabs explain themselves, an empty list does not`() {
        val allSeen = ListProgress(CustomList.Id(1), User.Id(1), listOf(entry(1, WatchStatus.WATCHED, null, seen = true)))
        assertEquals("list.progress.empty.unseen", allSeen.toView(ListFilter.UNSEEN).emptyKey)
        assertNull(allSeen.toView(ListFilter.SEEN).emptyKey)

        val noneSeen = ListProgress(CustomList.Id(1), User.Id(1), listOf(entry(1, null, null, seen = false)))
        assertEquals("list.progress.empty.seen", noneSeen.toView(ListFilter.SEEN).emptyKey)

        val empty = ListProgress(CustomList.Id(1), User.Id(1), emptyList()).toView(ListFilter.SEEN)
        assertNull(empty.emptyKey)
        assertEquals(0, empty.percent)
    }

    @Test
    fun `percent rounds down so 100 means everything`() {
        val entries = (1..3).map { entry(it, if (it < 3) WatchStatus.WATCHED else null, null, seen = it < 3) }
        assertEquals(66, ListProgress(CustomList.Id(1), User.Id(1), entries).toView(ListFilter.ALL).percent)
    }

    @Test
    fun `back links`() {
        assertEquals(ListBackLink("/lists", "list.back.lists", null), ListBackLink.LISTS)
        assertEquals(ListBackLink("/profile/3", "list.back.profile", "alice"), ListBackLink.profile(3, "alice"))
    }

    private fun entry(rank: Int, status: WatchStatus?, score: Double?, seen: Boolean) = ListProgressEntry(
        itemId = CustomListItem.Id(100L + rank),
        mediaType = MediaType.movie,
        mediaId = rank.toLong(),
        rank = rank,
        status = status,
        score = score,
        seen = seen,
    )
}
