package org.ratelog.person

import org.ratelog.MediaType
import java.text.Normalizer
import java.time.LocalDate

/*
 * Fork: person pages and People in search (docs/decisions/people-and-credits.md "People: person pages and People in search").
 * A person as TMDB describes them, and every credit they have (cast and crew, movies and TV).
 */

/** The department a cast credit counts under; crew credits keep TMDB's department ("Directing", "Writing"...). */
const val ACTING = "Acting"

data class PersonDetails(
    val id: Int,
    val name: String,
    val biography: String?,
    val birthday: LocalDate?,
    val deathday: LocalDate?,
    val placeOfBirth: String?,
    val profilePath: String?,
    val knownForDepartment: String?,
    val credits: List<PersonCredit>,
    /** TMDB's gender (1 female, 2 male, else not set / non-binary), for "as herself / himself / themselves". */
    val gender: Int? = null,
)

/** One credit: [department] is [ACTING] for cast, else the crew department; [role] is the character or the job. */
data class PersonCredit(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val releaseDate: LocalDate?,
    val posterPath: String?,
    val department: String,
    val role: String?,
    val popularity: Double,
    val voteCount: Int,
    /**
     * Fork (2026-11-24): a cast credit that isn't a role (talk shows, award shows, "Self"; see [PersonParsing.castCredit]).
     * Kept so the person page can show them on request; left out of Acting by default.
     */
    val appearance: Boolean = false,
)

/** A People result in search: the known-for line is the department and up to three of their best-known titles. */
data class PersonSummary(
    val id: Int,
    val name: String,
    val profilePath: String?,
    val knownForDepartment: String?,
    val knownFor: List<String>,
    val popularity: Double,
)

object PersonParsing {
    /** TV genres whose cast lists are guest appearances, not roles: Talk, News, Reality. */
    private val APPEARANCE_TV_GENRES = setOf(10767, 10763, 10764)

    /** "Self", "Himself", "Herself - Guest", "Self (archive footage)": appearing as oneself. */
    private val SELF_ROLE = Regex("^(self|himself|herself|themselves|themself)\\b.*", RegexOption.IGNORE_CASE)

    fun details(response: TmdbPersonResponse): PersonDetails? {
        val id = response.id?.takeIf { it > 0 } ?: return null
        val name = response.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cast = response.combinedCredits?.cast.orEmpty().mapNotNull { castCredit(it) }
        val crew = response.combinedCredits?.crew.orEmpty().mapNotNull { crewCredit(it) }
        return PersonDetails(
            id = id,
            name = name,
            biography = response.biography?.trim()?.takeIf { it.isNotEmpty() },
            birthday = date(response.birthday),
            deathday = date(response.deathday),
            placeOfBirth = response.placeOfBirth?.trim()?.takeIf { it.isNotEmpty() },
            profilePath = response.profilePath?.takeIf { it.isNotBlank() },
            knownForDepartment = response.knownForDepartment?.trim()?.takeIf { it.isNotEmpty() },
            credits = cast + crew,
            gender = response.gender,
        )
    }

    /**
     * A cast credit (null only for unusable data), marked [PersonCredit.appearance] when it isn't a role: on TV, talk /
     * news / reality shows and appearing as oneself (Tom Hanks has 96 TV cast credits, ~70 of them talk shows, award
     * shows and "Self" interviews); in movies, appearing as oneself in a documentary (or archive footage in one) or in a
     * film with no genre at all (TMDB's making-ofs, award galas). Playing yourself in a comedy is a role.
     * Fork (2026-11-24): appearances were dropped here; they are kept, marked, for the person page's switch.
     */
    fun castCredit(credit: TmdbPersonCredit): PersonCredit? {
        val base = credit(credit, ACTING, credit.character) ?: return null
        val genres = credit.genreIds.orEmpty()
        val asSelf = base.role != null && SELF_ROLE.matches(base.role)
        val appearance = when (base.type) {
            MediaType.tvshow -> asSelf || genres.any { it in APPEARANCE_TV_GENRES }
            MediaType.movie -> asSelf && (genres.isEmpty() || DOCUMENTARY in genres)
        }
        return if (appearance) base.copy(appearance = true) else base
    }

    private const val DOCUMENTARY = 99

    fun crewCredit(credit: TmdbPersonCredit): PersonCredit? {
        val department = credit.department?.trim()?.takeIf { it.isNotEmpty() } ?: "Crew"
        return credit(credit, department, credit.job)
    }

    private fun credit(credit: TmdbPersonCredit, department: String, role: String?): PersonCredit? {
        val id = credit.id?.takeIf { it > 0 } ?: return null
        if (credit.adult == true) return null
        val type = when (credit.mediaType) {
            "movie" -> MediaType.movie
            "tv" -> MediaType.tvshow
            else -> return null
        }
        val title = when (type) {
            MediaType.movie -> listOf(credit.title, credit.originalTitle)
            MediaType.tvshow -> listOf(credit.name, credit.originalName)
        }.firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
        return PersonCredit(
            tmdbId = id,
            type = type,
            title = title,
            releaseDate = date(if (type == MediaType.movie) credit.releaseDate else credit.firstAirDate),
            posterPath = credit.posterPath?.takeIf { it.isNotBlank() },
            department = department,
            role = role?.trim()?.takeIf { it.isNotEmpty() },
            popularity = credit.popularity ?: 0.0,
            voteCount = credit.voteCount ?: 0,
        )
    }

    /**
     * People worth a row above the title results: anyone whose name is exactly the query, else people with a photo
     * and some popularity (a search for "dune" finds 81 people named Dune, none of whom you meant). TMDB's order
     * (most popular first) is kept.
     */
    fun searchResults(response: TmdbPersonSearchResponse, query: String, limit: Int): List<PersonSummary> {
        val wanted = normalize(query)
        return response.results.orEmpty().asSequence()
            .filter { it.adult != true }
            .mapNotNull { item ->
                val id = item.id?.takeIf { it > 0 } ?: return@mapNotNull null
                val name = item.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val photo = item.profilePath?.takeIf { it.isNotBlank() }
                val popularity = item.popularity ?: 0.0
                val exact = normalize(name) == wanted
                if (!exact && (photo == null || popularity < MIN_SEARCH_POPULARITY)) return@mapNotNull null
                PersonSummary(
                    id = id,
                    name = name,
                    profilePath = photo,
                    knownForDepartment = item.knownForDepartment?.trim()?.takeIf { it.isNotEmpty() },
                    knownFor = item.knownFor.orEmpty()
                        .mapNotNull { k -> listOf(k.title, k.name).firstOrNull { !it.isNullOrBlank() }?.trim() }
                        .distinct()
                        .take(3),
                    popularity = popularity,
                )
            }
            .distinctBy { it.id }
            .take(limit)
            .toList()
    }

    const val MIN_SEARCH_POPULARITY = 1.0

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun date(raw: String?): LocalDate? =
        raw?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
}
