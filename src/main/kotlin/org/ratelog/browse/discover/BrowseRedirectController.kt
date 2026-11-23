package org.ratelog.browse.discover

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.browse.BrowseFilters
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Browse was renamed Discover: `/browse?...` answers 301 to `/discover?...` with the query string unchanged, so old
 * links and bookmarks open the same view. Only the path is fixed here, so the redirect can't leave the app.
 */
@Controller
class BrowseRedirectController {
    @GetMapping("/browse")
    fun browse(request: HttpServletRequest): ResponseEntity<Void> {
        val query = request.queryString?.takeIf { it.isNotBlank() && it.none { c -> c == '\r' || c == '\n' } }
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
            .header(HttpHeaders.LOCATION, BrowseFilters.PATH + (query?.let { "?$it" } ?: ""))
            .build()
    }
}
