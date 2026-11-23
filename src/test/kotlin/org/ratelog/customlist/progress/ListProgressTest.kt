package org.ratelog.customlist.progress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus

class ListProgressTest {

    @Test
    fun `a movie is seen when rated or Watched`() {
        val movie = MediaType.movie
        assertTrue(ViewerMark(WatchStatus.WATCHED, null, rated = false).seen(movie))
        assertTrue(ViewerMark(null, 8.0, rated = true).seen(movie))
        assertTrue(ViewerMark(WatchStatus.DROPPED, 4.0, rated = true).seen(movie), "a rating counts even when Dropped")
        assertTrue(ViewerMark(WatchStatus.WATCHING, 7.0, rated = true).seen(movie))
    }

    @Test
    fun `a show is seen when Watched, or rated with no status, and its status wins over rated seasons`() {
        val tv = MediaType.tvshow
        assertTrue(ViewerMark(WatchStatus.WATCHED, null, rated = false).seen(tv))
        assertTrue(ViewerMark(WatchStatus.WATCHED, 8.0, rated = true).seen(tv))
        assertTrue(ViewerMark(null, 8.0, rated = true).seen(tv), "rated before statuses existed")
        assertFalse(ViewerMark(WatchStatus.WATCHING, 7.0, rated = true).seen(tv), "one rated season of five: still watching")
        assertFalse(ViewerMark(WatchStatus.DROPPED, 4.0, rated = true).seen(tv), "dropped")
        assertFalse(ViewerMark(WatchStatus.WANT_TO_WATCH, 9.0, rated = true).seen(tv), "back on the watchlist")
    }

    @Test
    fun `Watching, Want to watch, Dropped and nothing are not seen`() {
        MediaType.entries.forEach { type ->
            assertFalse(ViewerMark(WatchStatus.WATCHING, null, rated = false).seen(type))
            assertFalse(ViewerMark(WatchStatus.WANT_TO_WATCH, null, rated = false).seen(type))
            assertFalse(ViewerMark(WatchStatus.DROPPED, null, rated = false).seen(type))
            assertFalse(ViewerMark.NONE.seen(type))
        }
    }

    @Test
    fun `only unseen titles without any status are addable`() {
        fun entry(status: WatchStatus?, seen: Boolean) =
            ListProgressEntry(CustomListItem.Id(1), MediaType.movie, 1, 1, status, null, seen)

        assertTrue(entry(null, seen = false).addable)
        assertFalse(entry(WatchStatus.WANT_TO_WATCH, seen = false).addable)
        assertFalse(entry(WatchStatus.WATCHING, seen = false).addable)
        assertFalse(entry(WatchStatus.DROPPED, seen = false).addable)
        assertFalse(entry(WatchStatus.WATCHED, seen = true).addable)
        assertFalse(entry(null, seen = true).addable, "rated without a status")
    }

    @Test
    fun `filter parses the show parameter and falls back to All`() {
        assertEquals(ListFilter.ALL, ListFilter.parse(null))
        assertEquals(ListFilter.ALL, ListFilter.parse(""))
        assertEquals(ListFilter.ALL, ListFilter.parse("all"))
        assertEquals(ListFilter.ALL, ListFilter.parse("<script>"))
        assertEquals(ListFilter.UNSEEN, ListFilter.parse("unseen"))
        assertEquals(ListFilter.UNSEEN, ListFilter.parse(" Not-Seen "))
        assertEquals(ListFilter.UNSEEN, ListFilter.parse("unwatched"))
        assertEquals(ListFilter.SEEN, ListFilter.parse("SEEN"))
        assertEquals(ListFilter.SEEN, ListFilter.parse("watched"))
    }

    @Test
    fun `ranks follow list order over the whole list, with no gaps after removals`() {
        // Positions 1, 2, 5, 9 (titles 3, 4, 6-8 were removed): ranks are 1-4.
        val list = listOf(
            item(id = 10, mediaId = 100, position = 5),
            item(id = 11, mediaId = 101, position = 1),
            item(id = 12, mediaId = 102, position = 9),
            item(id = 13, mediaId = 103, position = 2),
        ).inList()

        val progress = ListProgress.of(list, emptyMap())

        assertEquals(listOf(101L, 103L, 100L, 102L), progress.entries.map { it.mediaId })
        assertEquals(listOf(1, 2, 3, 4), progress.entries.map { it.rank })
    }

    @Test
    fun `equal positions are ordered by item id`() {
        val list = listOf(item(id = 21, mediaId = 1, position = 1), item(id = 20, mediaId = 2, position = 1)).inList()

        assertEquals(listOf(2L, 1L), ListProgress.of(list, emptyMap()).entries.map { it.mediaId })
    }

    @Test
    fun `filtering keeps the original ranks and counts`() {
        val list = (1..5).map { item(id = it.toLong(), mediaId = it.toLong(), position = it) }.inList()
        val marks = mapOf(
            (MediaType.movie to 2L) to ViewerMark(WatchStatus.WATCHED, null, rated = false),
            (MediaType.movie to 4L) to ViewerMark(null, 9.0, rated = true),
            (MediaType.movie to 5L) to ViewerMark(WatchStatus.WATCHING, null, rated = false),
        )

        val progress = ListProgress.of(list, marks)

        assertEquals(5, progress.total)
        assertEquals(2, progress.seenCount)
        assertEquals(3, progress.unseenCount)
        assertEquals(2, progress.addableCount, "1 and 3 have no status; 5 is Watching")
        assertEquals(listOf(2, 4), progress.visible(ListFilter.SEEN).map { it.rank })
        assertEquals(listOf(1, 3, 5), progress.visible(ListFilter.UNSEEN).map { it.rank })
        assertEquals(listOf(1, 2, 3, 4, 5), progress.visible(ListFilter.ALL).map { it.rank })
        assertEquals(3, progress.count(ListFilter.UNSEEN))
        assertEquals(9.0, progress.entries[3].score)
    }

    @Test
    fun `a movie and a show with the same local id are told apart`() {
        val list = listOf(
            item(id = 1, mediaId = 7, position = 1, type = MediaType.movie),
            item(id = 2, mediaId = 7, position = 2, type = MediaType.tvshow),
        ).inList()
        val marks = mapOf((MediaType.tvshow to 7L) to ViewerMark(WatchStatus.WATCHED, null, rated = false))

        val progress = ListProgress.of(list, marks)

        assertEquals(listOf(false, true), progress.entries.map { it.seen })
    }

    private fun item(id: Long, mediaId: Long, position: Int, type: MediaType = MediaType.movie) =
        CustomListItem(CustomListItem.Id(id), LIST_ID, mediaId, type, position, 0L)

    private fun List<CustomListItem>.inList() =
        CustomList(LIST_ID, User.Id(1), ListName("Top"), isPublic = true, createdAtEpochMs = 0L, items = this)

    companion object {
        private val LIST_ID = CustomList.Id(1)
    }
}
