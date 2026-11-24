package org.ratelog.api.lists

import org.ratelog.ListName
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.AddItemsRequest
import org.ratelog.api.ApiException
import org.ratelog.api.CreateListRequest
import org.ratelog.api.ItemInput
import org.ratelog.api.MoveItemRequest
import org.ratelog.api.SetOrderRequest
import org.ratelog.api.apiName
import org.ratelog.api.TitleDto
import org.ratelog.api.UpdateListRequest
import org.ratelog.api.badRequest
import org.ratelog.api.forbidden
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.notFound
import org.ratelog.api.parseMediaType
import org.ratelog.api.toDto
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.CustomListRepository
import org.ratelog.customlist.create.CreateListCommand
import org.ratelog.customlist.create.CreateListError
import org.ratelog.customlist.create.CreateListHandler
import org.ratelog.customlist.create.ListLimitPolicy
import org.ratelog.customlist.delete.DeleteListCommand
import org.ratelog.customlist.delete.DeleteListHandler
import org.ratelog.customlist.official.OfficialListRepository
import org.ratelog.customlist.order.ListOptionsRepository
import org.ratelog.customlist.order.ListOrder
import org.ratelog.customlist.order.move.MoveListItem
import org.ratelog.customlist.order.move.MoveListItemError
import org.ratelog.customlist.order.move.MoveListItemHandler
import org.ratelog.customlist.order.move.MoveTarget
import org.ratelog.customlist.order.numbered.SetListNumbered
import org.ratelog.customlist.order.numbered.SetListNumberedHandler
import org.ratelog.customlist.order.setorder.SetListOrder
import org.ratelog.customlist.order.setorder.SetListOrderError
import org.ratelog.customlist.order.setorder.SetListOrderHandler
import org.ratelog.customlist.official.mark.SetListOfficial
import org.ratelog.customlist.official.mark.SetListOfficialError
import org.ratelog.customlist.official.mark.SetListOfficialHandler
import org.ratelog.customlist.progress.GetListProgressHandler
import org.ratelog.customlist.removeitem.RemoveFromListCommand
import org.ratelog.customlist.removeitem.RemoveFromListHandler
import org.ratelog.customlist.update.UpdateListCommand
import org.ratelog.customlist.update.UpdateListError
import org.ratelog.customlist.update.UpdateListHandler
import org.ratelog.user.User
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ListSummaryDto(
    val id: Long,
    val name: String,
    val isPublic: Boolean,
    /** An admin-curated list every account can read (fork). */
    val isOfficial: Boolean,
    val itemCount: Int,
    val url: String,
    /** Fork: the list page shows ranks (false = a plain collection; the order is kept either way). */
    val numbered: Boolean = true,
)

data class ListItemDto(
    /** 1-based place in the list (fork: contiguous; it used to be the stored sort key, with gaps after removals). */
    val position: Int,
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    /** The token user's status (`want_to_watch`, `watching`, `watched`, `dropped`), or null. */
    val status: String? = null,
    /** The token user's score, or null if unrated. */
    val score: Double? = null,
    /** Rated, or status `watched`. */
    val seen: Boolean = false,
    /** Fork (2026-11-24): on your watchlist, independent of `status` (a `watched` title can be on it too, to rewatch). */
    val onWatchlist: Boolean = false,
)

/** How many of the list's items the token user has seen. */
data class ListProgressDto(val seen: Int, val total: Int)

data class ListDetailDto(
    val id: Long,
    val name: String,
    val isPublic: Boolean,
    val isOfficial: Boolean,
    val url: String,
    val items: List<ListItemDto>,
    val progress: ListProgressDto,
    /** Fork: see [ListSummaryDto.numbered]. */
    val numbered: Boolean = true,
)

data class CreateListResponse(val list: ListDetailDto, val items: AddItemsResult?)

@RestController
@RequestMapping("/api/v1/lists")
class ApiListController(
    private val customListRepository: CustomListRepository,
    private val createListHandler: CreateListHandler,
    private val updateListHandler: UpdateListHandler,
    private val deleteListHandler: DeleteListHandler,
    private val removeFromListHandler: RemoveFromListHandler,
    private val addListItemsService: AddListItemsService,
    private val mediaImporter: MediaImporter,
    private val getListProgressHandler: GetListProgressHandler,
    private val officialLists: OfficialListRepository,
    private val setListOfficialHandler: SetListOfficialHandler,
    private val listOptions: ListOptionsRepository,
    private val setListNumberedHandler: SetListNumberedHandler,
    private val moveListItemHandler: MoveListItemHandler,
    private val setListOrderHandler: SetListOrderHandler,
) {
    /** Your own lists. Everyone's official lists are at `GET /api/v1/lists/official`. */
    @GetMapping
    fun lists(@CurrentUser user: User): List<ListSummaryDto> {
        val lists = customListRepository.findByUserId(user.id!!)
        val ids = lists.mapNotNull { it.id }
        val official = officialLists.officialAmong(ids)
        val plain = listOptions.plainAmong(ids)
        return lists.map {
            ListSummaryDto(it.id!!.value, it.name.value, it.isPublic, it.id in official, it.items.size, "/list/${it.id.value}", it.id !in plain)
        }
    }

    /** Your lists, and public official lists (any token can read those). */
    @GetMapping("/{id}")
    fun list(@CurrentUser user: User, @PathVariable id: Long): ListDetailDto = detail(readableList(user, id), user)

    @PostMapping
    fun create(@CurrentUser user: User, @RequestBody body: CreateListRequest): ResponseEntity<CreateListResponse> {
        checkBatchSize(body.items)
        val official = body.isOfficial == true
        // Checked before anything is created, so a refused request leaves no list behind.
        if (official && !user.role.isAdminLike) throw forbidden(ADMIN_ONLY)
        // Official never implies public (2026-11-23): an official list created private is hidden until made public.
        val isPublic = body.isPublic == true
        val list = createListHandler.handle(CreateListCommand(user.id!!, body.name.orEmpty(), isPublic)).fold(
            { error ->
                throw when (error) {
                    CreateListError.InvalidListName -> badRequest("invalid_name", "name must be 1-100 characters")
                    CreateListError.ListLimitExceeded -> badRequest("list_limit", "List limit reached (${ListLimitPolicy.USER_MAX} per account, ${ListLimitPolicy.ADMIN_MAX} for admins)")
                }
            },
            { it }
        )
        if (official) setOfficial(list.id!!, user, true)
        if (body.numbered == false) setNumbered(list.id!!, user, false)
        val itemsResult = if (body.items.isEmpty()) null
        else addListItemsService.addItems(list.id!!, user.id, user.metadataLang, body.items)

        val fresh = customListRepository.findById(list.id!!)!!
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateListResponse(detail(fresh, user), itemsResult))
    }

    /**
     * `name` / `isPublic`: the owner only. `isOfficial`: admin tokens only, on any list; sending its current value is
     * a no-op for anyone (so a GET response can be sent back). Everything is checked before anything changes.
     */
    @PatchMapping("/{id}")
    fun update(@CurrentUser user: User, @PathVariable id: Long, @RequestBody body: UpdateListRequest): ListDetailDto {
        val list = customListRepository.findById(CustomList.Id(id)) ?: throw notFound("List $id not found")
        val listId = list.id!!
        val isOwner = list.userId == user.id
        val isAdmin = user.role.isAdminLike
        val official = officialLists.isOfficial(listId)
        val changesOfficial = body.isOfficial != null && body.isOfficial != official
        // An admin can mark any list and un-mark any official one, hidden (private) ones included.
        val adminReach = isAdmin && (body.isOfficial == true || (official && body.isOfficial == false))

        // Others' lists stay hidden (404) unless they are public official lists, or an admin is marking / un-marking.
        if (!isOwner && !(official && list.isPublic) && !adminReach) throw notFound("List $id not found")
        if (changesOfficial && !isAdmin) throw forbidden(ADMIN_ONLY)
        val editsList = body.name != null || body.isPublic != null
        if ((editsList || body.numbered != null) && !isOwner) {
            throw forbidden("Only the list's owner can change its name, visibility or numbering")
        }
        if (body.name != null && ListName.parse(body.name).isLeft()) throw badRequest("invalid_name", "name must be 1-100 characters")

        // The mark and the visibility are independent (official lists can be private since 2026-11-23): neither
        // step changes the other, so the order doesn't matter.
        if (changesOfficial && body.isOfficial == false) setOfficial(listId, user, false)
        if (editsList) {
            updateListHandler.handle(
                UpdateListCommand(listId, user.id!!, body.name ?: list.name.value, body.isPublic ?: list.isPublic)
            ).onLeft { error ->
                throw when (error) {
                    UpdateListError.InvalidListName -> badRequest("invalid_name", "name must be 1-100 characters")
                    UpdateListError.ListNotFound, UpdateListError.NotOwner -> notFound("List $id not found")
                }
            }
        }
        if (changesOfficial && body.isOfficial == true) setOfficial(listId, user, true)
        body.numbered?.let { setNumbered(listId, user, it) }
        return detail(customListRepository.findById(listId)!!, user)
    }

    @DeleteMapping("/{id}")
    fun delete(@CurrentUser user: User, @PathVariable id: Long): ResponseEntity<Unit> {
        val list = ownedList(user, id)
        deleteListHandler.handle(DeleteListCommand(list.id!!, user.id!!)).onLeft { throw notFound("List $id not found") }
        return ResponseEntity.noContent().build()
    }

    /** Append titles in order. Each item: `{tmdbId, type}` or `{title, year?, type?}`, optionally with a `position` to insert at. */
    @PostMapping("/{id}/items")
    fun addItems(@CurrentUser user: User, @PathVariable id: Long, @RequestBody body: AddItemsRequest): AddItemsResult {
        val list = ownedList(user, id)
        if (body.items.isEmpty()) throw badRequest("invalid_parameter", "items must not be empty")
        checkBatchSize(body.items)
        return addListItemsService.addItems(list.id!!, user.id!!, user.metadataLang, body.items)
    }

    @DeleteMapping("/{id}/items/{type}/{tmdbId}")
    fun removeItem(
        @CurrentUser user: User,
        @PathVariable id: Long,
        @PathVariable type: String,
        @PathVariable tmdbId: Int,
    ): ResponseEntity<Unit> {
        val list = ownedList(user, id)
        val mediaType = parseMediaType(type)!!
        val local = mediaImporter.findLocal(tmdbId, mediaType, user.metadataLang)
            ?: throw notFound("That title is not in this list")
        val item = customListRepository.findItemByListIdAndMediaIdAndMediaType(list.id!!, local.id, mediaType.name)
            ?: throw notFound("That title is not in this list")
        removeFromListHandler.handle(RemoveFromListCommand(list.id, user.id!!, item.id!!))
            .onLeft { throw notFound("That title is not in this list") }
        return ResponseEntity.noContent().build()
    }

    /** Fork: moves one title to a 1-based `position` (past the end = last); the rest shift. Owner only. */
    @PatchMapping("/{id}/items/{type}/{tmdbId}")
    fun moveItem(
        @CurrentUser user: User,
        @PathVariable id: Long,
        @PathVariable type: String,
        @PathVariable tmdbId: Int,
        @RequestBody body: MoveItemRequest,
    ): ListDetailDto {
        val list = ownedList(user, id)
        val position = body.position ?: throw badRequest("invalid_parameter", "position is required (1 = first)")
        if (position < 1) throw badRequest("invalid_parameter", "position must be 1 or more (1 = first)")
        val item = itemOf(list, parseMediaType(type)!!, tmdbId, user) ?: throw notFound("That title is not in this list")
        moveListItemHandler.handle(MoveListItem(list.id!!, user.id!!, item.id!!, MoveTarget.Place(position))).onLeft { error ->
            throw when (error) {
                MoveListItemError.ItemNotFound -> notFound("That title is not in this list")
                MoveListItemError.ListNotFound, MoveListItemError.NotOwner -> notFound("List $id not found")
            }
        }
        return detail(customListRepository.findById(list.id)!!, user)
    }

    /**
     * Fork: sets the order at once. `items` are titles of the list (`{tmdbId, type}`) in their new order; titles left
     * out keep their order after them. Anything unknown or repeated is a 400 and nothing changes. Owner only.
     */
    @PutMapping("/{id}/order")
    fun setOrder(@CurrentUser user: User, @PathVariable id: Long, @RequestBody body: SetOrderRequest): ListDetailDto {
        val list = ownedList(user, id)
        if (body.items.size > MAX_ORDER_ITEMS) {
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_items", "Send at most $MAX_ORDER_ITEMS titles per request")
        }
        val missing = mutableListOf<String>()
        val itemIds = body.items.mapIndexedNotNull { index, ref ->
            val tmdbId = ref.tmdbId?.takeIf { it > 0 } ?: throw badRequest("invalid_parameter", "items[$index] needs a positive tmdbId")
            val mediaType = parseMediaType(ref.type)
                ?: throw badRequest("invalid_parameter", "items[$index] needs a type (\"movie\" or \"tv\")")
            val item = itemOf(list, mediaType, tmdbId, user)
            if (item == null) missing += "${mediaType.apiName()}/$tmdbId"
            item?.id
        }
        if (missing.isNotEmpty()) throw badRequest("not_in_list", "Not in this list: ${missing.joinToString(", ")}")
        setListOrderHandler.handle(SetListOrder(list.id!!, user.id!!, itemIds)).onLeft { error ->
            throw when (error) {
                is SetListOrderError.Duplicates -> badRequest("invalid_parameter", "Each title can appear only once in items")
                is SetListOrderError.NotInList -> badRequest("not_in_list", "Some titles are no longer in this list")
                SetListOrderError.ListNotFound, SetListOrderError.NotOwner -> notFound("List $id not found")
            }
        }
        return detail(customListRepository.findById(list.id)!!, user)
    }

    /** The list's item for a TMDB title, or null if the title isn't in it (or Ratelog has never seen it). */
    private fun itemOf(list: CustomList, type: MediaType, tmdbId: Int, user: User): CustomListItem? {
        val local = mediaImporter.findLocal(tmdbId, type, user.metadataLang) ?: return null
        return list.items.find { it.mediaType == type && it.mediaId == local.id }
    }

    /**
     * For changes. Other users' lists are reported as missing so list ids can't be probed; public official lists are
     * readable by everyone, so changing someone else's is a plain 403. A hidden (private) official list is a 404.
     */
    private fun ownedList(user: User, id: Long): CustomList {
        val list = customListRepository.findById(CustomList.Id(id)) ?: throw notFound("List $id not found")
        if (list.userId != user.id) {
            if (isSharedOfficial(list)) throw forbidden("Only the list's owner can change it")
            throw notFound("List $id not found")
        }
        return list
    }

    /** Your lists and every public official list. */
    private fun readableList(user: User, id: Long): CustomList {
        val list = customListRepository.findById(CustomList.Id(id)) ?: throw notFound("List $id not found")
        if (list.userId != user.id && !isSharedOfficial(list)) throw notFound("List $id not found")
        return list
    }

    /** Official and public: what every account sees. A private official list is hidden, its owner's only. */
    private fun isSharedOfficial(list: CustomList): Boolean = list.isPublic && officialLists.isOfficial(list.id!!)

    private fun setOfficial(listId: CustomList.Id, user: User, official: Boolean) {
        setListOfficialHandler.handle(SetListOfficial(listId, user.id!!, official)).onLeft { error ->
            throw when (error) {
                SetListOfficialError.NotAdmin -> forbidden(ADMIN_ONLY)
                SetListOfficialError.ListNotFound -> notFound("List ${listId.value} not found")
            }
        }
    }

    private fun setNumbered(listId: CustomList.Id, user: User, numbered: Boolean) {
        setListNumberedHandler.handle(SetListNumbered(listId, user.id!!, numbered)).onLeft {
            throw notFound("List ${listId.value} not found")
        }
    }

    private fun detail(list: CustomList, user: User): ListDetailDto {
        // The token user's progress: one marks query per media type, not one per title.
        val progress = getListProgressHandler.progressOf(list, user.id!!).entries.associateBy { it.itemId }
        // Fork: list order (position, then id) and 1-based places, the ranks the list page shows.
        val items = list.items.sortedWith(ListOrder.COMPARATOR).mapIndexedNotNull { index, item ->
            mediaImporter.findLocalById(item.mediaId, item.mediaType, user.metadataLang)?.let { media: org.ratelog.api.media.LocalMedia ->
                val dto: TitleDto = media.toDto()
                val entry = progress[item.id]
                ListItemDto(
                    position = index + 1,
                    tmdbId = dto.tmdbId,
                    type = dto.type,
                    title = dto.title,
                    year = dto.year,
                    posterUrl = dto.posterUrl,
                    status = entry?.let { org.ratelog.watchlist.WatchStatus.effective(it.status, it.onWatchlist) }?.apiName,
                    score = entry?.score,
                    seen = entry?.seen ?: false,
                    onWatchlist = entry?.onWatchlist ?: false,
                )
            }
        }
        return ListDetailDto(
            id = list.id!!.value,
            name = list.name.value,
            isPublic = list.isPublic,
            isOfficial = officialLists.isOfficial(list.id),
            url = "/list/${list.id.value}",
            items = items,
            progress = ListProgressDto(seen = items.count { it.seen }, total = items.size),
            numbered = listOptions.isNumbered(list.id),
        )
    }

    private fun checkBatchSize(items: List<ItemInput>) {
        if (items.size > MAX_ITEMS_PER_REQUEST) {
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_items", "Send at most $MAX_ITEMS_PER_REQUEST items per request")
        }
    }

    companion object {
        const val MAX_ITEMS_PER_REQUEST = 100
        /** `PUT .../order` only reorders (no TMDB lookups), so it takes a whole long list at once. */
        const val MAX_ORDER_ITEMS = 1000
        const val ADMIN_ONLY = "Only admin tokens can change isOfficial"
    }
}
