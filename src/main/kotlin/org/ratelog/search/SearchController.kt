package org.ratelog.search

import arrow.core.getOrElse
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.config.istmdbconfigured.IsTmdbConfiguredHandler
import org.ratelog.config.istmdbconfigured.IsTmdbConfiguredQuery
import org.ratelog.home.dashboard.GetHomeDashboard
import org.ratelog.home.dashboard.GetHomeDashboardHandler
import org.ratelog.home.dashboard.toView
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes

data class SearchApiResponse(
    val items: List<SearchResultResponse>,
    val hasMore: Boolean,
)

data class SearchResultResponse(
    val tmdbId: Int,
    val title: String,
    val overview: String?,
    val year: Int?,
    val posterPath: String?,
    val type: String,
)

@Controller
class SearchController(
    private val handler: SearchHandler,
    private val homeDashboardHandler: GetHomeDashboardHandler,
    private val isTmdbConfiguredHandler: IsTmdbConfiguredHandler,
    private val yearMenu: SearchYearMenu,
) {

    @GetMapping("/")
    fun searchPage(
        @CurrentUser user: User,
        @RequestParam("q", required = false) query: String?,
        @RequestParam("mediaType", required = false) mediaType: String?,
        @RequestParam("page", required = false, defaultValue = "1") page: Int,
        @RequestParam("year", required = false) year: String?,
        @RequestParam("decade", required = false) decade: String?,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        model.addAttribute(
            "tmdbConfigured",
            isTmdbConfiguredHandler.handle(IsTmdbConfiguredQuery).getOrElse { false }
        )
        if (!query.isNullOrBlank()) {
            val type = mediaType?.takeIf { it.isNotBlank() }
                ?.let(MediaType::valueOf)
            // Fork (2026-11-24): the Year menu, `year=2023` or `decade=2020s` (a year wins).
            val yearFilter = SearchYearFilter.parse(year, decade, yearMenu.today())

            SearchQuery(
                query = query,
                userId = user.id!!,
                lang = user.metadataLang,
                mediaType = type,
                page = page,
                year = (yearFilter as? SearchYearFilter.Exact)?.year,
                decade = (yearFilter as? SearchYearFilter.Decade)?.decade,
            ).let(handler::handle)
            .fold(
                {
                    redirectAttributes.addFlashAttribute("error", it)
                },
                {
                    model.addAttribute("query", query)
                    model.addAttribute("results", it.items)
                    model.addAttribute("hasMore", it.hasMore)
                    model.addAttribute("mediaType", mediaType)
                    // A decade reads several TMDB pages: the next page starts after the last one read.
                    model.addAttribute("currentPage", it.lastPage ?: page)
                    model.addAttribute("searchYear", (yearFilter as? SearchYearFilter.Exact)?.year)
                    model.addAttribute("searchDecade", (yearFilter as? SearchYearFilter.Decade)?.decade?.param)
                    model.addAttribute("yearMenu", yearMenu.build(query, mediaType?.takeIf { m -> m.isNotBlank() }, yearFilter, year))
                }
            )
        } else {
            // Fork: the home dashboard (templates/fragments/home-dashboard.html) replaces the trending grid.
            GetHomeDashboard(userId = user.id!!, lang = user.metadataLang)
                .let(homeDashboardHandler::handle)
                .let { model.addAttribute("home", it.toView(user.lang.locale)) }
        }
        return "search"
    }

    @GetMapping("/api/search")
    @ResponseBody
    fun searchApi(
        @CurrentUser user: User,
        @RequestParam("q") query: String,
        @RequestParam("mediaType", required = false) mediaType: String?,
        @RequestParam("page", defaultValue = "1") page: Int,
    ): SearchApiResponse {
        val type = mediaType?.takeIf { it.isNotBlank() }
            ?.let(MediaType::valueOf)

        return SearchQuery(
            query = query,
            userId = user.id!!,
            lang = user.metadataLang,
            mediaType = type,
            page = page,
        ).let(handler::handle)
            .getOrElse { SearchResult(emptyList(), false) }
            .let { result ->
                SearchApiResponse(
                    items = result.items.map(::toResponse),
                    hasMore = result.hasMore,
                )
            }
    }

    private fun toResponse(item: SearchResultItem) = SearchResultResponse(
        tmdbId = item.tmdbId,
        title = item.title,
        overview = item.overview,
        year = item.year,
        posterPath = item.posterPath,
        type = item.type,
    )
}
