package org.ratelog.customlist.progress.markall

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.progress.GetListProgressError
import org.ratelog.customlist.progress.GetListProgressHandler
import org.ratelog.customlist.progress.ListProgress
import org.ratelog.customlist.progress.ListProgressEntry
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.SeenState
import org.ratelog.watchlist.seen.ToggleSeenError
import org.ratelog.watchlist.seen.ToggleSeenHandler
import org.ratelog.watchlist.seen.ToggleSeenOutcome
import org.ratelog.watchlist.seen.ToggleSeenResult

class MarkAllSeenHandlerTest {

    private val progressHandler = mock<GetListProgressHandler>()
    private val toggle = mock<ToggleSeenHandler>()
    private val handler = MarkAllSeenHandler(progressHandler, toggle)
    private val user = User.Id(7)
    private val list = CustomList.Id(3)

    private fun entry(id: Long, seen: Boolean, type: MediaType = MediaType.movie) =
        ListProgressEntry(CustomListItem.Id(id), type, mediaId = 100 + id, rank = id.toInt(), status = if (seen) WatchStatus.WATCHED else null,
            score = null, seen = seen)

    private fun marked() = ToggleSeenResult(ToggleSeenOutcome.MARKED_SEEN, null, SeenState(WatchStatus.WATCHED, rated = false)).right()

    @Test
    fun `marks every unseen title, leaves seen ones alone and counts titles that aren't out yet`() {
        val entries = listOf(entry(1, seen = true), entry(2, seen = false), entry(3, seen = false, type = MediaType.tvshow), entry(4, seen = false))
        whenever(progressHandler.handle(any())).thenReturn(ListProgress(list, User.Id(1), entries).right())
        whenever(toggle.handle(argThat { mediaId == 102L || mediaId == 103L })).thenReturn(marked())
        whenever(toggle.handle(argThat { mediaId == 104L })).thenReturn(ToggleSeenError.NotReleased.left())

        val result = handler.handle(MarkAllSeen(list, user))

        assertEquals(MarkAllSeenResult(marked = 2, notOut = 1).right(), result)
        verify(toggle, never()).handle(argThat { mediaId == 101L })
        verify(toggle, times(3)).handle(argThat { userId == user })
    }

    @Test
    fun `a list the viewer can't open changes nothing`() {
        whenever(progressHandler.handle(any())).thenReturn(GetListProgressError.NotAuthorized.left())

        assertEquals(GetListProgressError.NotAuthorized.left(), handler.handle(MarkAllSeen(list, user)))
        verify(toggle, never()).handle(any())
    }
}
