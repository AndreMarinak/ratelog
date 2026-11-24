package org.ratelog.extras

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Fork (2026-11-24): who made a title and who is in it, plus the small facts around the overview. Comes with the same TMDB
 * extras request (`credits,release_dates` for movies, `aggregate_credits,content_ratings` for shows) and is cached in
 * `title_extras.credits_json` (V1143). Every field is optional: a fetch always stores a value (possibly empty), so an
 * empty one means "TMDB has nothing", while a null [TmdbExtras.credits] means "cached before credits existed".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class TitleCredits(
    val tagline: String? = null,
    /** US age rating: R, PG-13, TV-MA... (no fallback to other countries: their scales differ). */
    val certification: String? = null,
    val directors: List<CreditPerson> = emptyList(),
    /** Movies: one entry per person, with every writing job they had (screenplay, novel...). */
    val writers: List<CreditPerson> = emptyList(),
    /** Shows only. */
    val createdBy: List<CreditPerson> = emptyList(),
    /** Top billed, at most [MAX_CAST]. */
    val cast: List<CastMember> = emptyList(),
    /** Production companies (movies), at most [MAX_COMPANIES]. */
    val studios: List<String> = emptyList(),
    /** Shows only, at most [MAX_COMPANIES]. */
    val networks: List<String> = emptyList(),
    /** US dollars; movies only, null when TMDB doesn't know (it stores 0). */
    val budget: Long? = null,
    val revenue: Long? = null,
    /** Shows only. */
    val firstAirYear: Int? = null,
    val lastAirYear: Int? = null,
    val numberOfEpisodes: Int? = null,
    /** TMDB's show status: "Returning Series", "Ended", "Canceled", "In Production"... */
    val status: String? = null,
) {
    companion object {
        const val MAX_CAST = 10
        const val MAX_DIRECTORS = 3
        const val MAX_WRITERS = 6
        const val MAX_CREATORS = 4
        const val MAX_COMPANIES = 3

        private val logger = LoggerFactory.getLogger(TitleCredits::class.java)
        private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        fun toJson(credits: TitleCredits): String = mapper.writeValueAsString(credits)

        /** Null for a missing or unreadable value (it is then fetched again, like a row cached before credits). */
        fun fromJson(json: String?): TitleCredits? {
            if (json.isNullOrBlank()) return null
            return try {
                mapper.readValue<TitleCredits>(json)
            } catch (e: Exception) {
                logger.warn("Unreadable cached credits: {}", e.javaClass.simpleName)
                null
            }
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class CreditPerson(
    /** TMDB person id. */
    val id: Int? = null,
    val name: String,
    /** Writers: TMDB jobs in credit order ("Screenplay", "Novel"); empty for directors and creators. */
    val jobs: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class CastMember(
    /** TMDB person id. */
    val id: Int? = null,
    val name: String,
    val character: String? = null,
    /** TMDB image path ("/abc.jpg"), null when TMDB has no photo. */
    val profilePath: String? = null,
    /** Shows only: episodes this person appears in. */
    val episodes: Int? = null,
)

/** Turns the appended TMDB sub-resources into [TitleCredits]. */
object TitleCreditsParser {

    /**
     * Writing jobs worth showing, most important first (TMDB's "Writing" department also holds story editors, staff
     * writers and script consultants, which are left out).
     */
    private val WRITER_JOBS = listOf(
        "Screenplay", "Writer", "Teleplay", "Story", "Screenstory", "Original Story", "Novel", "Book", "Author",
        "Short Story", "Play", "Theatre Play", "Graphic Novel", "Comic Book", "Original Concept", "Idea", "Characters",
        "Adaptation",
    )

    fun movie(response: TmdbExtrasResponse): TitleCredits {
        val crew = response.credits?.crew.orEmpty().filter { !it.name.isNullOrBlank() }
        val directors = crew.filter { it.job == "Director" }
            .distinctBy { it.id ?: it.name }
            .take(TitleCredits.MAX_DIRECTORS)
            .map { CreditPerson(it.id, it.name!!.trim()) }
        return TitleCredits(
            tagline = clean(response.tagline),
            certification = movieCertification(response.releaseDates),
            directors = directors,
            writers = writers(crew),
            cast = response.credits?.cast.orEmpty()
                .filter { !it.name.isNullOrBlank() }
                .sortedBy { it.order ?: Int.MAX_VALUE }
                .take(TitleCredits.MAX_CAST)
                .map { CastMember(it.id, it.name!!.trim(), clean(it.character), cleanPath(it.profilePath)) },
            studios = companies(response.productionCompanies),
            budget = response.budget?.takeIf { it > 0 },
            revenue = response.revenue?.takeIf { it > 0 },
        )
    }

    fun tv(response: TmdbExtrasResponse): TitleCredits = TitleCredits(
        tagline = clean(response.tagline),
        certification = response.contentRatings?.results.orEmpty()
            .firstOrNull { it.country == "US" && !it.rating.isNullOrBlank() }?.rating?.trim(),
        createdBy = response.createdBy.orEmpty()
            .filter { !it.name.isNullOrBlank() }
            .distinctBy { it.id ?: it.name }
            .take(TitleCredits.MAX_CREATORS)
            .map { CreditPerson(it.id, it.name!!.trim()) },
        cast = response.aggregateCredits?.cast.orEmpty()
            .filter { !it.name.isNullOrBlank() }
            .take(TitleCredits.MAX_CAST)
            .map { member ->
                // A show credit can have several roles; the one with the most episodes names the character.
                val role = member.roles.orEmpty().filter { !it.character.isNullOrBlank() }.maxByOrNull { it.episodeCount ?: 0 }
                CastMember(
                    id = member.id,
                    name = member.name!!.trim(),
                    character = clean(role?.character),
                    profilePath = cleanPath(member.profilePath),
                    episodes = member.totalEpisodeCount?.takeIf { it > 0 },
                )
            },
        networks = companies(response.networks),
        firstAirYear = year(response.firstAirDate),
        lastAirYear = year(response.lastAirDate),
        numberOfEpisodes = response.numberOfEpisodes?.takeIf { it > 0 },
        status = clean(response.status),
    )

    /** One entry per person, their jobs in [WRITER_JOBS] order; people ordered by their most important job. */
    private fun writers(crew: List<TmdbCrewMember>): List<CreditPerson> {
        val writing = crew.filter { it.job in WRITER_JOBS }
        return writing
            .groupBy { it.id ?: it.name!!.trim() }
            .values
            .map { credits ->
                val jobs = credits.mapNotNull { it.job }.distinct().sortedBy { WRITER_JOBS.indexOf(it) }
                CreditPerson(credits.first().id, credits.first().name!!.trim(), jobs)
            }
            .sortedBy { WRITER_JOBS.indexOf(it.jobs.first()) }
            .take(TitleCredits.MAX_WRITERS)
    }

    /**
     * The US rating, preferring the theatrical release (type 3), then digital (4), then any US release that has one.
     * TMDB lists one entry per release; most carry an empty certification.
     */
    private fun movieCertification(releaseDates: TmdbReleaseDatesResponse?): String? {
        val us = releaseDates?.results.orEmpty().firstOrNull { it.country == "US" } ?: return null
        val rated = us.releaseDates.orEmpty().filter { !it.certification.isNullOrBlank() }
        val pick = rated.firstOrNull { it.type == 3 } ?: rated.firstOrNull { it.type == 4 } ?: rated.firstOrNull()
        return pick?.certification?.trim()?.takeIf { it.length <= 12 }
    }

    private fun companies(companies: List<TmdbNamedRef>?): List<String> =
        companies.orEmpty().mapNotNull { clean(it.name) }.distinct().take(TitleCredits.MAX_COMPANIES)

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun cleanPath(value: String?): String? = clean(value)?.takeIf { it.startsWith("/") && !it.contains("..") }

    private fun year(value: String?): Int? =
        value?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)).year }.getOrNull() }
}
