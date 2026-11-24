package org.ratelog.api.lists

import org.ratelog.annotations.CurrentUser
import org.ratelog.api.ApiException
import org.ratelog.api.notFound
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.saved.browse.GetSavedListsHandler
import org.ratelog.customlist.saved.set.SetListSaved
import org.ratelog.customlist.saved.set.SetListSavedError
import org.ratelog.customlist.saved.set.SetListSavedHandler
import org.ratelog.user.User
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** One of the token user's saved lists, with their progress. */
data class SavedListDto(
    val id: Long,
    val name: String,
    val isOfficial: Boolean,
    val itemCount: Int,
    val url: String,
    val owner: String,
    val progress: ListProgressDto,
    /** ISO-8601 instant of the save. */
    val savedAt: String,
)

data class ListSavedDto(val id: Long, val saved: Boolean, val changed: Boolean)

/**
 * Saved lists (fork, 2026-11-24): save an official list like a playlist; it then leads the Official lists sections.
 *  - `GET /api/v1/lists/saved`: the saved lists you can still see (official and public), newest save first. The literal
 *    path wins over `/{id}`.
 *  - `PUT /api/v1/lists/{id}/saved`: save it (idempotent). 400 `not_official` for any other list.
 *  - `DELETE /api/v1/lists/{id}/saved`: un-save it (idempotent), whatever the list's state now.
 */
@RestController
@RequestMapping("/api/v1/lists")
class ApiSavedListController(
    private val setHandler: SetListSavedHandler,
    private val listHandler: GetSavedListsHandler,
) {
    @GetMapping("/saved")
    fun saved(@CurrentUser user: User): List<SavedListDto> =
        listHandler.handle(user.id!!).map { (list, seen, savedAt) ->
            SavedListDto(
                id = list.id.value,
                name = list.name,
                isOfficial = list.isOfficial,
                itemCount = list.itemCount,
                url = "/list/${list.id.value}",
                owner = list.ownerUsername,
                progress = ListProgressDto(seen = seen, total = list.itemCount),
                savedAt = java.time.Instant.ofEpochMilli(savedAt).toString(),
            )
        }

    @PutMapping("/{id}/saved")
    fun save(@CurrentUser user: User, @PathVariable id: Long): ListSavedDto = set(user, id, saved = true)

    @DeleteMapping("/{id}/saved")
    fun unsave(@CurrentUser user: User, @PathVariable id: Long): ResponseEntity<ListSavedDto> =
        ResponseEntity.ok(set(user, id, saved = false))

    private fun set(user: User, id: Long, saved: Boolean): ListSavedDto =
        setHandler.handle(SetListSaved(CustomList.Id(id), user.id!!, saved)).fold(
            { error ->
                throw when (error) {
                    SetListSavedError.ListNotFound -> notFound("List $id not found")
                    SetListSavedError.NotSaveable ->
                        ApiException(HttpStatus.BAD_REQUEST, "not_official", "Only official, public lists can be saved")
                }
            },
            { ListSavedDto(id = id, saved = it.saved, changed = it.changed) },
        )
}
