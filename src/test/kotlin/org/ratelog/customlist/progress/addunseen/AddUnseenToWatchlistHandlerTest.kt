package org.ratelog.customlist.progress.addunseen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.progress.GetListProgressError
import org.ratelog.customlist.progress.ListProgressFixture
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AddUnseenToWatchlistHandlerTest {

    private val f = ListProgressFixture()
    private val now = 1_800_000_000_000L
    private val handler = AddUnseenToWatchlistHandler(
        f.handler,
        f.progressRepository,
        Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
    )
    private val owner = User.Id(1)
    private val viewer = User.Id(2)

    @Test
    fun `adds only the unseen titles that have no status`() {
        val list = f.list(owner)
        val (coco, up, moana, frozen, encanto, wish, cars) =
            listOf("Coco", "Up", "Moana", "Frozen", "Encanto", "Wish", "Cars").map(f::movie)
        f.add(list, coco, up, moana, frozen, encanto, wish, cars)
        f.status(viewer, up, WatchStatus.WATCHING, at = 11)
        f.status(viewer, moana, WatchStatus.DROPPED, at = 12)
        f.status(viewer, frozen, WatchStatus.WATCHED, at = 13)
        f.status(viewer, encanto, WatchStatus.WANT_TO_WATCH, at = 14)

        val added = handler.handle(AddUnseenToWatchlist(list.id!!, viewer)).getOrNull()

        assertEquals(3, added, "Coco, Wish and Cars")
        assertEquals(WatchStatus.WANT_TO_WATCH, f.statusOf(viewer, coco))
        assertEquals(WatchStatus.WANT_TO_WATCH, f.statusOf(viewer, wish))
        assertEquals(WatchStatus.WANT_TO_WATCH, f.statusOf(viewer, cars))
        // Existing statuses are left exactly as they were, timestamps included.
        assertEquals(WatchStatus.WATCHING, f.statusOf(viewer, up))
        assertEquals(WatchStatus.DROPPED, f.statusOf(viewer, moana))
        assertEquals(WatchStatus.WATCHED, f.statusOf(viewer, frozen))
        assertEquals(WatchStatus.WANT_TO_WATCH, f.statusOf(viewer, encanto))
        assertEquals(listOf(11L, 12L, 13L, 14L), listOf(up, moana, frozen, encanto).map {
            f.statuses.find(viewer, MediaType.movie, it.id!!.value)!!.updatedAtEpochMs
        })
    }

    @Test
    fun `a rated title without a status counts as seen and is skipped`() {
        val list = f.list(owner)
        val coco = f.movie("Coco")
        val bluey = f.show("Bluey")
        val up = f.movie("Up")
        f.add(list, coco, bluey, up)
        f.rate(viewer, coco, 8.0)
        f.rate(viewer, bluey, 9.0)

        val added = handler.handle(AddUnseenToWatchlist(list.id!!, viewer)).getOrNull()

        assertEquals(1, added)
        assertNull(f.statusOf(viewer, coco))
        assertNull(f.statusOf(viewer, bluey))
        assertEquals(WatchStatus.WANT_TO_WATCH, f.statusOf(viewer, up))
    }

    @Test
    fun `adds shows too, and earlier ranks get later timestamps so the watchlist shows list order`() {
        val list = f.list(owner)
        val coco = f.movie("Coco")
        val bluey = f.show("Bluey")
        val up = f.movie("Up")
        f.add(list, coco, bluey, up)

        handler.handle(AddUnseenToWatchlist(list.id!!, viewer))

        val watchlist = f.statuses.findByUser(viewer, WatchStatus.WANT_TO_WATCH, null)
        assertEquals(
            listOf(MediaType.movie to coco.id!!.value, MediaType.tvshow to bluey.id!!.value, MediaType.movie to up.id!!.value),
            watchlist.map { it.mediaType to it.mediaId },
        )
        assertEquals(now, watchlist.first().updatedAtEpochMs)
    }

    @Test
    fun `running it twice adds nothing the second time`() {
        val list = f.list(owner)
        f.add(list, f.movie("Coco"), f.movie("Up"))

        assertEquals(2, handler.handle(AddUnseenToWatchlist(list.id!!, viewer)).getOrNull())
        assertEquals(0, handler.handle(AddUnseenToWatchlist(list.id!!, viewer)).getOrNull())
    }

    @Test
    fun `works on someone else's public list and only touches the viewer's statuses`() {
        val list = f.list(owner, isPublic = true)
        val coco = f.movie("Coco")
        f.add(list, coco)

        handler.handle(AddUnseenToWatchlist(list.id!!, viewer))

        assertEquals(WatchStatus.WANT_TO_WATCH, f.statusOf(viewer, coco))
        assertNull(f.statusOf(owner, coco))
    }

    @Test
    fun `refuses someone else's private list without changing anything`() {
        val list = f.list(owner, isPublic = false)
        val coco = f.movie("Coco")
        f.add(list, coco)

        val result = handler.handle(AddUnseenToWatchlist(list.id!!, viewer))

        assertEquals(GetListProgressError.NotAuthorized, result.leftOrNull())
        assertNull(f.statusOf(viewer, coco))
        assertTrue(f.statuses.findByUser(viewer, null, null).isEmpty())
    }

    @Test
    fun `a missing list is not found`() {
        assertEquals(GetListProgressError.ListNotFound, handler.handle(AddUnseenToWatchlist(CustomList.Id(404), viewer)).leftOrNull())
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
private operator fun <T> List<T>.component7(): T = this[6]
