package org.ratelog.api.lists

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.official.browse.GetOfficialListsHandler
import org.ratelog.user.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** An official list with the token user's own progress. Open it with `GET /api/v1/lists/{id}` (any token). */
data class OfficialListDto(
    val id: Long,
    val name: String,
    val isPublic: Boolean,
    val isOfficial: Boolean,
    val itemCount: Int,
    val url: String,
    /** The account that owns it (only the owner can change its titles; admins can un-mark it). */
    val owner: String,
    val progress: ListProgressDto,
    /** The token user saved it (fork, saved lists 2026-11-24); saved lists come first. */
    val saved: Boolean = false,
)

/**
 * `GET /api/v1/lists/official` (fork): every official list, the token user's saved ones first (newest save first), then
 * the rest most recently active first. The literal path wins over `/{id}`.
 */
@RestController
@RequestMapping("/api/v1/lists")
class ApiOfficialListController(
    private val handler: GetOfficialListsHandler,
) {
    @GetMapping("/official")
    fun official(@CurrentUser user: User): List<OfficialListDto> =
        handler.handle(user.id!!).map { (list, seen, saved) ->
            OfficialListDto(
                id = list.id.value,
                name = list.name,
                isPublic = list.isPublic,
                isOfficial = true,
                itemCount = list.itemCount,
                url = "/list/${list.id.value}",
                owner = list.ownerUsername,
                progress = ListProgressDto(seen = seen, total = list.itemCount),
                saved = saved,
            )
        }
}
