package org.ratelog.tile

import jakarta.servlet.http.HttpServletResponse
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.MediaImporter
import org.ratelog.customlist.CustomListRepository
import org.ratelog.customlist.membership.ListMembershipRepository
import org.ratelog.customlist.membership.web.ListMembershipView
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.ratelog.watchlist.seen.parseTileType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * GET /tile/lists?tmdbId=603&type=movie[&back=/discover]: the title page's add-to-list sheet
 * (`fragments/add-to-list-modal.html`, with its ticks, remove confirm and "New list"), for a poster tile. Tiles only
 * know the TMDB id, so the title is saved locally first if needed, like the tile's bookmark. static/tile-rate.js
 * loads it when the tile's list button is tapped and opens it in place; every toggle in it is the sheet's own
 * POST /list/{id}/membership. `back` (a local path) is where those forms return without JS.
 */
@Controller
class TileListSheetController(
    private val mediaImporter: MediaImporter,
    private val customListRepository: CustomListRepository,
    private val membershipRepository: ListMembershipRepository,
) {
    @GetMapping("/tile/lists")
    fun sheet(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type") type: String,
        @RequestParam("back", required = false) back: String?,
        model: Model,
        response: HttpServletResponse,
    ): String {
        val mediaType = parseTileType(type)
        val media = if (mediaType == null || tmdbId <= 0) null else mediaImporter.ensure(tmdbId, mediaType, user.metadataLang).getOrNull()
        if (mediaType == null || media == null) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            return "fragments/tile-rate :: listSheetMissing"
        }
        val lists = customListRepository.findByUserId(user.id!!)
        val inLists = if (lists.isEmpty()) emptyList() else membershipRepository.findListsContaining(user.id, mediaType, media.id)
        val view = ListMembershipView.of(mediaType, media.id, media.tmdbId, lists, inLists)
        model.addAttribute("listMembership", BackPath.local(back, keepFragment = true)?.let { view.copy(back = it) } ?: view)
        model.addAttribute("sheetMediaId", media.id)
        model.addAttribute("sheetMediaType", mediaType.name)
        model.addAttribute("sheetKey", (if (mediaType.name == "movie") "movie" else "tv") + ":" + media.tmdbId)
        model.addAttribute("sheetTitle", media.title)
        return "fragments/tile-rate :: listSheet"
    }
}
