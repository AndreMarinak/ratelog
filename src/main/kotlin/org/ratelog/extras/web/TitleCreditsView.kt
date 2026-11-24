package org.ratelog.extras.web

import org.ratelog.extras.CastMember
import org.ratelog.extras.CreditPerson
import org.ratelog.extras.TitleCredits
import java.util.Locale

/**
 * Display-ready credits for `fragments/title-credits.html` (fork, 2026-11-24). Desktop shows them as a Details card and a
 * Cast card under the action panel; tablets and phones as a "Directed by · Written by" line, a sideways cast row and a
 * collapsed "More details". Names carry their TMDB person id; they only become links when `ratelog.features.people` is on.
 */
class TitleCreditsView(private val credits: TitleCredits, val isMovie: Boolean) {

    data class Person(val id: Int?, val name: String, /** "novel", "screenplay, story"; null for a plain "Writer". */ val job: String? = null)

    data class Cast(
        val id: Int?,
        val name: String,
        val character: String?,
        val profileUrl: String?,
        /** 1x / 2x for the 40px desktop avatar. */
        val profileSrcset: String?,
        /** Shown in a circle when there is no photo. */
        val initial: String,
        val episodes: Int?,
    )

    val tagline: String? get() = credits.tagline
    val certification: String? get() = credits.certification

    val directors: List<Person> = credits.directors.map(::person)
    val writers: List<Person> = credits.writers.map { w ->
        Person(w.id, w.name, w.jobs.filter { it != "Writer" }.joinToString(", ") { it.lowercase(Locale.ROOT) }.ifEmpty { null })
    }
    val createdBy: List<Person> = credits.createdBy.map(::person)

    val cast: List<Cast> = credits.cast.map(::castMember)
    val hasCast: Boolean get() = cast.isNotEmpty()
    val castCount: Int get() = cast.size
    /** Desktop shows this many, "Show all N" reveals the rest in place. */
    val castPreviewSize: Int get() = CAST_PREVIEW
    val hasMoreCast: Boolean get() = cast.size > CAST_PREVIEW

    /** The phone line names at most [LINE_WRITERS] writers, then "+N more" (the full list is under More details). */
    val lineWriters: List<Person> get() = writers.take(LINE_WRITERS)
    val moreWriters: Int get() = (writers.size - LINE_WRITERS).coerceAtLeast(0)
    val hasCreditLine: Boolean get() = directors.isNotEmpty() || writers.isNotEmpty() || createdBy.isNotEmpty()

    val studios: String? get() = credits.studios.take(2).joinToString(", ").ifEmpty { null }
    val studioCount: Int get() = credits.studios.take(2).size
    val networks: String? get() = credits.networks.take(2).joinToString(", ").ifEmpty { null }
    val networkCount: Int get() = credits.networks.take(2).size
    val budget: String? get() = credits.budget?.let(::money)
    val revenue: String? get() = credits.revenue?.let(::money)

    val firstAirYear: Int? get() = credits.firstAirYear
    val lastAirYear: Int? get() = credits.lastAirYear
    /** Ended or canceled shows get "2008–2013"; anything else is still running ("2019–present"). */
    val hasEnded: Boolean get() = credits.status == "Ended" || credits.status == "Canceled"
    val airedSingleYear: Boolean get() = hasEnded && (lastAirYear == null || lastAirYear == firstAirYear)
    /** "2008–2013" or "2019" for a show that ended; null while it runs (the template says "2019–present"). */
    val airedRange: String?
        get() = when {
            firstAirYear == null || !hasEnded -> null
            airedSingleYear -> firstAirYear.toString()
            else -> "$firstAirYear–$lastAirYear"
        }
    val numberOfEpisodes: Int? get() = credits.numberOfEpisodes

    val hasProductionFacts: Boolean
        get() = studios != null || networks != null || budget != null || revenue != null || firstAirYear != null || numberOfEpisodes != null

    /** The desktop Details card. */
    val hasDetails: Boolean get() = directors.isNotEmpty() || writers.isNotEmpty() || createdBy.isNotEmpty() || hasProductionFacts

    /** The phone "More details": production facts, plus the writers when the line above had to cut them short. */
    val hasMoreDetails: Boolean get() = hasProductionFacts || moreWriters > 0

    /** Nothing at all to show (an obscure title): the fragments render nothing. */
    val isEmpty: Boolean get() = !hasDetails && !hasCast && tagline == null && certification == null

    private fun person(p: CreditPerson) = Person(p.id, p.name)

    private fun castMember(m: CastMember) = Cast(
        id = m.id,
        name = m.name,
        character = m.character,
        profileUrl = m.profilePath?.let { "$IMAGE_BASE/w185$it" },
        profileSrcset = m.profilePath?.let { "$IMAGE_BASE/w45$it 1x, $IMAGE_BASE/w185$it 2x" },
        initial = m.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
        episodes = m.episodes,
    )

    companion object {
        const val CAST_PREVIEW = 6
        const val LINE_WRITERS = 3
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p"

        /** 850_000 -> "$850K", 6_000_000 -> "$6M", 245_066_411 -> "$245.1M", 2_923_706_026 -> "$2.9B". */
        fun money(value: Long): String = when {
            value >= 999_950_000 -> "$" + trimZero(String.format(Locale.ROOT, "%.1f", value / 1_000_000_000.0)) + "B"
            value >= 1_000_000 -> "$" + trimZero(String.format(Locale.ROOT, "%.1f", value / 1_000_000.0)) + "M"
            value >= 1_000 -> "$" + (value / 1_000) + "K"
            else -> "$$value"
        }

        private fun trimZero(value: String) = value.removeSuffix(".0")
    }
}
