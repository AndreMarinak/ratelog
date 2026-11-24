package org.ratelog.person.detail.web

import jakarta.servlet.http.HttpServletResponse
import org.ratelog.annotations.CurrentUser
import org.ratelog.person.FilmographySort
import org.ratelog.person.FilmographyType
import org.ratelog.person.detail.GetPersonError
import org.ratelog.person.detail.GetPersonHandler
import org.ratelog.person.detail.GetPersonQuery
import org.ratelog.user.User
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.LocalDate

/**
 * Fork: `/person/{tmdbPersonId}` (docs/design-decisions.md "People: person pages and People in search"): photo, known
 * for, dates, a short bio, then the filmography with department / type / sort filters (links that view-swap.js applies
 * in place, replacing the URL). Reached from People in search, and from the credits on title pages when
 * `ratelog.features.people` is on.
 */
@Controller
class PersonController(
    private val handler: GetPersonHandler,
) {
    private val clock: Clock = Clock.systemDefaultZone()

    @GetMapping("/person/{id}")
    fun person(
        @CurrentUser user: User,
        @PathVariable id: Int,
        @RequestParam("dept", required = false) dept: String?,
        @RequestParam("type", required = false) type: String?,
        @RequestParam("sort", required = false) sort: String?,
        @RequestParam("all", required = false) all: String?,
        model: Model,
        response: HttpServletResponse,
    ): String {
        val query = GetPersonQuery(
            personId = id,
            userId = user.id,
            lang = user.metadataLang,
            department = dept,
            type = FilmographyType.parse(type),
            sort = FilmographySort.parse(sort),
        )
        return handler.handle(query).fold(
            { error ->
                when (error) {
                    GetPersonError.NotFound -> {
                        response.status = HttpStatus.NOT_FOUND.value()
                        "not-found"
                    }
                    GetPersonError.TmdbNotConfigured, GetPersonError.TmdbFailed -> {
                        response.status = HttpStatus.BAD_GATEWAY.value()
                        model.addAttribute("badKey", false)
                        "tmdb-error"
                    }
                }
            },
            { page ->
                model.addAttribute("person", PersonPageView.of(page, all == "1", LocalDate.now(clock), LocaleContextHolder.getLocale()))
                "person"
            },
        )
    }
}
