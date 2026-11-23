package org.ratelog.customlist.membership

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.customlist.removeitem.RemoveFromListHandler
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryListMembershipRepository
import org.ratelog.test.UserFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import java.util.Locale

class SetListMembershipControllerTest {

    private val lists = InMemoryCustomListRepository()
    private val membership = InMemoryListMembershipRepository(lists)
    private val controller = SetListMembershipController(
        SetListMembershipHandler(lists, AddToListHandler(lists), RemoveFromListHandler(lists)),
        membership,
        BrowseBarViewsTest.testMessageSource(),
    )

    private val user = UserFactory.aUser(id = 1)
    private val other = UserFactory.aUser(id = 2)
    private val coco = 42L

    private val disney = list("Best Disney Movies", createdAt = 3_000, 10, 11, coco, 12) // Coco is #3
    private val pixar = list("Pixar ranked", createdAt = 2_000)
    private val kids = list("Watch with kids", createdAt = 1_000, coco)
    private val theirs = list("Not yours", createdAt = 500, coco, owner = other.id!!)

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun list(name: String, createdAt: Long, vararg mediaIds: Long, owner: org.ratelog.user.User.Id = user.id!!): CustomList {
        val list = lists.save(CustomList(null, owner, ListName(name), isPublic = false, createdAtEpochMs = createdAt))
        mediaIds.forEachIndexed { index, mediaId ->
            lists.addItem(CustomListItem(null, list.id!!, mediaId, MediaType.movie, index + 1, createdAt))
        }
        return list
    }

    private fun has(list: CustomList, mediaId: Long = coco) = lists.findById(list.id!!)!!.items.any { it.mediaId == mediaId }
    private fun request(referer: String? = null) = MockHttpServletRequest("POST", "/list/1/membership").apply {
        referer?.let { addHeader("Referer", it) }
    }

    // --- Plain form posts (no JS) ---

    @Test
    fun `a plain post adds the title and goes back to the title page`() {
        val flash = RedirectAttributesModelMap()

        val view = controller.set(pixar.id!!.value, user, coco, "movie", inList = true, back = "/movie/354912", request(), flash)

        assertEquals("redirect:/movie/354912", view)
        assertTrue(has(pixar))
        assertEquals("list.member.added.plain", flash.flashAttributes["success"])
    }

    @Test
    fun `a plain post removes the title and goes back`() {
        val flash = RedirectAttributesModelMap()

        val view = controller.set(disney.id!!.value, user, coco, "movie", inList = false, back = "/movie/354912", request(), flash)

        assertEquals("redirect:/movie/354912", view)
        assertFalse(has(disney))
        assertTrue(has(kids), "only that list")
        assertEquals("list.member.removed.plain", flash.flashAttributes["success"])
    }

    @Test
    fun `without back it uses the referer, and never leaves the app`() {
        assertEquals(
            "redirect:/tv/1399",
            controller.set(pixar.id!!.value, user, coco, "movie", true, null, request("http://localhost:8130/tv/1399"), RedirectAttributesModelMap()),
        )
        assertEquals(
            "redirect:/x",
            controller.set(pixar.id!!.value, user, coco, "movie", true, "//evil.example/x", request(), RedirectAttributesModelMap()),
            "only the path of another host's URL is kept",
        )
        assertEquals(
            "redirect:/list/${pixar.id!!.value}",
            controller.set(pixar.id!!.value, user, coco, "movie", true, "javascript:alert(1)", request(), RedirectAttributesModelMap()),
        )
    }

    @Test
    fun `someone else's list, a missing list or a bad type are reported and change nothing`() {
        val notOwner = RedirectAttributesModelMap()
        controller.set(theirs.id!!.value, user, coco, "movie", inList = false, back = "/movie/354912", request(), notOwner)
        assertEquals("list.error.not.owner", notOwner.flashAttributes["error"])
        assertTrue(has(theirs))

        val missing = RedirectAttributesModelMap()
        controller.set(999, user, coco, "movie", inList = true, back = "/movie/354912", request(), missing)
        assertEquals("list.error.not.found", missing.flashAttributes["error"])

        val bad = RedirectAttributesModelMap()
        controller.set(pixar.id!!.value, user, coco, "book", inList = true, back = "/movie/354912", request(), bad)
        assertEquals("list.member.error.invalid", bad.flashAttributes["error"])
        assertFalse(has(pixar))
    }

    // --- fetch (list-membership.js) ---

    @Test
    fun `fetch adds and answers the new state, the count and every label`() {
        val response = controller.setJson(pixar.id!!.value, user, coco, "movie", inList = true)

        assertEquals(HttpStatus.OK, response.statusCode)
        with(response.body!!) {
            assertTrue(ok)
            assertEquals(pixar.id!!.value, listId)
            assertTrue(inList)
            assertEquals(1, rank, "Pixar was empty")
            assertEquals("#1", rankLabel)
            assertEquals("Remove from \"Pixar ranked\"? It's #1 there.", confirm)
            assertEquals(3, count, "Disney, Pixar and Watch with kids; never someone else's list")
            assertEquals("In 3 of your lists", summary)
            assertEquals("In 3 lists", buttonLabel)
            assertEquals("In 3 of your lists. Tap to see which or to change it.", buttonTitle)
            assertEquals("Added to Pixar ranked", message)
        }
        assertEquals(1, membership.queries.get(), "one membership read per toggle")
    }

    @Test
    fun `fetch removes and the confirm question goes away`() {
        val first = controller.setJson(disney.id!!.value, user, coco, "movie", inList = false).body!!

        assertTrue(first.ok)
        assertFalse(first.inList)
        assertNull(first.rank)
        assertNull(first.rankLabel)
        assertNull(first.confirm)
        assertEquals(1, first.count)
        assertEquals("In one of your lists", first.summary)
        assertEquals("In 1 list", first.buttonLabel)
        assertEquals("Removed from Best Disney Movies", first.message)

        val last = controller.setJson(kids.id!!.value, user, coco, "movie", inList = false).body!!
        assertEquals(0, last.count)
        assertEquals("Not in any of your lists yet", last.summary)
        assertEquals("Add to list", last.buttonLabel)
        assertEquals("Add it to one of your lists", last.buttonTitle)
    }

    @Test
    fun `the rank in the confirm question is the title's place in the list`() {
        // Removing it elsewhere keeps Disney's numbering: Coco is still #3 there.
        val response = controller.setJson(kids.id!!.value, user, coco, "movie", inList = false).body!!
        assertEquals(1, response.count)

        val again = controller.setJson(disney.id!!.value, user, coco, "movie", inList = true).body!!
        assertEquals(3, again.rank, "already there: nothing changes")
        assertEquals("Remove from \"Best Disney Movies\"? It's #3 there.", again.confirm)
        assertEquals("Added to Best Disney Movies", again.message)
    }

    @Test
    fun `fetch errors are JSON with a status and a message`() {
        val notOwner = controller.setJson(theirs.id!!.value, user, coco, "movie", inList = false)
        assertEquals(HttpStatus.FORBIDDEN, notOwner.statusCode)
        assertFalse(notOwner.body!!.ok)
        assertEquals("You are not the owner of this list.", notOwner.body!!.message)
        assertTrue(has(theirs))

        val missing = controller.setJson(999, user, coco, "movie", inList = true)
        assertEquals(HttpStatus.NOT_FOUND, missing.statusCode)
        assertEquals("List not found.", missing.body!!.message)

        val bad = controller.setJson(pixar.id!!.value, user, coco, "tv", inList = true)
        assertEquals(HttpStatus.BAD_REQUEST, bad.statusCode)
        assertEquals("That title or list can't be changed.", bad.body!!.message)
        assertFalse(has(pixar))
    }

    @Test
    fun `list names with quotes and apostrophes come out as written`() {
        val quoted = list("Andre's \"comfort\" picks", createdAt = 4_000)

        val body = controller.setJson(quoted.id!!.value, user, coco, "movie", inList = true).body!!

        assertEquals("Remove from \"Andre's \"comfort\" picks\"? It's #1 there.", body.confirm)
        assertEquals("Added to Andre's \"comfort\" picks", body.message)
    }
}
