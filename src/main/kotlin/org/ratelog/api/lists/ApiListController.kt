package org.ratelog.api.lists

import org.ratelog.annotations.CurrentUser
import org.ratelog.api.AddItemsRequest
import org.ratelog.api.ApiException
import org.ratelog.api.CreateListRequest
import org.ratelog.api.ItemInput
import org.ratelog.api.TitleDto
import org.ratelog.api.UpdateListRequest
import org.ratelog.api.badRequest
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

data class ListSummaryDto(val id: Long, val name: String, val isPublic: Boolean, val itemCount: Int, val url: String)

data class ListItemDto(val position: Int, val tmdbId: Int, val type: String, val title: String, val year: Int?, val posterUrl: String?)

data class ListDetailDto(
    val id: Long,
    val name: String,
    val isPublic: Boolean,
    val url: String,
    val items: List<ListItemDto>,
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
) {
    @GetMapping
    fun lists(@CurrentUser user: User): List<ListSummaryDto> =
        customListRepository.findByUserId(user.id!!).map {
            ListSummaryDto(it.id!!.value, it.name.value, it.isPublic, it.items.size, "/list/${it.id.value}")
        }

    @GetMapping("/{id}")
    fun list(@CurrentUser user: User, @PathVariable id: Long): ListDetailDto = detail(ownedList(user, id), user)

    @PostMapping
    fun create(@CurrentUser user: User, @RequestBody body: CreateListRequest): ResponseEntity<CreateListResponse> {
        checkBatchSize(body.items)
        val list = createListHandler.handle(CreateListCommand(user.id!!, body.name.orEmpty(), body.isPublic)).fold(
            { error ->
                throw when (error) {
                    CreateListError.InvalidListName -> badRequest("invalid_name", "name must be 1-100 characters")
                    CreateListError.ListLimitExceeded -> badRequest("list_limit", "List limit reached (${CreateListHandler.MAX_LISTS_PER_USER} per user)")
                }
            },
            { it }
        )
        val itemsResult = if (body.items.isEmpty()) null
        else addListItemsService.addItems(list.id!!, user.id, user.metadataLang, body.items)

        val fresh = customListRepository.findById(list.id!!)!!
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateListResponse(detail(fresh, user), itemsResult))
    }

    @PatchMapping("/{id}")
    fun update(@CurrentUser user: User, @PathVariable id: Long, @RequestBody body: UpdateListRequest): ListDetailDto {
        val list = ownedList(user, id)
        updateListHandler.handle(
            UpdateListCommand(list.id!!, user.id!!, body.name ?: list.name.value, body.isPublic ?: list.isPublic)
        ).onLeft { error ->
            throw when (error) {
                UpdateListError.InvalidListName -> badRequest("invalid_name", "name must be 1-100 characters")
                UpdateListError.ListNotFound, UpdateListError.NotOwner -> notFound("List $id not found")
            }
        }
        return detail(customListRepository.findById(list.id)!!, user)
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

    /** Other users' lists are reported as missing so list ids can't be probed. */
    private fun ownedList(user: User, id: Long): CustomList {
        val list = customListRepository.findById(CustomList.Id(id))
        if (list == null || list.userId != user.id) throw notFound("List $id not found")
        return list
    }

    private fun detail(list: CustomList, user: User): ListDetailDto {
        val items = list.items.sortedBy { it.position }.mapNotNull { item ->
            mediaImporter.findLocalById(item.mediaId, item.mediaType, user.metadataLang)?.let { media: org.ratelog.api.media.LocalMedia ->
                val dto: TitleDto = media.toDto()
                ListItemDto(item.position, dto.tmdbId, dto.type, dto.title, dto.year, dto.posterUrl)
            }
        }
        return ListDetailDto(list.id!!.value, list.name.value, list.isPublic, "/list/${list.id.value}", items)
    }

    private fun checkBatchSize(items: List<ItemInput>) {
        if (items.size > MAX_ITEMS_PER_REQUEST) {
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_items", "Send at most $MAX_ITEMS_PER_REQUEST items per request")
        }
    }

    companion object {
        const val MAX_ITEMS_PER_REQUEST = 100
    }
}
