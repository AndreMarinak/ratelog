package org.ratelog.customlist.progress.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.customlist.progress.ListProgressFixture
import org.ratelog.test.InMemoryTitleReleaseRepository
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.MutableClock
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.WatchStatus
import org.springframework.ui.ExtendedModelMap

class ListProgressModelAdviceTest {

    private val f = ListProgressFixture()
    private val users = InMemoryUserRepository()
    private val alice = users.save(UserFactory.aUser(username = "alice"))
    private val bob = users.save(UserFactory.aUser(username = "bob"))
    private val releases = InMemoryTitleReleaseRepository(f.movies, f.shows)
    private val advice = ListProgressModelAdvice(f.handler, users, releases, MutableClock())

    @Test
    fun `own list gets progress and a back link to Lists`() {
        val list = f.list(alice.id!!)
        val coco = f.movie("Coco")
        f.add(list, coco, f.movie("Up"))
        f.status(alice.id!!, coco, WatchStatus.WATCHED)
        val model = ExtendedModelMap()

        advice.addListProgress(alice, list.id!!.value, null, null, model)

        val progress = model["listProgress"] as ListProgressView
        assertEquals(1, progress.seen)
        assertEquals(2, progress.total)
        assertEquals(ListFilter.ALL, progress.filter)
        assertEquals(ListBackLink.LISTS, model["listBack"])
    }

    @Test
    fun `someone else's public list shows the viewer's progress and links back to the owner's profile`() {
        val list = f.list(alice.id!!, isPublic = true)
        val coco = f.movie("Coco")
        f.add(list, coco)
        f.status(alice.id!!, coco, WatchStatus.WATCHED)
        val model = ExtendedModelMap()

        advice.addListProgress(bob, list.id!!.value, "seen", null, model)

        val progress = model["listProgress"] as ListProgressView
        assertEquals(0, progress.seen, "alice's status is not bob's progress")
        assertEquals(ListFilter.SEEN, progress.filter)
        assertEquals(ListBackLink("/profile/${alice.id!!.value}", "list.back.profile", "alice"), model["listBack"])
    }

    @Test
    fun `a private list of someone else gets no progress and a plain back link`() {
        val list = f.list(alice.id!!, isPublic = false)
        f.add(list, f.movie("Coco"))
        val model = ExtendedModelMap()

        advice.addListProgress(bob, list.id!!.value, null, null, model)

        assertNull(model["listProgress"])
        assertEquals(ListBackLink.LISTS, model["listBack"])
    }

    @Test
    fun `a missing list or owner falls back to Lists`() {
        val model = ExtendedModelMap()
        advice.addListProgress(bob, 404, null, null, model)
        assertNull(model["listProgress"])
        assertEquals(ListBackLink.LISTS, model["listBack"])

        val orphan = f.list(org.ratelog.user.User.Id(999), isPublic = true)
        val orphanModel = ExtendedModelMap()
        advice.addListProgress(bob, orphan.id!!.value, null, null, orphanModel)
        assertEquals(ListBackLink.LISTS, orphanModel["listBack"])
    }
}
