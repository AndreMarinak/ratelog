package org.ratelog.api.lists

import org.ratelog.ListName
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.AddItemsRequest
import org.ratelog.api.ApiException
import org.ratelog.api.CreateListRequest
import org.ratelog.api.ItemInput
import org.ratelog.api.TitleDto
import org.ratelog.api.UpdateListRequest
import org.ratelog.api.badRequest
import org.ratelog.api.forbidden
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.notFound
import org.ratelog.api.parseMediaType
import org.ratelog.api.toDto
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListRepository
import org.ratelog.customlist.create.CreateListCommand
import org.ratelog.customlist.create.CreateListError
import org.ratelog.customlist.create.CreateListHandler
import org.ratelog.customlist.delete.DeleteListCommand
import org.ratelog.customlist.delete.DeleteListHandler
import org.ratelog.customlist.official.OfficialListRepository
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
)

data class ListItemDto(
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
) {
    /** Your own lists. Everyone's official lists are at `GET /api/v1/lists/official`. */
    @GetMapping
    fun lists(@CurrentUser user: User): List<ListSummaryDto> {
        val lists = customListRepository.findByUserId(user.id!!)
        val official = officialLists.officialAmong(lists.mapNotNull { it.id })
        return lists.map {
            ListSummaryDto(it.id!!.value, it.name.value, it.isPublic, it.id in official, it.items.size, "/list/${it.id.value}")
        }
    }

    /** Your lists, and official lists (any token can read those). */
    @GetMapping("/{id}")
    fun list(@CurrentUser user: User, @PathVariable id: Long): ListDetailDto = detail(readableList(user, id), user)

    @PostMapping
    fun create(@CurrentUser user: User, @RequestBody body: CreateListRequest): ResponseEntity<CreateListResponse> {
        checkBatchSize(body.items)
        val official = body.isOfficial == true
        // Checked before anything is created, so a refused request leaves no list behind.
        if (official && !user.role.isAdminLike) throw forbidden(ADMIN_ONLY)
        if (official && body.isPublic == false) throw officialMustBePublic()
        val isPublic = official || body.isPublic == true
        val list = createListHandler.handle(CreateListCommand(user.id!!, body.name.orEmpty(), isPublic)).fold(
            { error ->
                throw when (error) {
                    CreateListError.InvalidListName -> badRequest("invalid_name", "name must be 1-100 characters")
                    CreateListError.ListLimitExceeded -> badRequest("list_limit", "List limit reached (${CreateListHandler.MAX_LISTS_PER_USER} per user)")
                }
            },
            { it }
        )
        if (official) setOfficial(list.id!!, user, true)
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

        // Others' lists stay hidden (404) unless they are official, or an admin is marking one official.
        if (!isOwner && !official && !(isAdmin && body.isOfficial == true)) throw notFound("List $id not found")
        if (changesOfficial && !isAdmin) throw forbidden(ADMIN_ONLY)
        val editsList = body.name != null || body.isPublic != null
        if (editsList && !isOwner) throw forbidden("Only the list's owner can change its name or visibility")
        if ((body.isOfficial ?: official) && body.isPublic == false) throw officialMustBePublic()
        if (body.name != null && ListName.parse(body.name).isLeft()) throw badRequest("invalid_name", "name must be 1-100 characters")

        // Un-mark before a visibility change (official lists can't go private), mark after it.
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
        return detail(customListRepository.findById(listId)!!, user)
    }

    @DeleteMapping("/{id}")
    fun delete(@CurrentUser user: User, @PathVariable id: Long): ResponseEntity<Unit> {
        val list = ownedList(user, id)
        deleteListHandler.handle(DeleteListCommand(list.id!!, user.id!!)).onLeft { throw notFound("List $id not found") }
        return ResponseEntity.noContent().build()
    }

    /** Append titles in order. Each item: `{tmdbId, type}` or `{title, year?, type?}`. */
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

    /**
     * For changes. Other users' lists are reported as missing so list ids can't be probed; official lists are
     * readable by everyone, so changing someone else's is a plain 403.
     */
    private fun ownedList(user: User, id: Long): CustomList {
        val list = customListRepository.findById(CustomList.Id(id)) ?: throw notFound("List $id not found")
        if (list.userId != user.id) {
            if (officialLists.isOfficial(list.id!!)) throw forbidden("Only the list's owner can change it")
            throw notFound("List $id not found")
        }
        return list
    }

    /** Your lists and every official list. */
    private fun readableList(user: User, id: Long): CustomList {
        val list = customListRepository.findById(CustomList.Id(id)) ?: throw notFound("List $id not found")
        if (list.userId != user.id && !officialLists.isOfficial(list.id!!)) throw notFound("List $id not found")
        return list
    }

    private fun setOfficial(listId: CustomList.Id, user: User, official: Boolean) {
        setListOfficialHandler.handle(SetListOfficial(listId, user.id!!, official)).onLeft { error ->
            throw when (error) {
                SetListOfficialError.NotAdmin -> forbidden(ADMIN_ONLY)
                SetListOfficialError.ListNotFound -> notFound("List ${listId.value} not found")
            }
        }
    }

    private fun officialMustBePublic() =
        badRequest("official_list_public", "Official lists are always public: send \"isOfficial\": false first (admin tokens only)")

    private fun detail(list: CustomList, user: User): ListDetailDto {
        // The token user's progress: one marks query per media type, not one per title.
        val progress = getListProgressHandler.progressOf(list, user.id!!).entries.associateBy { it.itemId }
        val items = list.items.sortedBy { it.position }.mapNotNull { item ->
            mediaImporter.findLocalById(item.mediaId, item.mediaType, user.metadataLang)?.let { media: org.ratelog.api.media.LocalMedia ->
                val dto: TitleDto = media.toDto()
                val entry = progress[item.id]
                ListItemDto(
                    position = item.position,
                    tmdbId = dto.tmdbId,
                    type = dto.type,
                    title = dto.title,
                    year = dto.year,
                    posterUrl = dto.posterUrl,
                    status = entry?.status?.apiName,
                    score = entry?.score,
                    seen = entry?.seen ?: false,
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
        )
    }

    private fun checkBatchSize(items: List<ItemInput>) {
        if (items.size > MAX_ITEMS_PER_REQUEST) {
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_items", "Send at most $MAX_ITEMS_PER_REQUEST items per request")
        }
    }

    companion object {
        const val MAX_ITEMS_PER_REQUEST = 100
        const val ADMIN_ONLY = "Only admin tokens can change isOfficial"
    }
}
