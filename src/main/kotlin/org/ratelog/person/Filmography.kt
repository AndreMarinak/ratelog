package org.ratelog.person

import org.ratelog.MediaType
import java.time.LocalDate

/** All | Movies | TV on a person page (`type=` in the URL: absent, `movie`, `tv`). */
enum class FilmographyType(val param: String?) {
    ALL(null), MOVIE("movie"), TV("tv");

    fun matches(type: MediaType): Boolean = when (this) {
        ALL -> true
        MOVIE -> type == MediaType.movie
        TV -> type == MediaType.tvshow
    }

    companion object {
        fun parse(raw: String?): FilmographyType = entries.find { it.param != null && it.param == raw?.trim()?.lowercase() } ?: ALL
    }
}

/** Newest first (default) or Most popular (`sort=popular`). */
enum class FilmographySort(val param: String?) {
    NEWEST(null), POPULAR("popular");

    companion object {
        fun parse(raw: String?): FilmographySort = entries.find { it.param != null && it.param == raw?.trim()?.lowercase() } ?: NEWEST
    }
}

/** One title in a department: a person with several jobs on it (Director, Writer) appears once, jobs joined. */
data class FilmographyEntry(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val releaseDate: LocalDate?,
    val posterPath: String?,
    val roles: List<String>,
    val popularity: Double,
    val voteCount: Int,
) {
    val year: Int? get() = releaseDate?.year
}

/** A department tab: its URL slug (`acting`, `directing`, `costume-make-up`), TMDB's name and how many titles it has. */
data class FilmographyDepartment(val slug: String, val name: String, val count: Int)

object Filmography {
    /** Tab order; any other department TMDB sends comes after these, A-Z. */
    private val ORDER = listOf(
        ACTING, "Directing", "Writing", "Creator", "Production", "Camera", "Editing", "Sound", "Art",
        "Costume & Make-Up", "Visual Effects", "Lighting", "Crew",
    )

    fun slug(department: String): String = department.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** The departments this person has credits in, in tab order, each counted in distinct titles. */
    fun departments(credits: List<PersonCredit>): List<FilmographyDepartment> =
        credits.groupBy { it.department }
            .map { (name, list) -> FilmographyDepartment(slug(name), name, list.distinctBy { it.type to it.tmdbId }.size) }
            .sortedWith(compareBy<FilmographyDepartment> { ORDER.indexOf(it.name).let { i -> if (i < 0) ORDER.size else i } }.thenBy { it.name })

    /**
     * The department a page shows: [requested] (a slug) when the person has it, else their known-for department, else
     * the first tab. Null when they have no credits at all.
     */
    fun pick(departments: List<FilmographyDepartment>, requested: String?, knownFor: String?): FilmographyDepartment? {
        requested?.trim()?.lowercase()?.let { slug -> departments.find { it.slug == slug }?.let { return it } }
        knownFor?.let { name -> departments.find { it.name.equals(name, ignoreCase = true) }?.let { return it } }
        return departments.firstOrNull()
    }

    /** The titles of one department, de-duplicated (roles joined in TMDB's order), filtered by [type] and sorted. */
    fun entries(credits: List<PersonCredit>, department: String, type: FilmographyType, sort: FilmographySort): List<FilmographyEntry> =
        sorted(merge(credits.filter { it.department == department && type.matches(it.type) }), sort)

    /** One entry per title; its roles in first-seen order without repeats ("Producer" twice is "Producer"). */
    fun merge(credits: List<PersonCredit>): List<FilmographyEntry> =
        credits.groupBy { it.type to it.tmdbId }.values.map { group ->
            val first = group.first()
            FilmographyEntry(
                tmdbId = first.tmdbId,
                type = first.type,
                title = first.title,
                releaseDate = group.firstNotNullOfOrNull { it.releaseDate },
                posterPath = group.firstNotNullOfOrNull { it.posterPath },
                roles = group.mapNotNull { it.role }.distinctBy { it.lowercase() },
                popularity = group.maxOf { it.popularity },
                voteCount = group.maxOf { it.voteCount },
            )
        }

    /**
     * Newest: latest release first (so upcoming titles lead), titles without a date last (announced, no date yet),
     * then by title. Most popular: most TMDB votes first (how well known a title is, not this week's trend), then
     * popularity, then newest.
     */
    fun sorted(entries: List<FilmographyEntry>, sort: FilmographySort): List<FilmographyEntry> = when (sort) {
        FilmographySort.NEWEST -> entries.sortedWith(
            compareBy<FilmographyEntry> { it.releaseDate == null }
                .thenByDescending { it.releaseDate }
                .thenBy { it.title.lowercase() }
        )
        FilmographySort.POPULAR -> entries.sortedWith(
            compareByDescending<FilmographyEntry> { it.voteCount }
                .thenByDescending { it.popularity }
                .thenByDescending { it.releaseDate }
        )
    }

    /** How a tile names the role: characters joined with " / ", jobs with ", ". */
    fun roleLabel(entry: FilmographyEntry, department: String): String? =
        entry.roles.takeIf { it.isNotEmpty() }?.joinToString(if (department == ACTING) " / " else ", ")
}
