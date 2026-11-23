package org.ratelog.api.media

import org.ratelog.MediaType
import java.text.Normalizer
import kotlin.math.abs

data class Candidate(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val tmdbVoteAverage: Double?,
)

sealed interface MatchResult {
    data class Matched(val candidate: Candidate) : MatchResult
    /** Several titles fit equally well: pass a year, a type, or the tmdbId to pick one. */
    data class Ambiguous(val candidates: List<Candidate>) : MatchResult
    /** Nothing matched the title closely enough; [candidates] are TMDB's top results for reference. */
    data class NotFound(val candidates: List<Candidate>) : MatchResult
}

/**
 * Decides which TMDB search result a "Title (Year)" refers to. Deliberately strict:
 * a wrong title silently added to a list is worse than asking the caller to disambiguate.
 */
object TitleMatcher {

    fun match(title: String, year: Int?, candidates: List<Candidate>): MatchResult {
        val wanted = normalize(title)
        val titleMatches = candidates.filter { wanted == normalize(it.title) || wanted == normalize(it.originalTitle) }

        if (year == null) {
            return when (titleMatches.size) {
                0 -> MatchResult.NotFound(candidates.take(MAX_SUGGESTIONS))
                1 -> MatchResult.Matched(titleMatches.single())
                else -> MatchResult.Ambiguous(titleMatches)
            }
        }

        val exactYear = titleMatches.filter { it.year == year }
        if (exactYear.size == 1) return MatchResult.Matched(exactYear.single())
        if (exactYear.size > 1) return MatchResult.Ambiguous(exactYear)

        // Release years differ by country/festival vs. wide release, so allow one year of slack.
        val nearYear = titleMatches.filter { it.year != null && abs(it.year - year) <= 1 }
        return when (nearYear.size) {
            0 -> MatchResult.NotFound((titleMatches + candidates).distinct().take(MAX_SUGGESTIONS))
            1 -> MatchResult.Matched(nearYear.single())
            else -> MatchResult.Ambiguous(nearYear)
        }
    }

    /** Lowercase, strip accents and punctuation, "&" -> "and", drop a leading article. */
    fun normalize(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val words = ascii.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return words.removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()
    }

    private const val MAX_SUGGESTIONS = 5
}
