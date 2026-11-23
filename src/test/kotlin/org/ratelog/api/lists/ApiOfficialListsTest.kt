package org.ratelog.api.lists

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.ratelog.Role
import org.ratelog.api.AddItemsRequest
import org.ratelog.api.ApiException
import org.ratelog.api.CreateListRequest
import org.ratelog.api.ItemInput
import org.ratelog.api.UpdateListRequest
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.media.TitleResolver
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.customlist.create.CreateListHandler
import org.ratelog.customlist.delete.DeleteListHandler
import org.ratelog.customlist.official.OfficialListsFixture
import org.ratelog.customlist.removeitem.RemoveFromListHandler
import org.ratelog.customlist.update.UpdateListHandler
import org.ratelog.tag.assign.AssignTagsHandler
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.UserFactory
import org.ratelog.tmdb.TmdbClient
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.http.HttpStatus

/** Official lists in the API: `isOfficial` on lists, admin-only changes, `/official`, and reading other people's official lists. */
class ApiOfficialListsTest {

    private val f = OfficialListsFixture()
    private val tmdbClient: TmdbClient = mock()
    private val mediaImporter = MediaImporter(tmdbClient, f.p.movies, InMemoryMovieDescriptionRepository(), f.p.shows, InMemoryTvDescriptionRepository())
    private val controller = ApiListController(
        customListRepository = f.lists,
        createListHandler = CreateListHandler(f.lists),
        updateListHandler = UpdateListHandler(f.lists),
        deleteListHandler = DeleteListHandler(f.lists),
        removeFromListHandler = RemoveFromListHandler(f.lists),
        addListItemsService = AddListItemsService(
            TitleResolver(tmdbClient),
            mediaImporter,
            AddToListHandler(f.lists),
            AssignTagsHandler(InMemoryTagRepository(), f.p.movies, f.p.shows),
        ),
        mediaImporter = mediaImporter,
        getListProgressHandler = f.p.handler,
        officialLists = f.official,
        setListOfficialHandler = f.setOfficial,
    )
    private val officialController = ApiOfficialListController(f.getOfficial)
    private val admin = f.admin
    private val andre = f.andre
    private val json = jacksonObjectMapper()

    private fun error(block: () -> Unit): ApiException = assertThrows<ApiException> { block() }

    private fun assertForbidden(e: ApiException) {
        assertEquals(HttpStatus.FORBIDDEN, e.status)
        assertEquals("forbidden", e.code)
    }

    // --- isOfficial on the existing endpoints ---

    @Test
    fun `GET lists and GET list carry isOfficial`() {
        val official = f.list(admin, "AFI 100")
        val plain = f.list(admin, "Drafts")
        f.markOfficial(official)

        val lists = controller.lists(admin).associateBy { it.name }

        assertTrue(lists.getValue("AFI 100").isOfficial)
        assertFalse(lists.getValue("Drafts").isOfficial)
        assertTrue(controller.list(admin, official.id!!.value).isOfficial)
        assertFalse(controller.list(admin, plain.id!!.value).isOfficial)
        val summaryJson = json.readTree(json.writeValueAsString(controller.lists(admin).first()))
        assertEquals(setOf("id", "name", "isPublic", "isOfficial", "itemCount", "url"), summaryJson.fieldNames().asSequence().toSet())
    }

    // --- Reading another user's official list ---

    @Test
    fun `any token can read an official list, with its own progress`() {
        val list = f.list(admin, "AFI 100", isPublic = true, titles = 0)
        val kane = f.p.movie("Citizen Kane")
        val vertigo = f.p.movie("Vertigo")
        f.p.add(list, kane, vertigo)
        f.markOfficial(list)
        f.p.status(andre.id!!, kane, WatchStatus.WATCHED)
        f.p.status(admin.id!!, vertigo, WatchStatus.WATCHED)

        val detail = controller.list(andre, list.id!!.value)

        assertTrue(detail.isOfficial)
        assertTrue(detail.isPublic)
        assertEquals(listOf("Citizen Kane", "Vertigo"), detail.items.map { it.title })
        assertEquals(listOf(true, false), detail.items.map { it.seen }, "andre's marks, not the admin's")
        assertEquals(ListProgressDto(seen = 1, total = 2), detail.progress)
    }

    @Test
    fun `other users' ordinary lists are still a 404, public or not`() {
        val publicList = f.list(admin, "Public", isPublic = true)
        val privateList = f.list(admin, "Private")

        listOf(publicList, privateList).forEach { list ->
            assertEquals(HttpStatus.NOT_FOUND, error { controller.list(andre, list.id!!.value) }.status)
        }
    }

    @Test
    fun `an official list can be read but not changed by someone else`() {
        val list = f.list(admin, "AFI 100", isPublic = true)
        f.markOfficial(list)
        val id = list.id!!.value

        assertForbidden(error { controller.update(andre, id, UpdateListRequest(name = "Mine now")) })
        assertForbidden(error { controller.update(andre, id, UpdateListRequest(isPublic = false)) })
        assertForbidden(error { controller.delete(andre, id) })
        assertForbidden(error { controller.addItems(andre, id, AddItemsRequest(listOf(ItemInput(tmdbId = 1, type = "movie")))) })
        assertForbidden(error { controller.removeItem(andre, id, "movie", 1) })

        val after = f.reload(list)!!
        assertEquals("AFI 100", after.name.value)
        assertTrue(after.isPublic)
        assertTrue(f.official.isOfficial(list.id!!))
    }

    // --- PATCH isOfficial ---

    @Test
    fun `an admin token marks a private list official, which keeps it private (hidden)`() {
        val list = f.list(admin, "IMDb Top 100")

        val detail = controller.update(admin, list.id!!.value, UpdateListRequest(isOfficial = true))

        assertTrue(detail.isOfficial)
        assertFalse(detail.isPublic, "marking never changes the visibility")
        assertTrue(f.official.isOfficial(list.id!!))
        assertTrue(officialController.official(andre).isEmpty(), "hidden until it is public")

        val published = controller.update(admin, list.id!!.value, UpdateListRequest(isPublic = true))
        assertTrue(published.isOfficial && published.isPublic)
        assertEquals(listOf("IMDb Top 100"), officialController.official(andre).map { it.name })
    }

    @Test
    fun `marking and publishing can go in one request`() {
        val list = f.list(admin, "IMDb Top 250")

        val detail = controller.update(admin, list.id!!.value, UpdateListRequest(isPublic = true, isOfficial = true))

        assertTrue(detail.isOfficial && detail.isPublic)
        assertEquals(listOf("IMDb Top 250"), officialController.official(andre).map { it.name })
    }

    @Test
    fun `an admin token can mark and un-mark someone else's list, even a private one`() {
        val andresList = f.list(andre, "Andre's canon")

        val marked = controller.update(admin, andresList.id!!.value, UpdateListRequest(isOfficial = true))
        assertTrue(marked.isOfficial)
        assertFalse(marked.isPublic, "an admin marking someone's private list doesn't publish it")

        val unmarked = controller.update(admin, andresList.id!!.value, UpdateListRequest(isOfficial = false))
        assertFalse(unmarked.isOfficial)
        assertFalse(unmarked.isPublic, "un-marking doesn't change it either")
        assertEquals(andre.id, f.reload(andresList)!!.userId)

        val andresPublic = f.list(andre, "Andre's public", isPublic = true)
        controller.update(admin, andresPublic.id!!.value, UpdateListRequest(isOfficial = true))
        assertTrue(controller.update(admin, andresPublic.id!!.value, UpdateListRequest(isOfficial = false)).isPublic, "a public one stays public")
    }

    @Test
    fun `an admin can't rename someone else's list through the same request`() {
        val andresList = f.list(andre, "Andre's canon")

        assertForbidden(error { controller.update(admin, andresList.id!!.value, UpdateListRequest(name = "Renamed", isOfficial = true)) })

        assertFalse(f.official.isOfficial(andresList.id!!), "nothing is half-applied")
        assertFalse(f.reload(andresList)!!.isPublic)
    }

    @Test
    fun `a regular token gets 403 for changing isOfficial, on any list, and nothing changes`() {
        val own = f.list(andre, "Mine")
        val official = f.list(admin, "AFI 100", isPublic = true)
        f.markOfficial(official)

        assertForbidden(error { controller.update(andre, own.id!!.value, UpdateListRequest(isOfficial = true)) })
        assertForbidden(error { controller.update(andre, official.id!!.value, UpdateListRequest(isOfficial = false)) })

        assertFalse(f.official.isOfficial(own.id!!))
        assertFalse(f.reload(own)!!.isPublic)
        assertTrue(f.official.isOfficial(official.id!!))
    }

    @Test
    fun `a regular token's request for a hidden list is still a 404, not a 403`() {
        val adminsPrivate = f.list(admin, "Private")

        assertEquals(HttpStatus.NOT_FOUND, error { controller.update(andre, adminsPrivate.id!!.value, UpdateListRequest(isOfficial = true)) }.status)
    }

    @Test
    fun `sending the current isOfficial back is fine for anyone`() {
        val own = f.list(andre, "Mine")

        val detail = controller.update(andre, own.id!!.value, UpdateListRequest(name = "Mine, renamed", isOfficial = false))

        assertEquals("Mine, renamed", detail.name)
        assertFalse(detail.isOfficial)
    }

    @Test
    fun `the owner can make an official list private, which hides it, and public again`() {
        val list = f.list(admin, "AFI 100", isPublic = true)
        f.markOfficial(list)
        val id = list.id!!.value

        val hidden = controller.update(admin, id, UpdateListRequest(isPublic = false))
        assertTrue(hidden.isOfficial, "still official")
        assertFalse(hidden.isPublic, "isPublic: false is no longer refused (official_list_public is gone)")
        assertFalse(f.reload(list)!!.isPublic)
        assertTrue(officialController.official(andre).isEmpty())
        assertEquals(HttpStatus.NOT_FOUND, error { controller.list(andre, id) }.status)

        val shown = controller.update(admin, id, UpdateListRequest(isPublic = true))
        assertTrue(shown.isOfficial && shown.isPublic)
        assertEquals(listOf("AFI 100"), officialController.official(andre).map { it.name })
        assertEquals("AFI 100", controller.list(andre, id).name)

        val both = controller.update(admin, id, UpdateListRequest(isOfficial = false, isPublic = false))
        assertFalse(both.isOfficial)
        assertFalse(both.isPublic, "both in one request")
    }

    @Test
    fun `a hidden official list is only its owner's, like any private list`() {
        val list = f.list(admin, "Drafts")
        f.markOfficial(list)
        val id = list.id!!.value

        assertEquals("Drafts", controller.list(admin, id).name, "the owner reads it")
        assertTrue(controller.lists(admin).single { it.name == "Drafts" }.let { it.isOfficial && !it.isPublic })
        listOf<() -> Unit>(
            { controller.list(andre, id) },
            { controller.update(andre, id, UpdateListRequest(name = "Mine now")) },
            { controller.update(andre, id, UpdateListRequest(isOfficial = false)) },
            { controller.delete(andre, id) },
            { controller.addItems(andre, id, AddItemsRequest(listOf(ItemInput(tmdbId = 1, type = "movie")))) },
            { controller.removeItem(andre, id, "movie", 1) },
        ).forEach { call -> assertEquals(HttpStatus.NOT_FOUND, error(call).status, "404, not 403: its id is no one else's business") }
        assertTrue(officialController.official(andre).isEmpty())
        assertTrue(officialController.official(admin).isEmpty(), "the Official sections only list public ones, the owner's too")
    }

    @Test
    fun `another admin can still un-mark a hidden official list`() {
        val mod: User = f.users.save(UserFactory.aUser(username = "mod", role = Role.ADMIN))
        val list = f.list(andre, "Andre's drafts")
        f.markOfficial(list)

        val detail = controller.update(mod, list.id!!.value, UpdateListRequest(isOfficial = false))

        assertFalse(detail.isOfficial)
        assertFalse(detail.isPublic)
        assertEquals(HttpStatus.NOT_FOUND, error { controller.update(mod, f.list(andre, "Other").id!!.value, UpdateListRequest(isOfficial = false)) }.status,
            "un-marking a list that isn't official doesn't open someone's private list")
    }

    @Test
    fun `an invalid name is refused before isOfficial changes`() {
        val list = f.list(admin, "AFI 100")

        val e = error { controller.update(admin, list.id!!.value, UpdateListRequest(name = " ", isOfficial = true)) }

        assertEquals("invalid_name", e.code)
        assertFalse(f.official.isOfficial(list.id!!))
    }

    // --- POST isOfficial ---

    @Test
    fun `an admin token creates an official list in one request`() {
        val created = controller.create(admin, CreateListRequest(name = "Sight and Sound", isPublic = true, isOfficial = true)).body!!

        assertTrue(created.list.isOfficial)
        assertTrue(created.list.isPublic)
        assertTrue(f.official.isOfficial(CustomList.Id(created.list.id)))
        assertEquals(listOf("Sight and Sound"), officialController.official(andre).map { it.name })
    }

    @Test
    fun `a regular token can't create an official list, and no list is left behind`() {
        val e = error { controller.create(andre, CreateListRequest(name = "Sneaky", isOfficial = true)) }

        assertForbidden(e)
        assertTrue(f.lists.findByUserId(andre.id!!).isEmpty())
    }

    @Test
    fun `creating an official list without isPublic makes a hidden one, private like any list`() {
        val omitted = controller.create(admin, CreateListRequest(name = "Draft canon", isOfficial = true)).body!!.list
        val explicit = controller.create(admin, CreateListRequest(name = "Private canon", isPublic = false, isOfficial = true)).body!!.list

        listOf(omitted, explicit).forEach {
            assertTrue(it.isOfficial)
            assertFalse(it.isPublic)
        }
        assertTrue(officialController.official(andre).isEmpty())
    }

    @Test
    fun `POST without isOfficial works as before, private by default`() {
        val created = controller.create(andre, CreateListRequest(name = "Plain")).body!!.list

        assertFalse(created.isOfficial)
        assertFalse(created.isPublic)
        assertTrue(controller.create(andre, CreateListRequest(name = "Public one", isPublic = true)).body!!.list.isPublic)
        assertFalse(controller.create(andre, CreateListRequest(name = "Explicitly not", isOfficial = false)).body!!.list.isOfficial)
    }

    // --- GET /api/v1/lists/official ---

    @Test
    fun `official returns every official list with the token user's progress`() {
        val afi = f.list(admin, "AFI 100", isPublic = true, titles = 0, createdAt = 10_000)
        val kane = f.p.movie("Citizen Kane")
        f.p.add(afi, kane, f.p.movie("Casablanca"), f.p.movie("Vertigo"))
        val andres = f.list(andre, "Andre's canon", isPublic = true, createdAt = 20_000)
        f.markOfficial(f.list(admin, "Hidden draft", createdAt = 40_000))
        f.list(admin, "Not official", isPublic = true, createdAt = 30_000)
        f.markOfficial(afi)
        f.markOfficial(andres)
        f.p.status(andre.id!!, kane, WatchStatus.WATCHED)

        val lists = officialController.official(andre)

        assertEquals(listOf("Andre's canon", "AFI 100"), lists.map { it.name }, "most recently active first")
        val afiDto = lists.single { it.name == "AFI 100" }
        assertEquals(ListProgressDto(seen = 1, total = 3), afiDto.progress)
        assertEquals("/list/${afi.id!!.value}", afiDto.url)
        assertEquals("admin", afiDto.owner)
        assertTrue(afiDto.isOfficial && afiDto.isPublic)
        assertEquals(ListProgressDto(seen = 0, total = 3), officialController.official(admin).single { it.name == "AFI 100" }.progress)

        val dtoJson = json.readTree(json.writeValueAsString(afiDto))
        assertEquals(setOf("id", "name", "isPublic", "isOfficial", "itemCount", "url", "owner", "progress"), dtoJson.fieldNames().asSequence().toSet())
        assertEquals(setOf("seen", "total"), dtoJson["progress"].fieldNames().asSequence().toSet())
    }

    @Test
    fun `official is an empty array when there are none`() {
        f.list(admin, "Private")

        assertTrue(officialController.official(andre).isEmpty())
    }

    @Test
    fun `deleting an official list takes it out of official`() {
        val list = f.list(admin, "AFI 100", isPublic = true)
        f.markOfficial(list)

        controller.delete(admin, list.id!!.value)

        assertTrue(officialController.official(andre).isEmpty())
        assertEquals(HttpStatus.NOT_FOUND, error { controller.list(andre, list.id!!.value) }.status)
    }

    @Test
    fun `admins and superadmins both count, and the role is re-checked against the account`() {
        val mod: User = f.users.save(UserFactory.aUser(username = "mod", role = Role.ADMIN))
        val list = f.list(andre, "Andre's")
        assertTrue(controller.update(mod, list.id!!.value, UpdateListRequest(isOfficial = true)).isOfficial)

        // A principal that claims ADMIN for an account that is a plain USER in the database.
        val demoted: User = f.users.save(UserFactory.aUser(username = "demoted", role = Role.USER))
        val theirs = f.list(demoted, "Theirs")
        assertForbidden(error { controller.update(demoted.copy(role = Role.ADMIN), theirs.id!!.value, UpdateListRequest(isOfficial = true)) })
        assertFalse(f.official.isOfficial(theirs.id!!))
        assertFalse(f.reload(theirs)!!.isPublic)
    }
}
