package org.ratelog.customlist.progress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus

class GetListProgressHandlerTest {

    private val f = ListProgressFixture()
    private val owner = User.Id(1)
    private val viewer = User.Id(2)

    @Test
    fun `counts the viewer's seen titles and keeps list order`() {
        val list = f.list(owner)
        val (coco, up, moana, frozen) = listOf("Coco", "Up", "Moana", "Frozen").map(f::movie)
        f.add(list, coco, up, moana, frozen)
        f.status(viewer, coco, WatchStatus.WATCHED)
        f.rate(viewer, moana, 8.5)
        f.status(viewer, frozen, WatchStatus.WATCHING)

        val progress = f.handler.handle(GetListProgress(list.id!!, viewer)).getOrNull()!!

        assertEquals(4, progress.total)
        assertEquals(2, progress.seenCount)
        assertEquals(listOf(1, 2, 3, 4), progress.entries.map { it.rank })
        assertEquals(listOf(true, false, true, false), progress.entries.map { it.seen })
        assertEquals(listOf(WatchStatus.WATCHED, null, null, WatchStatus.WATCHING), progress.entries.map { it.status })
        assertEquals(8.5, progress.entries[2].score)
        assertEquals(1, progress.addableCount, "only Up has no status and no rating")
    }

    @Test
    fun `progress is the viewer's own, not the list owner's`() {
        val list = f.list(owner)
        val coco = f.movie("Coco")
        f.add(list, coco)
        f.status(owner, coco, WatchStatus.WATCHED)
        f.rate(owner, coco, 9.0)

        val progress = f.handler.handle(GetListProgress(list.id!!, viewer)).getOrNull()!!

        assertEquals(0, progress.seenCount)
        assertNull(progress.entries.single().status)
        assertNull(progress.entries.single().score)
    }

    @Test
    fun `a show counts as seen once a season is rated`() {
        val list = f.list(owner)
        val bluey = f.show("Bluey")
        val gravityFalls = f.show("Gravity Falls")
        f.add(list, bluey, gravityFalls)
        f.rate(viewer, bluey, 9.5)

        val progress = f.handler.handle(GetListProgress(list.id!!, viewer)).getOrNull()!!

        assertEquals(listOf(true, false), progress.entries.map { it.seen })
        assertEquals(9.5, progress.entries[0].score)
    }

    @Test
    fun `looks marks up once per media type, not once per title`() {
        val list = f.list(owner)
        val movies = (1..30).map { f.movie("Movie $it") }
        val shows = (1..5).map { f.show("Show $it") }
        f.add(list, *(movies + shows).toTypedArray())

        f.handler.handle(GetListProgress(list.id!!, viewer))

        assertEquals(2, f.progressRepository.markQueries.get())
        assertEquals(setOf(MediaType.movie, MediaType.tvshow), f.progressRepository.markQueryTypes.toSet())
    }

    @Test
    fun `a movie-only list needs one marks query and an empty list none`() {
        val list = f.list(owner)
        f.add(list, f.movie("Coco"), f.movie("Up"))
        val empty = f.list(owner, name = "Empty")

        f.handler.handle(GetListProgress(list.id!!, viewer))
        assertEquals(1, f.progressRepository.markQueries.get())

        val progress = f.handler.handle(GetListProgress(empty.id!!, viewer)).getOrNull()!!
        assertEquals(1, f.progressRepository.markQueries.get())
        assertEquals(0, progress.total)
    }

    @Test
    fun `someone else's private list is not authorized, a missing one not found`() {
        val privateList = f.list(owner, isPublic = false)

        assertEquals(GetListProgressError.NotAuthorized, f.handler.handle(GetListProgress(privateList.id!!, viewer)).leftOrNull())
        assertEquals(GetListProgressError.ListNotFound, f.handler.handle(GetListProgress(CustomList.Id(999), viewer)).leftOrNull())
    }

    @Test
    fun `the owner sees progress on their private list`() {
        val privateList = f.list(owner, isPublic = false)
        f.add(privateList, f.movie("Coco"))

        val progress = f.handler.handle(GetListProgress(privateList.id!!, owner)).getOrNull()!!

        assertEquals(1, progress.total)
        assertEquals(owner, progress.ownerId)
    }

    @Test
    fun `seen counts for cards map lists with nothing seen to zero`() {
        val a = f.list(owner, name = "A")
        val b = f.list(owner, name = "B")
        val coco = f.movie("Coco")
        val up = f.movie("Up")
        f.add(a, coco, up)
        f.add(b, up)
        f.status(viewer, coco, WatchStatus.WATCHED)
        f.status(viewer, up, WatchStatus.WANT_TO_WATCH)

        val counts = f.handler.seenCounts(viewer, listOf(a.id!!, b.id!!))

        assertEquals(mapOf(a.id!! to 1, b.id!! to 0), counts)
    }
}
