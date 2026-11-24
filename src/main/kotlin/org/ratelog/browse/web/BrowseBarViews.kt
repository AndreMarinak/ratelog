package org.ratelog.browse.web

import org.ratelog.Genre
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.browse.BrowseDecade
import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowseGenres
import org.ratelog.browse.BrowsePopular
import org.ratelog.browse.BrowseProvider
import org.ratelog.browse.BrowseRuntime
import org.ratelog.browse.BrowseSort
import org.ratelog.browse.BrowseYear
import org.ratelog.browse.TmdbBrowseClient
import org.ratelog.browse.discover.BrowseTile
import org.ratelog.user.UserDetailsService
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.TileActionsView
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.util.Locale

/** A service the Service menu shows first under its everyday name; [ids] are all its TMDB providers (plans). */
data class FeaturedService(val key: String, val label: String, val ids: List<Int>)

/** Streaming services for the Service menu; [failed] when TMDB couldn't list them. */
data class ProviderOptions(
    val region: WatchRegion,
    val providers: List<BrowseProvider>,
    val failed: Boolean,
)

/**
 * Builds the browse bar and tiles. Registered as `browseBarViews` so `fragments/browse-bar.html` can build
 * a default bar by itself (`@browseBarViews.defaults(...)`) when a page includes it without a model.
 */
@Component("browseBarViews")
class BrowseBarViews(
    private val messages: MessageSource,
    private val client: TmdbBrowseClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** For includes outside `/discover`: no filters, the current user's region, no sort menu. [type] is `movie` (default) or `tv`. */
    fun defaults(type: String?): BrowseBarView {
        val lang = UserDetailsService.getCurrentUser()?.metadataLang ?: Lang.en
        val mediaType = if (type?.trim()?.lowercase() in setOf("tv", "tvshow", "show", "series")) MediaType.tvshow else MediaType.movie
        val filters = BrowseFilters(type = mediaType)
        return build(filters, providerOptions(mediaType, lang), showSort = false)
    }

    fun providerOptions(type: MediaType, lang: Lang): ProviderOptions {
        val region = WatchRegion.fromLang(lang)
        return client.providers(type, region, lang).fold(
            { ProviderOptions(region, emptyList(), failed = true) },
            { ProviderOptions(region, it, failed = false) },
        )
    }

    fun build(
        filters: BrowseFilters,
        providers: ProviderOptions,
        showSort: Boolean = true,
        locale: Locale = LocaleContextHolder.getLocale(),
    ): BrowseBarView {
        val m = Messages(messages, locale)
        val today = LocalDate.now(clock)
        return BrowseBarView(
            type = BrowseFilters.typeParam(filters.type),
            typeLinks = listOf(MediaType.movie, MediaType.tvshow).map { t ->
                BarLink(
                    label = m(if (t == MediaType.movie) "browse.type.movie" else "browse.type.tv"),
                    href = filters.withType(t).href(),
                    active = filters.type == t,
                )
            },
            menus = listOf(
                yearMenu(filters, today, m),
                ratingMenu(filters, m),
                popularMenu(filters, m),
                genreMenu(filters, m),
                serviceMenu(filters, providers, m, locale),
                moreMenu(filters, m, locale),
            ),
            sortMenu = if (showSort) sortMenu(filters, m) else null,
            chips = chips(filters, providers, m, locale),
            // "Hide seen" is a view setting, not a filter: clearing filters keeps it.
            clearHref = BrowseFilters(type = filters.type, hideSeen = filters.hideSeen).href(),
        )
    }

    fun tiles(tiles: List<BrowseTile>, locale: Locale = LocaleContextHolder.getLocale()): List<BrowseTileView> {
        val m = Messages(messages, locale)
        val today = LocalDate.now(clock)
        return tiles.map { tile ->
            val t = tile.title
            val typeParam = BrowseFilters.typeParam(t.type)
            // The progress status; Want to watch (older marks) is the bookmark, never a badge.
            val status = tile.mark?.status?.takeIf { it != WatchStatus.WANT_TO_WATCH }
            val onWatchlist = tile.mark?.onWatchlist == true
            val badge = status
            BrowseTileView(
                key = "$typeParam-${t.tmdbId}",
                tmdbId = t.tmdbId,
                type = typeParam,
                href = if (t.type == MediaType.movie) "/movie/${t.tmdbId}" else "/tv/${t.tmdbId}",
                title = t.title,
                year = t.year,
                posterUrl = t.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                posterUrl2x = t.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                initial = t.title.trim().take(1).uppercase(),
                tmdbScore = t.voteAverage?.let(::oneDecimal),
                userScore = tile.mark?.score?.let(::oneDecimal),
                status = WatchStatus.effective(status, onWatchlist)?.apiName,
                onWatchlist = onWatchlist,
                statusIcon = badge?.icon,
                statusLabel = badge?.let { m(it.messageKey) },
                actions = TileActionsView.of(t.tmdbId, t.type, status, tile.mark?.rated == true, t.releaseDate, today, locale, score = tile.mark?.score, onWatchlist = onWatchlist),
            )
        }
    }

    // --- menus ---

    /**
     * Shortcuts (Any, This year, Last year, Upcoming), the decades, then the chosen decade's years. Picking a decade
     * keeps the menu open on its years (fork, user 2026-11-24: the menu used to close on the swap, so the years
     * were never seen).
     */
    private fun yearMenu(f: BrowseFilters, today: LocalDate, m: Messages): BarMenu =
        yearMenu(f.decade, f.year, f.isMovie, today, m, yearBox = null) { decade, year -> f.copy(decade = decade, year = year).href() }

    /**
     * Fork (2026-11-24): the same Year menu for another page (the search page's, `search/SearchYearMenu.kt`), which
     * writes its own links: [href] gets the decade and year a link chooses (a year inside a decade gets both).
     * [yearBox] adds a "Type a year" box at the top. No Upcoming entry unless [withUpcoming].
     */
    fun yearMenu(
        decade: BrowseDecade?,
        year: BrowseYear?,
        withUpcoming: Boolean,
        yearBox: BarYearBox? = null,
        locale: Locale = LocaleContextHolder.getLocale(),
        href: (BrowseDecade?, BrowseYear?) -> String,
    ): BarMenu = yearMenu(decade, year, withUpcoming, LocalDate.now(clock), Messages(messages, locale), yearBox, href)

    private fun yearMenu(
        decade: BrowseDecade?,
        year: BrowseYear?,
        withUpcoming: Boolean,
        today: LocalDate,
        m: Messages,
        yearBox: BarYearBox?,
        href: (BrowseDecade?, BrowseYear?) -> String,
    ): BarMenu {
        val currentDecade = BrowseFilters.decadeOf(today.year).startYear
        val effective = BrowseFilters.effectiveDecade(decade, year)
        val earlierActive = year != BrowseYear.Upcoming && when (effective) {
            BrowseDecade.Earlier -> true
            is BrowseDecade.Of -> effective.startYear <= BrowseDecade.Earlier.LAST_YEAR
            null -> false
        }
        fun single(y: Int) = href(null, BrowseYear.Of(y))
        val shortcuts = buildList {
            add(BarLink(m("browse.year.any"), href(null, null), active = decade == null && year == null))
            add(BarLink(m("browse.year.this"), single(today.year), active = year == BrowseYear.Of(today.year), hint = today.year.toString(), option = "this-year"))
            add(BarLink(m("browse.year.last"), single(today.year - 1), active = year == BrowseYear.Of(today.year - 1), hint = (today.year - 1).toString(), option = "last-year"))
            if (withUpcoming) add(BarLink(m("browse.year.upcoming"), href(null, BrowseYear.Upcoming), active = year == BrowseYear.Upcoming))
        }
        val decades = buildList {
            for (start in currentDecade downTo 1960 step 10) {
                add(
                    BarLink(
                        decadeLabel(start), href(BrowseDecade.Of(start), null),
                        active = (effective as? BrowseDecade.Of)?.startYear == start,
                        keepOpen = true, option = "decade-$start",
                    ),
                )
            }
            add(BarLink(m("browse.year.earlier"), href(BrowseDecade.Earlier, null), active = earlierActive, keepOpen = true, option = "decade-earlier"))
        }
        val sub: BarSection? = when {
            year == BrowseYear.Upcoming -> null
            effective == BrowseDecade.Earlier -> BarSection(
                heading = m("browse.year.older.decades"),
                links = (1950 downTo 1900 step 10).map { start ->
                    BarLink(decadeLabel(start), href(BrowseDecade.Of(start), null), active = false, keepOpen = true, option = "decade-$start")
                },
                layout = "grid",
                reveal = true,
            )
            effective is BrowseDecade.Of -> {
                val last = minOf(effective.endYear, today.year + 1)
                BarSection(
                    heading = m("browse.year.in.decade", decadeLabel(effective.startYear)),
                    links = (effective.startYear..last).map { y ->
                        BarLink(y.toString(), href(effective, BrowseYear.Of(y)), active = year == BrowseYear.Of(y), option = "year-$y")
                    },
                    layout = "years",
                    reveal = true,
                )
            }
            else -> null
        }
        return BarMenu(
            id = "year",
            label = m("browse.menu.year"),
            icon = "calendar-days",
            value = yearValue(decade, year, m),
            sections = listOfNotNull(
                BarSection(null, shortcuts, "grid"),
                BarSection(m("browse.year.decades"), decades, "grid"),
                sub,
            ),
            yearBox = yearBox,
        )
    }

    private fun ratingMenu(f: BrowseFilters, m: Messages) = BarMenu(
        id = "rating",
        label = m("browse.menu.rating"),
        icon = "star",
        value = f.minRating?.let { m("browse.rating.min", BrowseFilters.formatRating(it)) },
        sections = listOf(
            BarSection(
                heading = m("browse.rating.heading"),
                links = listOf(BarLink(m("browse.rating.any"), f.copy(minRating = null).href(), active = f.minRating == null)) +
                    RATING_FLOORS.map { floor ->
                        BarLink(m("browse.rating.min", BrowseFilters.formatRating(floor)), f.copy(minRating = floor).href(), active = f.minRating == floor)
                    },
            ),
            BarSection(
                heading = null,
                links = listOf(
                    BarLink(m("browse.rating.highest"), f.withSort(BrowseSort.RATING).href(), active = f.sort == BrowseSort.RATING, hint = m("browse.rating.highest.hint")),
                ),
            ),
        ),
    )

    private fun popularMenu(f: BrowseFilters, m: Messages) = BarMenu(
        id = "popular",
        label = m("browse.menu.popular"),
        icon = "flame",
        value = f.popular?.let { m(popularKey(it)) },
        sections = listOf(
            BarSection(
                heading = null,
                links = BrowsePopular.entries.map { p ->
                    BarLink(m(popularKey(p)), f.withPopular(p).href(), active = f.popular == p, hint = m(popularKey(p) + ".hint"))
                },
            ),
        ),
    )

    private fun genreMenu(f: BrowseFilters, m: Messages): BarMenu {
        val genres = BrowseGenres.forType(f.type).sortedBy { m(genreKey(it)).lowercase() }
        return BarMenu(
            id = "genre",
            label = m("browse.menu.genre"),
            icon = "clapperboard",
            value = f.genre?.let { m(genreKey(it)) },
            sections = listOf(
                BarSection(
                    heading = null,
                    links = listOf(BarLink(m("browse.genre.any"), f.copy(genre = null).href(), active = f.genre == null)) +
                        genres.map { g -> BarLink(m(genreKey(g)), f.copy(genre = g).href(), active = f.genre == g) },
                    layout = "grid",
                ),
            ),
        )
    }

    /**
     * Multi-select (fork, user 2026-11-24): the big services first with logos and checkboxes, then every other
     * service in the region behind "Show all services" with a search box. Each option toggles its ids in place
     * and keeps the menu open.
     */
    private fun serviceMenu(f: BrowseFilters, options: ProviderOptions, m: Messages, locale: Locale): BarMenu {
        val choices = serviceChoices(f, options, m)
        fun link(c: ServiceChoice) = BarLink(
            label = c.label,
            href = f.toggleProviders(c.ids).href(),
            active = c.selected,
            logoUrl = c.logoPath?.let { "https://image.tmdb.org/t/p/w92$it" },
            keepOpen = true,
            option = c.key,
            checkbox = true,
        )
        val anyLink = BarLink(m("browse.service.any"), f.withProviders(emptyList()).href(), active = f.providers.isEmpty(), option = "svc-any")
        val rest = choices.filter { !it.featured }
        return BarMenu(
            id = "service",
            label = m("browse.menu.service"),
            icon = "monitor-play",
            value = serviceValue(choices, m),
            sections = listOfNotNull(
                BarSection(
                    heading = m("browse.service.region", countryName(options.region, locale)),
                    links = listOf(anyLink) + choices.filter { it.featured }.map(::link),
                    layout = "logos",
                ),
                rest.takeIf { it.isNotEmpty() }?.let {
                    BarSection(
                        heading = null,
                        links = it.map(::link),
                        layout = "services",
                        more = BarMore(
                            label = m("browse.service.all", it.size.toString()),
                            open = it.any(ServiceChoice::selected),
                            searchLabel = m("browse.service.search"),
                            noMatches = m("browse.service.search.none"),
                        ),
                    )
                },
            ),
            note = when {
                options.failed -> m("browse.service.unavailable")
                options.providers.isEmpty() -> m("browse.service.none")
                else -> null
            },
        )
    }

    /** One row of the Service menu: a featured service (maybe several TMDB ids, e.g. two Paramount+ plans) or one provider. */
    private data class ServiceChoice(
        val key: String,
        val label: String,
        val ids: List<Int>,
        val logoPath: String?,
        val selected: Boolean,
        val featured: Boolean,
    )

    /**
     * Featured first ([FEATURED_SERVICES] found in the region, topped up with the region's best-ranked services
     * up to [FEATURED_COUNT]), then chosen ids TMDB doesn't list (so they can still be unticked), then the
     * region's other services by TMDB's display priority.
     */
    private fun serviceChoices(f: BrowseFilters, options: ProviderOptions, m: Messages): List<ServiceChoice> {
        val byId = options.providers.associateBy { it.id }
        val chosen = f.providers.toSet()
        val used = mutableSetOf<Int>()
        val featured = mutableListOf<ServiceChoice>()
        for (service in FEATURED_SERVICES) {
            val present = service.ids.filter { it in byId }
            if (present.isEmpty()) continue
            used += service.ids
            featured += ServiceChoice(
                key = "svc-${service.key}",
                label = service.label,
                ids = present,
                logoPath = byId.getValue(present.first()).logoPath,
                selected = service.ids.any { it in chosen },
                featured = true,
            )
        }
        fun single(p: BrowseProvider, isFeatured: Boolean) =
            ServiceChoice("svc-${p.id}", p.name, listOf(p.id), p.logoPath, p.id in chosen, isFeatured)
        for (p in options.providers) {
            if (featured.size >= FEATURED_COUNT) break
            if (p.id in used) continue
            used += p.id
            featured += single(p, isFeatured = true)
        }
        val unknown = f.providers.filter { it !in used && it !in byId }.map { id ->
            ServiceChoice("svc-$id", m("browse.service.unknown", id.toString()), listOf(id), null, selected = true, featured = true)
        }
        val rest = options.providers.filter { it.id !in used }.map { single(it, isFeatured = false) }
        return featured + unknown + rest
    }

    /** "Netflix", or "Netflix + 2" for three services (counted as rows of the menu, not TMDB ids). */
    private fun serviceValue(choices: List<ServiceChoice>, m: Messages): String? {
        val selected = choices.filter { it.selected }
        val first = selected.firstOrNull() ?: return null
        return if (selected.size == 1) first.label else m("browse.service.several", first.label, (selected.size - 1).toString())
    }

    private fun moreMenu(f: BrowseFilters, m: Messages, locale: Locale): BarMenu {
        val languages = LANGUAGES.toMutableList()
        f.language?.let { if (it !in languages) languages += it }
        val sections = buildList {
            add(
                BarSection(
                    heading = m("browse.other.language"),
                    links = listOf(BarLink(m("browse.other.language.any"), f.copy(language = null).href(), active = f.language == null)) +
                        languages.map { code -> BarLink(languageName(code, locale), f.copy(language = code).href(), active = f.language == code) },
                    layout = "grid",
                ),
            )
            if (f.isMovie) {
                add(
                    BarSection(
                        heading = m("browse.other.runtime"),
                        links = listOf(BarLink(m("browse.other.runtime.any"), f.copy(runtime = null).href(), active = f.runtime == null)) +
                            BrowseRuntime.entries.map { r -> BarLink(m(runtimeKey(r)), f.copy(runtime = r).href(), active = f.runtime == r) },
                    ),
                )
            }
            add(
                BarSection(
                    heading = m("browse.other.library"),
                    links = listOfNotNull(
                        // While seen titles are hidden (the default), rated ones already are: only offer it to switch it off.
                        BarLink(m("browse.hide.rated"), f.copy(hideRated = !f.hideRated).href(), active = f.hideRated)
                            .takeIf { !f.hideSeen || f.hideRated },
                        BarLink(m("browse.hide.watchlist"), f.copy(hideWatchlist = !f.hideWatchlist).href(), active = f.hideWatchlist),
                    ),
                    layout = "toggles",
                ),
            )
        }
        val activeCount = listOf(f.language != null, f.runtime != null, f.hideRated, f.hideWatchlist).count { it }
        return BarMenu(
            id = "more",
            label = m("browse.menu.other"),
            icon = "sliders-horizontal",
            value = if (activeCount > 0) activeCount.toString() else null,
            sections = sections,
        )
    }

    private fun sortMenu(f: BrowseFilters, m: Messages): BarMenu {
        val current = when {
            f.popular != null -> m(popularKey(f.popular))
            else -> m(sortKey(f.sort ?: BrowseSort.POPULARITY))
        }
        return BarMenu(
            id = "sort",
            label = m("browse.menu.sort"),
            icon = "arrow-down-up",
            value = current,
            sections = listOf(
                BarSection(
                    heading = null,
                    links = BrowseSort.entries.map { s ->
                        val target = if (s == BrowseSort.POPULARITY) f.withSort(null) else f.withSort(s)
                        val active = f.popular == null && (f.sort ?: BrowseSort.POPULARITY) == s
                        BarLink(m(sortKey(s)), target.href(), active)
                    },
                ),
            ),
        )
    }

    private fun chips(f: BrowseFilters, providers: ProviderOptions, m: Messages, locale: Locale): List<BarChip> = buildList {
        fun chip(label: String, without: BrowseFilters) = add(BarChip(label, without.href(), m("browse.chip.remove", label)))

        f.popular?.let { chip(m(popularKey(it)), f.withPopular(null)) }
        when (val year = f.year) {
            is BrowseYear.Of -> chip(year.year.toString(), f.copy(year = null))
            BrowseYear.Upcoming -> chip(m("browse.year.upcoming"), f.copy(year = null))
            null -> f.decade?.let { chip(decadeValue(it, m), f.withDecade(null)) }
        }
        f.minRating?.let { chip(m("browse.rating.chip", BrowseFilters.formatRating(it)), f.copy(minRating = null)) }
        f.genre?.let { chip(m(genreKey(it)), f.copy(genre = null)) }
        serviceValue(serviceChoices(f, providers, m), m)?.let { chip(it, f.withProviders(emptyList())) }
        f.language?.let { chip(languageName(it, locale), f.copy(language = null)) }
        f.runtime?.let { chip(m(runtimeKey(it)), f.copy(runtime = null)) }
        if (f.hideRated) chip(m("browse.chip.hide.rated"), f.copy(hideRated = false))
        if (f.hideWatchlist) chip(m("browse.chip.hide.watchlist"), f.copy(hideWatchlist = false))
    }

    // --- labels ---

    private fun yearValue(decade: BrowseDecade?, year: BrowseYear?, m: Messages): String? = when (year) {
        is BrowseYear.Of -> year.year.toString()
        BrowseYear.Upcoming -> m("browse.year.upcoming")
        null -> decade?.let { decadeValue(it, m) }
    }

    private fun decadeValue(decade: BrowseDecade, m: Messages) = when (decade) {
        is BrowseDecade.Of -> decadeLabel(decade.startYear)
        BrowseDecade.Earlier -> m("browse.year.earlier")
    }

    private fun decadeLabel(start: Int) = "${start}s"

    private fun genreKey(genre: Genre) = "genre.${genre.tmdbId}"

    private fun popularKey(popular: BrowsePopular) = "browse.popular.${popular.param}"

    private fun sortKey(sort: BrowseSort) = "browse.sort.${sort.param}"

    private fun runtimeKey(runtime: BrowseRuntime) = "browse.runtime.${runtime.param}"

    private class Messages(private val source: MessageSource, private val locale: Locale) {
        /** No args: the message is used as is (no MessageFormat), like Thymeleaf's `#{key}`. */
        operator fun invoke(key: String, vararg args: String): String =
            source.getMessage(key, if (args.isEmpty()) null else arrayOf<Any>(*args), key, locale) ?: key
    }

    companion object {
        /** Rows above "Show all services". */
        const val FEATURED_COUNT = 8

        /**
         * The big US services, in the user's order (2026-11-24), with their TMDB ids checked against
         * `/watch/providers/movie?watch_region=US`: Paramount+ and Peacock list each plan separately, and one plan
         * alone misses titles (Paramount+ Premium 2303 has 1192 movies, with Essential 2616 1387).
         */
        val FEATURED_SERVICES = listOf(
            FeaturedService("netflix", "Netflix", listOf(8)),
            FeaturedService("prime", "Prime Video", listOf(9)),
            FeaturedService("disney", "Disney+", listOf(337)),
            FeaturedService("hbomax", "HBO Max", listOf(1899)),
            FeaturedService("hulu", "Hulu", listOf(15)),
            FeaturedService("appletv", "Apple TV+", listOf(350)),
            FeaturedService("peacock", "Peacock", listOf(386, 387)),
            FeaturedService("paramount", "Paramount+", listOf(2303, 2616)),
        )
        val RATING_FLOORS = listOf(8.0, 7.0, 6.0)

        /** Original languages offered in the menu (any ISO 639-1 code works in the URL). */
        val LANGUAGES = listOf("en", "es", "fr", "de", "it", "ja", "ko", "hi", "zh", "pt")

        fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

        fun languageName(code: String, locale: Locale): String {
            val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
            if (name.isBlank() || name.equals(code, ignoreCase = true)) return code.uppercase()
            return name.replaceFirstChar { it.titlecase(locale) }
        }

        fun countryName(region: WatchRegion, locale: Locale): String =
            Locale.of("", region.value).getDisplayCountry(locale).ifBlank { region.value }
    }
}
