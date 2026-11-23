package org.ratelog.api.home

import com.fasterxml.jackson.annotation.JsonInclude
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.apiName
import org.ratelog.api.posterUrl
import org.ratelog.customlist.ListSummary
import org.ratelog.home.HomeTitle
import org.ratelog.home.dashboard.DiscoverState
import org.ratelog.home.dashboard.GetHomeDashboard
import org.ratelog.home.dashboard.GetHomeDashboardHandler
import org.ratelog.home.dashboard.HomeRow
import org.ratelog.home.dashboard.HomeRowKind
import org.ratelog.user.User
import org.springframework.context.MessageSource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Locale

data class HomeResponse(
    val rows: List<HomeRowDto>,
    /** ok | not_configured | unavailable */
    val tmdb: String,
)

/** A row has `titles` or (for `your_lists`) `lists`; the other field is omitted. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class HomeRowDto(
    val id: String,
    val title: String,
    val personal: Boolean,
    val titles: List<HomeTitleDto>?,
    val lists: List<HomeListDto>?,
)

data class HomeTitleDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    /** Your status (want_to_watch | watching | watched | dropped) or null. */
    val status: String?,
    /** Your score, or null if you haven't rated it. */
    val score: Double?,
    /** ISO date, for TMDB titles. */
    val releaseDate: String?,
)

data class HomeListDto(
    val id: Long,
    val name: String,
    val isPublic: Boolean,
    val itemCount: Int,
    val url: String,
    val posterUrls: List<String>,
)

/** The home dashboard rows as JSON. See docs/api.md. */
@RestController
@RequestMapping("/api/v1/home")
class ApiHomeController(
    private val handler: GetHomeDashboardHandler,
    private val messageSource: MessageSource,
) {
    @GetMapping
    fun home(@CurrentUser user: User): HomeResponse {
        val dashboard = handler.handle(GetHomeDashboard(userId = user.id!!, lang = user.metadataLang))
        return HomeResponse(
            rows = dashboard.rows.map { it.toDto() },
            tmdb = when (dashboard.discover) {
                DiscoverState.OK -> "ok"
                DiscoverState.NOT_CONFIGURED -> "not_configured"
                DiscoverState.UNAVAILABLE -> "unavailable"
            },
        )
    }

    private fun HomeRow.toDto() = HomeRowDto(
        id = kind.apiName,
        title = messageSource.getMessage(kind.titleKey, null, kind.apiName, Locale.ENGLISH) ?: kind.apiName,
        personal = kind.isPersonal,
        titles = titles.map { it.toDto() }.takeUnless { kind == HomeRowKind.YOUR_LISTS },
        lists = lists.map { it.toDto() }.takeIf { kind == HomeRowKind.YOUR_LISTS },
    )

    private fun HomeTitle.toDto() = HomeTitleDto(
        tmdbId = tmdbId,
        type = mediaType.apiName(),
        title = title,
        year = year,
        posterUrl = posterPath?.let(::posterUrl),
        status = status?.apiName,
        score = score,
        releaseDate = releaseDate?.toString(),
    )

    private fun ListSummary.toDto() = HomeListDto(
        id = id.value,
        name = name,
        isPublic = isPublic,
        itemCount = itemCount,
        url = "/list/${id.value}",
        posterUrls = coverPosterPaths.map(::posterUrl),
    )
}
