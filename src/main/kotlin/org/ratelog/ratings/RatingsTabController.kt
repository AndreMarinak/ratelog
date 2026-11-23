package org.ratelog.ratings

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Fork: one "Ratings" tab for both ranking pages (user request, 2026-11-23). `/ratings` opens the movie ranking;
 * `/ratings?type=tv` the TV one. The pages themselves stay at `/movies` and `/tvshows` (upstream), joined by a
 * Movies | TV Shows switch (fragments/ratings-switch.html).
 */
@Controller
class RatingsTabController {

    @GetMapping("/ratings")
    fun ratings(@RequestParam("type", required = false) type: String?): String =
        "redirect:" + target(type)

    companion object {
        fun target(type: String?): String = when (type?.trim()?.lowercase()) {
            "tv", "tvshow", "tvshows", "show", "shows" -> "/tvshows"
            else -> "/movies"
        }
    }
}
