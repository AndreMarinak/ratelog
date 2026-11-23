package org.ratelog.extras.mdblist

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.ratelog.extras.ExternalScores
import org.ratelog.extras.TmdbExtrasClient
import kotlin.math.roundToInt

/**
 * Reads `GET /tmdb/{movie|show}/{id}`. The OpenAPI spec only types `ratings` as an array of objects;
 * in practice each entry is `{source, value, score, votes, url}` where `value` is on the source's own scale,
 * `score` is normalized to 0-100, and numbers sometimes arrive as strings, `""` or `"N/A"`.
 * Everything is parsed leniently: unknown sources are ignored, odd values become null.
 */
object MdbListParser {
    private val mapper = ObjectMapper()

    /** Null when the body isn't a JSON object (or is an `{"error": ...}` without ratings). */
    fun parse(json: String): ExternalScores? {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (!root.isObject) return null
        val ratings = root.path("ratings")
        if (!ratings.isArray && root.hasNonNull("error")) return null

        val bySource = ratings.filter { it.isObject }
            .mapNotNull { entry -> entry.text("source")?.lowercase()?.let { it to entry } }
            .groupBy({ it.first }, { it.second })

        fun entry(vararg sources: String): JsonNode? = sources.firstNotNullOfOrNull { bySource[it]?.firstOrNull() }

        val imdb = entry("imdb")
        return ExternalScores(
            imdbId = TmdbExtrasClient.cleanImdbId(root.text("imdb_id"))
                ?: TmdbExtrasClient.cleanImdbId(root.path("ids").text("imdb"))
                ?: TmdbExtrasClient.cleanImdbId(root.text("imdbid")),
            imdbRating = imdb?.let { tenPointScale(it) },
            imdbVotes = imdb?.takeIf { tenPointScale(it) != null }?.let { votes(it) },
            rtCritics = entry("tomatoes")?.let { percent(it) },
            rtAudience = entry("popcorn", "tomatoesaudience", "audience")?.let { percent(it) },
            metacritic = entry("metacritic")?.let { percent(it) },
            letterboxd = entry("letterboxd")?.let { fivePointScale(it) },
        )
    }

    private fun tenPointScale(entry: JsonNode): Double? {
        val value = entry.number("value") ?: entry.number("score")?.div(10.0)
        return value?.takeIf { it > 0.0 && it <= 10.0 }?.let { round1(it) }
    }

    private fun fivePointScale(entry: JsonNode): Double? {
        val value = entry.number("value") ?: entry.number("score")?.div(20.0)
        return value?.takeIf { it > 0.0 && it <= 5.0 }?.let { Math.round(it * 100.0) / 100.0 }
    }

    private fun percent(entry: JsonNode): Int? {
        val value = entry.number("value") ?: entry.number("score") ?: return null
        if (value < 0.0 || value > 100.0) return null
        // A 0 with no votes behind it is "not rated yet", not a real 0%.
        if (value == 0.0 && (votes(entry) ?: 0L) == 0L) return null
        return value.roundToInt()
    }

    private fun votes(entry: JsonNode): Long? = entry.number("votes")?.toLong()?.takeIf { it >= 0 }

    private fun round1(value: Double) = Math.round(value * 10.0) / 10.0

    private fun JsonNode.text(field: String): String? =
        get(field)?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonNode.number(field: String): Double? {
        val node = get(field) ?: return null
        return when {
            node.isNumber -> node.asDouble()
            node.isTextual -> node.asText().trim().replace(",", "").removeSuffix("%").toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }
    }
}
