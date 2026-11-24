package org.ratelog.person.detail

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.home.HomeRepository
import org.ratelog.home.TitleMark
import org.ratelog.person.Filmography
import org.ratelog.person.FilmographyDepartment
import org.ratelog.person.FilmographyEntry
import org.ratelog.person.FilmographySort
import org.ratelog.person.FilmographyType
import org.ratelog.person.PersonDetails
import org.ratelog.person.PersonLookupError
import org.ratelog.person.TmdbPersonClient
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class GetPersonQuery(
    val personId: Int,
    val userId: User.Id?,
    val lang: Lang,
    /** A department slug (`acting`, `directing`); null or unknown = the person's known-for department. */
    val department: String? = null,
    val type: FilmographyType = FilmographyType.ALL,
    val sort: FilmographySort = FilmographySort.NEWEST,
)

sealed interface GetPersonError {
    data object NotFound : GetPersonError
    data object TmdbNotConfigured : GetPersonError
    data object TmdbFailed : GetPersonError
}

/** A person page's data: the person, the department tabs, the chosen view's titles and the viewer's marks on them. */
data class PersonPage(
    val person: PersonDetails,
    val departments: List<FilmographyDepartment>,
    /** Null only when the person has no credits. */
    val department: FilmographyDepartment?,
    val type: FilmographyType,
    val sort: FilmographySort,
    /** Titles per type filter within the chosen department (All counts each title once). */
    val typeCounts: Map<FilmographyType, Int>,
    val entries: List<FilmographyEntry>,
    /** The viewer's status / score per title (titles without either are left out). */
    val marks: Map<Pair<MediaType, Int>, TitleMark>,
)

@Service
class GetPersonHandler(
    private val client: TmdbPersonClient,
    private val homeRepository: HomeRepository,
) {
    private val logger = LoggerFactory.getLogger(GetPersonHandler::class.java)

    fun handle(query: GetPersonQuery): Either<GetPersonError, PersonPage> = either {
        val person = client.person(query.personId, query.lang).mapLeft {
            when (it) {
                PersonLookupError.NotFound -> GetPersonError.NotFound
                PersonLookupError.ApiKeyMissing -> GetPersonError.TmdbNotConfigured
                else -> GetPersonError.TmdbFailed
            }
        }.bind()
        val departments = Filmography.departments(person.credits)
        val department = Filmography.pick(departments, query.department, person.knownForDepartment)
        val inDepartment = department?.let { d -> person.credits.filter { it.department == d.name } }.orEmpty()
        val typeCounts = FilmographyType.entries.associateWith { type ->
            inDepartment.filter { type.matches(it.type) }.distinctBy { it.type to it.tmdbId }.size
        }
        val entries = department?.let { Filmography.entries(person.credits, it.name, query.type, query.sort) }.orEmpty()
        PersonPage(
            person = person,
            departments = departments,
            department = department,
            type = query.type,
            sort = query.sort,
            typeCounts = typeCounts,
            entries = entries,
            marks = query.userId?.let { marks(it, entries) }.orEmpty(),
        )
    }

    /** One marks query per media type; a failing query only loses the marks (logged), never the page. */
    private fun marks(userId: User.Id, entries: List<FilmographyEntry>): Map<Pair<MediaType, Int>, TitleMark> = try {
        entries.groupBy { it.type }.flatMap { (type, list) ->
            homeRepository.findMarks(userId, type, list.map { it.tmdbId }).map { (id, mark) -> (type to id) to mark }
        }.toMap()
    } catch (e: Exception) {
        logger.warn("Person page marks unavailable: {}", e.javaClass.simpleName)
        emptyMap()
    }
}
