package org.ratelog.person.detail.web

import org.ratelog.MediaType
import org.ratelog.person.Filmography
import org.ratelog.person.FilmographySort
import org.ratelog.person.FilmographyType
import org.ratelog.person.detail.PersonPage
import org.ratelog.watchlist.seen.TileActionsView
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A filter link on the person page: a message key, or TMDB's own name for a department without one. */
data class PersonTab(
    val href: String,
    val labelKey: String?,
    val label: String?,
    val count: Int?,
    val active: Boolean,
    /** `acting`, `movie`, `popular`...: a stable hook for tests and scripts. */
    val value: String,
)

data class PersonTile(
    val key: String,
    val href: String,
    val title: String,
    val year: Int?,
    val role: String?,
    val posterUrl: String?,
    val posterUrl2x: String?,
    val initial: String,
    val isMovie: Boolean,
    val actions: TileActionsView,
)

data class PersonView(
    val id: Int,
    val name: String,
    val href: String,
    val photoUrl: String?,
    val photoUrl2x: String?,
    val initial: String,
    /** Known-for department: a message key, else TMDB's name. */
    val knownForKey: String?,
    val knownForLabel: String?,
    val born: String?,
    val age: Int?,
    val died: String?,
    val diedAge: Int?,
    val placeOfBirth: String?,
    /** The first sentences of the biography (up to three, within its first paragraph), and the rest behind "More". */
    val bioLead: String?,
    val bioRest: List<String>,
    val departmentTabs: List<PersonTab>,
    val typeTabs: List<PersonTab>,
    val sortTabs: List<PersonTab>,
    val tiles: List<PersonTile>,
    /** Titles in this view; [tiles] holds the first [PersonPageView.PAGE_SIZE] unless "Show all" was used. */
    val total: Int,
    val showAllHref: String?,
    val emptyKey: String?,
)

object PersonPageView {
    const val PAGE_SIZE = 60
    private const val IMG = "https://image.tmdb.org/t/p/"

    /** Departments with their own label; any other one TMDB sends is shown by its TMDB name. */
    private val DEPARTMENT_KEYS = setOf(
        "acting", "directing", "writing", "creator", "production", "camera", "editing", "sound", "art",
        "costume-make-up", "visual-effects", "lighting", "crew",
    )

    fun departmentKey(name: String): String? = Filmography.slug(name).takeIf { it in DEPARTMENT_KEYS }?.let { "person.dept.$it" }

    fun of(page: PersonPage, showAll: Boolean, today: LocalDate, locale: Locale): PersonView {
        val person = page.person
        val base = "/person/${person.id}"
        val defaultDept = page.departments.let { Filmography.pick(it, null, person.knownForDepartment) }
        val dept = page.department

        fun href(deptSlug: String? = dept?.slug, type: FilmographyType = page.type, sort: FilmographySort = page.sort, all: Boolean = false): String =
            UriComponentsBuilder.fromPath(base).apply {
                if (deptSlug != null && deptSlug != defaultDept?.slug) queryParam("dept", deptSlug)
                type.param?.let { queryParam("type", it) }
                sort.param?.let { queryParam("sort", it) }
                if (all) queryParam("all", "1")
            }.build().encode().toUriString()

        val departmentTabs = page.departments.map { d ->
            PersonTab(href(deptSlug = d.slug), departmentKey(d.name), d.name, d.count, d.slug == dept?.slug, d.slug)
        }
        val typeTabs = FilmographyType.entries
            .filter { it == FilmographyType.ALL || it == page.type || (page.typeCounts[it] ?: 0) > 0 }
            .map { t ->
                val key = when (t) {
                    FilmographyType.ALL -> "person.type.all"
                    FilmographyType.MOVIE -> "person.type.movies"
                    FilmographyType.TV -> "person.type.tv"
                }
                PersonTab(href(type = t), key, null, page.typeCounts[t] ?: 0, t == page.type, t.param ?: "all")
            }
        val sortTabs = FilmographySort.entries.map { s ->
            val key = if (s == FilmographySort.NEWEST) "person.sort.newest" else "person.sort.popular"
            PersonTab(href(sort = s), key, null, null, s == page.sort, s.param ?: "newest")
        }

        val shown = if (showAll) page.entries else page.entries.take(PAGE_SIZE)
        val tiles = shown.map { entry ->
            val mark = page.marks[entry.type to entry.tmdbId]
            val isMovie = entry.type == MediaType.movie
            PersonTile(
                key = "${if (isMovie) "movie" else "tv"}-${entry.tmdbId}",
                href = if (isMovie) "/movie/${entry.tmdbId}" else "/tv/${entry.tmdbId}",
                title = entry.title,
                year = entry.year,
                role = dept?.let { Filmography.roleLabel(entry, it.name) },
                posterUrl = entry.posterPath?.let { IMG + "w342" + it },
                posterUrl2x = entry.posterPath?.let { IMG + "w500" + it },
                initial = entry.title.take(1).uppercase(),
                isMovie = isMovie,
                actions = TileActionsView.of(
                    tmdbId = entry.tmdbId,
                    mediaType = entry.type,
                    status = mark?.status,
                    rated = mark?.score != null,
                    releaseDate = entry.releaseDate,
                    today = today,
                    locale = locale,
                    score = mark?.score,
                ),
            )
        }

        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
        val (lead, rest) = bio(person.biography)
        val knownFor = person.knownForDepartment
        return PersonView(
            id = person.id,
            name = person.name,
            href = base,
            photoUrl = person.profilePath?.let { IMG + "w185" + it },
            photoUrl2x = person.profilePath?.let { IMG + "h632" + it },
            initial = person.name.take(1).uppercase(),
            knownForKey = knownFor?.let(::departmentKey),
            knownForLabel = knownFor,
            born = person.birthday?.format(dateFormat),
            age = person.birthday?.takeIf { person.deathday == null }?.let { Period.between(it, today).years }?.takeIf { it in 0..130 },
            died = person.deathday?.format(dateFormat),
            diedAge = person.birthday?.let { b -> person.deathday?.let { d -> Period.between(b, d).years } }?.takeIf { it in 0..130 },
            placeOfBirth = person.placeOfBirth,
            bioLead = lead,
            bioRest = rest,
            departmentTabs = departmentTabs,
            typeTabs = typeTabs,
            sortTabs = sortTabs,
            tiles = tiles,
            total = page.entries.size,
            showAllHref = if (!showAll && page.entries.size > PAGE_SIZE) href(all = true) else null,
            emptyKey = when {
                page.department == null -> "person.empty.none"
                page.entries.isEmpty() -> "person.empty.filter"
                else -> null
            },
        )
    }

    private val ABBREVIATIONS = setOf("jr", "sr", "dr", "mr", "mrs", "ms", "st", "mt", "vs", "no", "co", "inc", "ltd", "u.s", "u.k")
    private val BOUNDARY = Regex("(?<=[.!?][\"”’)]?)\\s+(?=[\\p{Lu}\\p{N}\"“‘(])")

    /**
     * The biography's lead (up to [sentences] sentences, never past its first paragraph) and the rest as paragraphs.
     * Abbreviations ("Jr.", "Dr.") and initials ("J. R.") don't end a sentence.
     */
    fun bio(text: String?, sentences: Int = 3): Pair<String?, List<String>> {
        val paragraphs = text.orEmpty().split(Regex("\\n\\s*\\n|\\r?\\n")).map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return null to emptyList()
        val first = paragraphs.first()
        var cut = -1
        var count = 0
        for (match in BOUNDARY.findAll(first)) {
            val before = first.substring(0, match.range.first).trimEnd('"', '”', '’', ')')
            val word = before.substringAfterLast(' ').trimEnd('.', '!', '?').lowercase()
            if (before.endsWith(".") && (word.length == 1 || word in ABBREVIATIONS)) continue
            count++
            if (count == sentences) { cut = match.range.first; break }
        }
        val lead = if (cut < 0) first else first.substring(0, cut).trim()
        val remainder = if (cut < 0) "" else first.substring(cut).trim()
        val rest = listOfNotNull(remainder.takeIf { it.isNotEmpty() }) + paragraphs.drop(1)
        return lead to rest
    }
}
