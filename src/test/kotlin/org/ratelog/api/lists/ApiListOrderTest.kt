package org.ratelog.api.lists

import arrow.core.right
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.api.AddItemsRequest
import org.ratelog.api.ApiException
import org.ratelog.api.CreateListRequest
import org.ratelog.api.ItemInput
import org.ratelog.api.MoveItemRequest
import org.ratelog.api.SetOrderRequest
import org.ratelog.api.TitleRefInput
import org.ratelog.api.UpdateListRequest
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.media.TitleResolver
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.customlist.create.CreateListHandler
import org.ratelog.customlist.create.ListLimitPolicy
import org.ratelog.customlist.delete.DeleteListHandler
import org.ratelog.customlist.official.OfficialListsFixture
import org.ratelog.customlist.order.move.MoveListItemHandler
import org.ratelog.customlist.order.numbered.SetListNumberedHandler
import org.ratelog.customlist.order.setorder.SetListOrderHandler
import org.ratelog.customlist.removeitem.RemoveFromListHandler
import org.ratelog.customlist.update.UpdateListHandler
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieDescription
import org.ratelog.tag.assign.AssignTagsHandler
import org.ratelog.test.InMemoryListOptionsRepository
import org.ratelog.test.InMemoryListOrderRepository
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.tmdb.TmdbClient
import org.springframework.http.HttpStatus

/** List order in the API: `numbered`, contiguous `position`, insert at a position, move one title, set the whole order. */
class ApiListOrderTest {

    private val f = OfficialListsFixture()
    private val tmdbClient: TmdbClient = mock<TmdbClient>().also {
        whenever(it.movieTranslations(any(), any())).thenReturn(emptyList<MovieDescription>().right())
    }
    private val listOrder = InMemoryListOrderRepository(f.p.lists)
    private val listOptions = InMemoryListOptionsRepository(f.p.lists)
    private val mediaImporter = MediaImporter(tmdbClient, f.p.movies, InMemoryMovieDescriptionRepository(), f.p.shows, InMemoryTvDescriptionRepository())
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
            AssignTagsHandler(InMemoryTagRepository(), f.p.movies, f.p.shows),
            f.lists,
            MoveListItemHandler(f.lists, listOrder),
        ),
        mediaImporter = mediaImporter,
        getListProgressHandler = f.p.handler,
        officialLists = f.official,
        setListOfficialHandler = f.setOfficial,
        listOptions = listOptions,
        setListNumberedHandler = SetListNumberedHandler(f.lists, listOptions),
        moveListItemHandler = MoveListItemHandler(f.lists, listOrder),
        setListOrderHandler = SetListOrderHandler(f.lists, listOrder),
    )
    private val admin = f.admin
    private val andre = f.andre
    private val json = jacksonObjectMapper()

    private fun error(block: () -> Unit): ApiException = assertThrows<ApiException> { block() }

    /** A list of local movies; returns it and the movies (tmdb ids are what the API speaks). */
    private fun list(vararg titles: String, owner: org.ratelog.user.User = andre): Pair<CustomList, List<Movie>> {
        val list = f.list(owner, "Ranked", titles = 0)
        val movies = titles.map { f.p.movie(it) }
        f.p.add(list, *movies.toTypedArray())
        return f.reload(list)!! to movies
    }

    /** A public official list, the kind every account sees (official lists can be hidden since 2026-11-23). */
    private fun publishAsOfficial(list: CustomList) {
        f.p.lists.save(f.reload(list)!!.copy(isPublic = true))
        f.markOfficial(list)
    }

    private fun titles(list: CustomList) = controller.list(andre, list.id!!.value).items.map { it.title }
    private fun ref(movie: Movie) = TitleRefInput(tmdbId = movie.tmdbId.value, type = "movie")
    private fun input(movie: Movie, position: Int? = null) = ItemInput(tmdbId = movie.tmdbId.value, type = "movie", position = position)

    // --- numbered ---

    @Test
    fun `lists are numbered by default, and POST and PATCH set it`() {
        val created = controller.create(andre, CreateListRequest(name = "Comfort films", numbered = false)).body!!.list
        assertFalse(created.numbered)
        assertTrue(controller.create(andre, CreateListRequest(name = "Top 10")).body!!.list.numbered)

        assertTrue(controller.update(andre, created.id, UpdateListRequest(numbered = true)).numbered)
        assertFalse(controller.update(andre, created.id, UpdateListRequest(numbered = false)).numbered)

        val summaries = controller.lists(andre).associateBy { it.name }
        assertFalse(summaries.getValue("Comfort films").numbered)
        assertTrue(summaries.getValue("Top 10").numbered)
        assertEquals(false, json.readTree(json.writeValueAsString(controller.list(andre, created.id)))["numbered"].asBoolean())
    }

    @Test
    fun `numbered is the owner's, like name and visibility`() {
        val (list, _) = list("A", owner = admin)
        publishAsOfficial(list)

        val e = error { controller.update(andre, list.id!!.value, UpdateListRequest(numbered = false)) }
        assertEquals(HttpStatus.FORBIDDEN, e.status)
        assertTrue(listOptions.isNumbered(list.id!!))
        assertEquals(HttpStatus.NOT_FOUND, error { controller.update(admin, list("B").first.id!!.value, UpdateListRequest(numbered = false)) }.status)
    }

    // --- position on GET ---

    @Test
    fun `positions are 1-based places with no gaps`() {
        val (list, movies) = list("A", "B", "C")
        controller.removeItem(andre, list.id!!.value, "movie", movies[0].tmdbId.value)

        assertEquals(listOf(1, 2), controller.list(andre, list.id!!.value).items.map { it.position })
    }

    // --- insert at a position ---

    @Test
    fun `items with a position are inserted there, in the order sent`() {
        val (list, _) = list("A", "B", "C", "D")
        val coco = f.p.movie("Coco")
        val up = f.p.movie("Up")
        val last = f.p.movie("Last")

        val result = controller.addItems(andre, list.id!!.value, AddItemsRequest(listOf(input(coco, 3), input(up, 1), input(last))))

        assertEquals(3, result.added.size)
        assertEquals(listOf("Up", "A", "B", "Coco", "C", "D", "Last"), titles(list))
        assertEquals((1..7).toList(), controller.list(andre, list.id!!.value).items.map { it.position })
    }

    @Test
    fun `a position past the end appends, and a bad one is invalid`() {
        val (list, movies) = list("A", "B")
        val coco = f.p.movie("Coco")
        val up = f.p.movie("Up")

        val result = controller.addItems(andre, list.id!!.value, AddItemsRequest(listOf(input(coco, 99), input(up, 0), input(movies[1], 1))))

        assertEquals(listOf("A", "B", "Coco"), titles(list))
        assertEquals("invalid", result.unresolved.single().reason)
        assertTrue(result.unresolved.single().message.contains("position"))
        assertEquals(listOf("B"), result.alreadyInList.map { it.title }, "a title already in the list is not moved")
    }

    @Test
    fun `a new list can be created in a given order`() {
        val a = f.p.movie("A")
        val b = f.p.movie("B")
        val c = f.p.movie("C")

        val created = controller.create(andre, CreateListRequest(name = "Watch order", items = listOf(input(a), input(c), input(b, 2)))).body!!

        assertEquals(listOf("A", "B", "C"), created.list.items.map { it.title })
    }

    // --- PATCH one item ---

    @Test
    fun `PATCH an item moves it and returns the list`() {
        val (list, movies) = list("A", "B", "C", "D")
        val id = list.id!!.value

        val detail = controller.moveItem(andre, id, "movie", movies[3].tmdbId.value, MoveItemRequest(position = 2))
        assertEquals(listOf("A", "D", "B", "C"), detail.items.map { it.title })
        assertEquals(listOf(1, 2, 3, 4), detail.items.map { it.position })

        assertEquals(listOf("D", "B", "C", "A"), controller.moveItem(andre, id, "movie", movies[0].tmdbId.value, MoveItemRequest(position = 40)).items.map { it.title })
    }

    @Test
    fun `PATCH errors`() {
        val (list, movies) = list("A", "B")
        val id = list.id!!.value
        val tmdb = movies[0].tmdbId.value

        assertEquals("invalid_parameter", error { controller.moveItem(andre, id, "movie", tmdb, MoveItemRequest()) }.code)
        assertEquals("invalid_parameter", error { controller.moveItem(andre, id, "movie", tmdb, MoveItemRequest(position = 0)) }.code)
        assertEquals(HttpStatus.NOT_FOUND, error { controller.moveItem(andre, id, "movie", 424242, MoveItemRequest(position = 1)) }.status)
        assertEquals(HttpStatus.NOT_FOUND, error { controller.moveItem(andre, id, "tv", tmdb, MoveItemRequest(position = 1)) }.status)
        assertEquals("invalid_type", error { controller.moveItem(andre, id, "book", tmdb, MoveItemRequest(position = 1)) }.code)
        assertEquals(listOf("A", "B"), titles(list))
    }

    // --- PUT the whole order ---

    @Test
    fun `PUT order sets the order, titles left out follow`() {
        val (list, movies) = list("A", "B", "C", "D", "E")
        val (a, b, c, d) = movies

        val detail = controller.setOrder(andre, list.id!!.value, SetOrderRequest(listOf(ref(d), ref(b), ref(a))))

        assertEquals(listOf("D", "B", "A", "C", "E"), detail.items.map { it.title })
        assertEquals(listOf("E", "D", "C", "B", "A"), controller.setOrder(andre, list.id!!.value, SetOrderRequest(movies.reversed().map(::ref))).items.map { it.title })
        assertEquals("C", c.originalTitle.value)
    }

    @Test
    fun `PUT order refuses unknown, repeated or incomplete titles and changes nothing`() {
        val (list, movies) = list("A", "B", "C")
        val outsider = f.p.movie("Not in it")
        val id = list.id!!.value

        val notIn = error { controller.setOrder(andre, id, SetOrderRequest(listOf(ref(movies[2]), ref(outsider), TitleRefInput(999_999, "movie")))) }
        assertEquals("not_in_list", notIn.code)
        assertTrue(notIn.message.contains("movie/${outsider.tmdbId.value}") && notIn.message.contains("movie/999999"), notIn.message)
        assertEquals("invalid_parameter", error { controller.setOrder(andre, id, SetOrderRequest(listOf(ref(movies[1]), ref(movies[1])))) }.code)
        assertEquals("invalid_parameter", error { controller.setOrder(andre, id, SetOrderRequest(listOf(TitleRefInput(tmdbId = movies[0].tmdbId.value)))) }.code)
        assertEquals("invalid_parameter", error { controller.setOrder(andre, id, SetOrderRequest(listOf(TitleRefInput(type = "movie")))) }.code)
        val tooMany = error { controller.setOrder(andre, id, SetOrderRequest(List(ApiListController.MAX_ORDER_ITEMS + 1) { ref(movies[0]) })) }
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, tooMany.status)

        assertEquals(listOf("A", "B", "C"), titles(list))
        assertEquals(0, listOrder.rowsWritten)
    }

    // --- Ownership ---

    @Test
    fun `an official list is readable by anyone but only its owner reorders it`() {
        val (list, movies) = list("A", "B", owner = admin)
        publishAsOfficial(list)
        val id = list.id!!.value

        assertEquals(listOf(1, 2), controller.list(andre, id).items.map { it.position }, "readable, with positions")
        listOf<() -> Unit>(
            { controller.moveItem(andre, id, "movie", movies[1].tmdbId.value, MoveItemRequest(position = 1)) },
            { controller.setOrder(andre, id, SetOrderRequest(listOf(ref(movies[1])))) },
            { controller.addItems(andre, id, AddItemsRequest(listOf(input(f.p.movie("New"), 1)))) },
        ).forEach { call -> assertEquals(HttpStatus.FORBIDDEN, error(call).status) }

        assertEquals(listOf("A", "B"), controller.list(admin, id).items.map { it.title })
        assertEquals(listOf("B", "A"), controller.moveItem(admin, id, "movie", movies[1].tmdbId.value, MoveItemRequest(position = 1)).items.map { it.title }, "the owner can")
    }

    @Test
    fun `other people's ordinary lists stay a 404, even for admins`() {
        val (list, movies) = list("A", "B")
        val id = list.id!!.value

        assertEquals(HttpStatus.NOT_FOUND, error { controller.moveItem(admin, id, "movie", movies[1].tmdbId.value, MoveItemRequest(position = 1)) }.status)
        assertEquals(HttpStatus.NOT_FOUND, error { controller.setOrder(admin, id, SetOrderRequest(listOf(ref(movies[1])))) }.status)
        assertEquals(listOf("A", "B"), titles(list))
    }
}
