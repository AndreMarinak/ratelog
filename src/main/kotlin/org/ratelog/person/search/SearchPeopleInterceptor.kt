package org.ratelog.person.search

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.Lang
import org.ratelog.person.PersonSummary
import org.ratelog.person.TmdbPersonClient
import org.ratelog.person.detail.web.PersonPageView
import org.ratelog.user.User
import org.ratelog.user.UserDetailsService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A person in the search page's People row. */
data class SearchPersonView(
    val id: Int,
    val href: String,
    val name: String,
    val photoUrl: String?,
    val photoUrl2x: String?,
    val initial: String,
    /** Known-for department: a message key, else TMDB's name. */
    val knownForKey: String?,
    val knownForLabel: String?,
    /** Up to three best-known titles, joined. */
    val knownFor: String?,
) {
    companion object {
        fun of(person: PersonSummary) = SearchPersonView(
            id = person.id,
            href = "/person/${person.id}",
            name = person.name,
            photoUrl = person.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" },
            photoUrl2x = person.profilePath?.let { "https://image.tmdb.org/t/p/h632$it" },
            initial = person.name.take(1).uppercase(),
            knownForKey = person.knownForDepartment?.let(PersonPageView::departmentKey),
            knownForLabel = person.knownForDepartment,
            knownFor = person.knownFor.takeIf { it.isNotEmpty() }?.joinToString(", "),
        )
    }
}

/**
 * Fork: People in search (docs/design-decisions.md "People: person pages and People in search"). On the first page of
 * results for Any type (`/?q=tom+hanks`), the TMDB people search starts when the request comes in (preHandle), runs
 * beside the upstream title search, and its answer goes in the model as `searchPeople` (postHandle), so the upstream
 * controller stays untouched and the page waits at most for the slower of the two. Any failure or a slow TMDB only
 * loses the row.
 */
class SearchPeopleInterceptor(
    private val client: TmdbPersonClient,
    private val currentUser: () -> User?,
    private val executor: Executor,
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(SearchPeopleInterceptor::class.java)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val query = wanted(request) ?: return true
        val lang = currentUser()?.metadataLang ?: Lang.en
        val future = CompletableFuture.supplyAsync({ client.search(query, lang).getOrNull().orEmpty() }, executor)
        request.setAttribute(ATTRIBUTE, future)
        return true
    }

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        @Suppress("UNCHECKED_CAST")
        val future = request.getAttribute(ATTRIBUTE) as? CompletableFuture<List<PersonSummary>> ?: return
        request.removeAttribute(ATTRIBUTE)
        if (modelAndView == null || modelAndView.viewName != "search") {
            future.cancel(true)
            return
        }
        try {
            val people = future.get(WAIT_SECONDS, TimeUnit.SECONDS)
            if (people.isNotEmpty()) modelAndView.addObject("searchPeople", people.map(SearchPersonView::of))
        } catch (e: Exception) {
            future.cancel(true)
            logger.warn("Search people unavailable: {}", e.javaClass.simpleName)
        }
    }

    companion object {
        const val ATTRIBUTE = "org.ratelog.person.search.people"
        private const val WAIT_SECONDS = 4L

        /** The query when this request is the first results page for Any type, else null (Movies / TV, later pages). */
        fun wanted(request: HttpServletRequest): String? {
            if (!request.method.equals("GET", ignoreCase = true)) return null
            val query = request.getParameter("q")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: return null
            if (!request.getParameter("mediaType").isNullOrBlank()) return null
            val page = request.getParameter("page")?.trim()
            if (!page.isNullOrEmpty() && page != "1") return null
            return query
        }
    }
}

/** Registers [SearchPeopleInterceptor] on the search page. */
@Configuration
class SearchPeopleWebConfig(
    private val client: TmdbPersonClient,
) : WebMvcConfigurer {
    private val executor: Executor = Executors.newVirtualThreadPerTaskExecutor()

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(SearchPeopleInterceptor(client, { UserDetailsService.getCurrentUser() }, executor))
            .addPathPatterns("/")
    }
}
