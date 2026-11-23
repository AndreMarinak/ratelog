package org.ratelog.api

import arrow.core.getOrElse
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.MatchResult
import org.ratelog.api.media.TitleResolver
import org.ratelog.tmdb.TmdbError
import org.ratelog.user.User
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class MeResponse(val id: Long, val username: String, val role: String)

data class SearchResponse(val results: List<CandidateDto>)

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
) {
    @GetMapping("/me")
    fun me(@CurrentUser user: User) = MeResponse(user.id!!.value, user.username.value, user.role.name)

    /** Raw TMDB search, for looking up ids by hand. */
    @GetMapping("/search")
    fun search(
        @RequestParam q: String,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) year: Int?,
    ): SearchResponse {
        if (q.isBlank()) throw badRequest("invalid_parameter", "q must not be blank")
        val results = titleResolver.search(q.trim(), parseMediaType(type)).getOrElse { throw tmdbFailure(it) }
        val filtered = if (year == null) results else results.filter { it.year == null || kotlin.math.abs(it.year - year) <= 1 }
        return SearchResponse(filtered.map { it.toDto() })
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
