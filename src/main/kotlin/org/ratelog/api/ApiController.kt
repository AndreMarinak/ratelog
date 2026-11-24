package org.ratelog.api

import arrow.core.getOrElse
import com.fasterxml.jackson.annotation.JsonInclude
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.people.PersonSummaryDto
import org.ratelog.api.people.toDto
import org.ratelog.person.PersonLookupError
import org.ratelog.person.TmdbPersonClient
import org.ratelog.MediaType
import org.ratelog.api.media.Candidate
import org.ratelog.api.media.MatchResult
import org.ratelog.api.media.TitleResolver
import org.ratelog.browse.BrowseParamParser
import org.ratelog.search.SearchYearFilter
import org.ratelog.tmdb.TmdbError
import org.ratelog.user.User
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

data class MeResponse(val id: Long, val username: String, val role: String)

data class SearchResponse(
    val results: List<CandidateDto>,
    /** Fork (2026-11-24): only with `people=true`: people named like the query (see the People API). */
    @field:JsonInclude(JsonInclude.Include.NON_NULL) @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val people: List<PersonSummaryDto>? = null,
)

data class ResolveResponse(
    /** matched | ambiguous | not_found */
    val status: String,
    val match: CandidateDto?,
    val candidates: List<CandidateDto>,
)

@RestController
@RequestMapping("/api/v1")
class ApiController(
    private val titleResolver: TitleResolver,
    private val personClient: TmdbPersonClient,
) {
    private val clock: Clock = Clock.systemDefaultZone()

    private companion object {
        const val DECADE_PAGES = 5
        const val DECADE_LIMIT = 20
    }

    @GetMapping("/me")
    fun me(@CurrentUser user: User) = MeResponse(user.id!!.value, user.username.value, user.role.name)

    /** Raw TMDB search, for looking up ids by hand. */
    @GetMapping("/search")
    fun search(
        @RequestParam q: String,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) decade: String?,
        @RequestParam(required = false) people: Boolean?,
        @CurrentUser user: User,
    ): SearchResponse {
        if (q.isBlank()) throw badRequest("invalid_parameter", "q must not be blank")
        val mediaType = parseMediaType(type)
        // Fork (2026-11-24): `decade=1980s` like the search page's Year menu; `year` wins when both are sent.
        val range = decade?.takeIf { it.isNotBlank() && year == null }?.let { raw ->
            BrowseParamParser.decadeOrNull(raw, LocalDate.now(clock))?.let { SearchYearFilter.Decade(it) }
                ?: throw badRequest("invalid_parameter", "decade must look like 1980s (1870s up to ten years ahead) or \"earlier\"")
        }
        val filtered = if (range != null) {
            decadeResults(q.trim(), mediaType, range)
        } else {
            val results = titleResolver.search(q.trim(), mediaType).getOrElse { throw tmdbFailure(it) }
            if (year == null) results else results.filter { it.year == null || kotlin.math.abs(it.year - year) <= 1 }
        }
        val found = if (people == true) {
            personClient.search(q.trim(), user.metadataLang).getOrElse { throw personFailure(it) }.map { it.toDto() }
        } else null
        return SearchResponse(filtered.map { it.toDto() }, found)
    }

    /**
     * The titles of a decade among TMDB's first [DECADE_PAGES] result pages (TMDB's search has no date range), stopping
     * at an empty page or once [DECADE_LIMIT] are found. Titles without a year are left out.
     */
    private fun decadeResults(query: String, type: MediaType?, decade: SearchYearFilter.Decade): List<Candidate> {
        val found = mutableListOf<Candidate>()
        for (page in 1..DECADE_PAGES) {
            val results = titleResolver.search(query, type, page).getOrElse { throw tmdbFailure(it) }
            found += results.filter { decade.contains(it.year) }
            if (results.isEmpty() || found.size >= DECADE_LIMIT) break
        }
        return found.distinctBy { it.type to it.tmdbId }
    }

    /** Dry run of the matching used when adding items by title. Saves nothing. */
    @PostMapping("/resolve")
    fun resolve(@RequestBody body: ResolveRequest): ResolveResponse {
        val title = body.title?.trim()
        if (title.isNullOrEmpty()) throw badRequest("invalid_parameter", "title is required")
        return when (val result = titleResolver.resolve(title, body.year, parseMediaType(body.type)).getOrElse { throw tmdbFailure(it) }) {
            is MatchResult.Matched -> ResolveResponse("matched", result.candidate.toDto(), emptyList())
            is MatchResult.Ambiguous -> ResolveResponse("ambiguous", null, result.candidates.map { it.toDto() })
            is MatchResult.NotFound -> ResolveResponse("not_found", null, result.candidates.map { it.toDto() })
        }
    }
}

internal fun tmdbFailure(error: TmdbError) = when (error) {
    TmdbError.ApiKeyMissing -> ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB API key is not configured (Admin > Configuration)")
    else -> ApiException(HttpStatus.BAD_GATEWAY, "tmdb_error", "TMDB lookup failed")
}

internal fun personFailure(error: PersonLookupError) = when (error) {
    PersonLookupError.ApiKeyMissing -> ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB API key is not configured (Admin > Configuration)")
    else -> ApiException(HttpStatus.BAD_GATEWAY, "tmdb_error", "TMDB lookup failed")
}
