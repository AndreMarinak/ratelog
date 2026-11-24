package org.ratelog.api.lists

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.ratelog.api.ApiException
import org.ratelog.api.CreateListRequest
import org.ratelog.api.ItemInput
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.media.TitleResolver
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.customlist.create.CreateListHandler
import org.ratelog.customlist.create.ListLimitPolicy
import org.ratelog.customlist.delete.DeleteListHandler
import org.ratelog.customlist.official.mark.SetListOfficialHandler
import org.ratelog.customlist.order.move.MoveListItemHandler
import org.ratelog.customlist.order.numbered.SetListNumberedHandler
import org.ratelog.customlist.order.setorder.SetListOrderHandler
import org.ratelog.test.InMemoryListOptionsRepository
import org.ratelog.test.InMemoryListOrderRepository
import org.ratelog.customlist.progress.ListProgressFixture
import org.ratelog.customlist.removeitem.RemoveFromListHandler
import org.ratelog.customlist.update.UpdateListHandler
import org.ratelog.tag.assign.AssignTagsHandler
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryOfficialListRepository
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.UserFactory
import org.ratelog.tmdb.TmdbClient
import org.ratelog.watchlist.WatchStatus
import org.springframework.http.HttpStatus

/** GET /api/v1/lists/{id}: per-item `status` / `score` / `seen` and a top-level `progress` for the token's user. */
class ApiListControllerProgressTest {

    private val f = ListProgressFixture()
    private val tmdbClient: TmdbClient = mock()
    private val officialLists = InMemoryOfficialListRepository(f.lists)
    private val listOrder = InMemoryListOrderRepository(f.lists)
    private val listOptions = InMemoryListOptionsRepository(f.lists)
    private val mediaImporter = MediaImporter(tmdbClient, f.movies, InMemoryMovieDescriptionRepository(), f.shows, InMemoryTvDescriptionRepository())
    private val controller = ApiListController(
        customListRepository = f.lists,
        createListHandler = CreateListHandler(f.lists, ListLimitPolicy.fixed(ListLimitPolicy.USER_MAX)),
        updateListHandler = UpdateListHandler(f.lists),
        deleteListHandler = DeleteListHandler(f.lists),
        removeFromListHandler = RemoveFromListHandler(f.lists),
        addListItemsService = AddListItemsService(
            TitleResolver(tmdbClient),
            mediaImporter,
            AddToListHandler(f.lists),
            AssignTagsHandler(InMemoryTagRepository(), f.movies, f.shows),
            f.lists,
            MoveListItemHandler(f.lists, listOrder),
        ),
        mediaImporter = mediaImporter,
        getListProgressHandler = f.handler,
        officialLists = officialLists,
        setListOfficialHandler = SetListOfficialHandler(f.lists, officialLists, InMemoryUserRepository()),
        listOptions = listOptions,
        setListNumberedHandler = SetListNumberedHandler(f.lists, listOptions),
        moveListItemHandler = MoveListItemHandler(f.lists, listOrder),
        setListOrderHandler = SetListOrderHandler(f.lists, listOrder),
    )
    private val user = UserFactory.aUser(id = 1)
    private val userId = user.id!!
    private val other = UserFactory.aUser(id = 2)

    @Test
    fun `items carry the user's status, score and seen, plus progress`() {
        val list = f.list(userId)
        val coco = f.movie("Coco")
        val up = f.movie("Up")
        val moana = f.movie("Moana")
        val bluey = f.show("Bluey")
        f.add(list, coco, up, moana, bluey)
        f.status(userId, coco, WatchStatus.WATCHED)
        f.status(userId, up, WatchStatus.WANT_TO_WATCH)
        f.rate(userId, moana, 7.5)
        f.status(userId, bluey, WatchStatus.WATCHING)

        val detail = controller.list(user, list.id!!.value)

        assertEquals(listOf("Coco", "Up", "Moana", "Bluey"), detail.items.map { it.title })
        assertEquals(listOf("watched", "want_to_watch", null, "watching"), detail.items.map { it.status })
        assertEquals(listOf(true, false, true, false), detail.items.map { it.seen })
        assertEquals(listOf(null, null, 7.5, null), detail.items.map { it.score })
        assertEquals(ListProgressDto(seen = 2, total = 4), detail.progress)
        // Existing fields are unchanged.
        assertEquals(listOf(1, 2, 3, 4), detail.items.map { it.position })
        assertEquals(listOf("movie", "movie", "movie", "tv"), detail.items.map { it.type })
    }

    @Test
    fun `json keeps the old fields and adds the new ones`() {
        val list = f.list(userId, name = "Heist classics")
        val heat = f.movie("Heat")
        f.add(list, heat)
        f.status(userId, heat, WatchStatus.WATCHED)

        val json = jacksonObjectMapper().readTree(jacksonObjectMapper().writeValueAsString(controller.list(user, list.id!!.value)))

        assertEquals(setOf("id", "name", "isPublic", "isOfficial", "url", "items", "progress", "numbered"), json.fieldNames().asSequence().toSet())
        assertFalse(json["isOfficial"].asBoolean())
        assertEquals(1, json["progress"]["seen"].asInt())
        assertEquals(1, json["progress"]["total"].asInt())
        val item = json["items"][0]
        assertEquals(
            setOf("position", "tmdbId", "type", "title", "year", "posterUrl", "status", "score", "seen"),
            item.fieldNames().asSequence().toSet(),
        )
        assertEquals("watched", item["status"].asText())
        assertTrue(item["seen"].asBoolean())
        assertTrue(item["score"].isNull)
    }

    @Test
    fun `an untouched list has zero progress and null statuses`() {
        val list = f.list(userId)
        f.add(list, f.movie("Coco"), f.movie("Up"))

        val detail = controller.list(user, list.id!!.value)

        assertEquals(ListProgressDto(seen = 0, total = 2), detail.progress)
        assertTrue(detail.items.all { it.status == null && !it.seen })
    }

    @Test
    fun `create and update responses include progress too`() {
        val created = controller.create(user, CreateListRequest(name = "Empty", items = emptyList<ItemInput>())).body!!
        assertEquals(ListProgressDto(seen = 0, total = 0), created.list.progress)
        assertNull(created.items)

        val updated = controller.update(user, created.list.id, org.ratelog.api.UpdateListRequest(isPublic = true))
        assertEquals(ListProgressDto(seen = 0, total = 0), updated.progress)
        assertTrue(updated.isPublic)
    }

    @Test
    fun `another user's list is still a 404`() {
        val list = f.list(other.id!!, isPublic = true)
        f.add(list, f.movie("Coco"))

        val e = assertThrows<ApiException> { controller.list(user, list.id!!.value) }
        assertEquals(HttpStatus.NOT_FOUND, e.status)
        assertFalse(f.progressRepository.markQueries.get() > 0, "progress isn't read for lists you can't see")
    }
}
