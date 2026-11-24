package org.ratelog.api.people

import org.ratelog.annotations.CurrentUser
import org.ratelog.api.ApiException
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.notFound
import org.ratelog.api.posterUrl
import org.ratelog.person.FilmographySort
import org.ratelog.person.FilmographyType
import org.ratelog.person.PersonSummary
import org.ratelog.person.detail.GetPersonError
import org.ratelog.person.detail.GetPersonHandler
import org.ratelog.person.detail.GetPersonQuery
import org.ratelog.user.User
import org.ratelog.watchlist.seen.ReleaseRules
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

data class PersonResponse(
    val tmdbId: Int,
    val name: String,
    val url: String,
    val profileUrl: String?,
    val knownForDepartment: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val biography: String?,
    /** Every department with credits, in the page's tab order, each counted in distinct titles. */
    val departments: List<PersonDepartmentDto>,
    /** The department, type and sort [credits] are for (`department` is null when the person has no credits). */
    val department: String?,
    val type: String,
    val sort: String,
    val credits: List<PersonCreditDto>,
)

data class PersonDepartmentDto(val department: String, val name: String, val count: Int)

data class PersonCreditDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val releaseDate: String?,
    val posterUrl: String?,
    /** Characters (Acting) or jobs, one entry per title: several jobs on one title are listed together. */
    val roles: List<String>,
    val upcoming: Boolean,
    /** Your status (`want_to_watch`, `watching`, `watched`, `dropped`) and score, null when none. */
    val status: String?,
    val score: Double?,
)

data class PersonSummaryDto(
    val tmdbId: Int,
    val name: String,
    val url: String,
    val profileUrl: String?,
    val knownForDepartment: String?,
    val knownFor: List<String>,
)

fun PersonSummary.toDto() = PersonSummaryDto(
    tmdbId = id,
    name = name,
    url = "/person/$id",
    profileUrl = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" },
    knownForDepartment = knownForDepartment,
    knownFor = knownFor,
)

/** Fork: a person and their filmography, the data of the person page (`/person/{id}`). */
@RestController
@RequestMapping("/api/v1/people")
class ApiPeopleController(
    private val handler: GetPersonHandler,
) {
    private val clock: Clock = Clock.systemDefaultZone()

    @GetMapping("/{id}")
    fun person(
        @CurrentUser user: User,
        @PathVariable id: Int,
        @RequestParam(required = false) department: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) sort: String?,
    ): PersonResponse {
        val filmType = when (type?.trim()?.lowercase()) {
            null, "", "all" -> FilmographyType.ALL
            "movie", "movies" -> FilmographyType.MOVIE
            "tv", "tvshow", "show", "series" -> FilmographyType.TV
            else -> throw badRequest("invalid_type", "type must be \"all\", \"movie\" or \"tv\"")
        }
        val filmSort = when (sort?.trim()?.lowercase()) {
            null, "", "newest" -> FilmographySort.NEWEST
            "popular" -> FilmographySort.POPULAR
            else -> throw badRequest("invalid_parameter", "sort must be \"newest\" or \"popular\"")
        }
        val page = handler.handle(
            GetPersonQuery(personId = id, userId = user.id, lang = user.metadataLang, department = department, type = filmType, sort = filmSort)
        ).fold({ error ->
            throw when (error) {
                GetPersonError.NotFound -> notFound("Person $id not found")
                GetPersonError.TmdbNotConfigured -> ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB API key is not configured (Admin > Configuration)")
                GetPersonError.TmdbFailed -> ApiException(HttpStatus.BAD_GATEWAY, "tmdb_error", "TMDB lookup failed")
            }
        }, { it })
        val today = LocalDate.now(clock)
        val person = page.person
        return PersonResponse(
            tmdbId = person.id,
            name = person.name,
            url = "/person/${person.id}",
            profileUrl = person.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" },
            knownForDepartment = person.knownForDepartment,
            birthday = person.birthday?.toString(),
            deathday = person.deathday?.toString(),
            placeOfBirth = person.placeOfBirth,
            biography = person.biography,
            departments = page.departments.map { PersonDepartmentDto(it.slug, it.name, it.count) },
            department = page.department?.slug,
            type = page.type.param ?: "all",
            sort = page.sort.param ?: "newest",
            credits = page.entries.map { entry ->
                val mark = page.marks[entry.type to entry.tmdbId]
                PersonCreditDto(
                    tmdbId = entry.tmdbId,
                    type = entry.type.apiName(),
                    title = entry.title,
                    year = entry.year,
                    releaseDate = entry.releaseDate?.toString(),
                    posterUrl = entry.posterPath?.let(::posterUrl),
                    roles = entry.roles,
                    upcoming = ReleaseRules.isUpcoming(entry.releaseDate, today),
                    status = mark?.status?.apiName,
                    score = mark?.score,
                )
            },
        )
    }
}
