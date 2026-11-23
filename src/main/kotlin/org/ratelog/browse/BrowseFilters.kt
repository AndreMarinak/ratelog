package org.ratelog.browse

import org.ratelog.Genre
import org.ratelog.MediaType
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** "Popular" presets. [WEEK] is TMDB's weekly trending list; the others are discover orderings. */
enum class BrowsePopular(val param: String) {
    WEEK("week"),
    NOW("now"),
    ALL_TIME("alltime");

    companion object {
        fun parse(value: String): BrowsePopular? = when (value.trim().lowercase().replace('-', '_')) {
            "week", "this_week", "trending" -> WEEK
            "now", "popular", "popular_now", "month", "this_month" -> NOW
            "alltime", "all_time", "all" -> ALL_TIME
            else -> null
        }
    }
}

enum class BrowseSort(val param: String) {
    POPULARITY("popularity"),
    RATING("rating"),
    NEWEST("newest"),
    OLDEST("oldest"),
    TITLE("title");

    companion object {
        fun parse(value: String): BrowseSort? = when (value.trim().lowercase().replace('-', '_')) {
            "popularity", "popular" -> POPULARITY
            "rating", "rated", "highest_rated", "top" -> RATING
            "newest", "new", "release_desc", "latest" -> NEWEST
            "oldest", "old", "release_asc" -> OLDEST
            "title", "name", "a_z", "alphabetical" -> TITLE
            else -> null
        }
    }
}

/**
 * Movie runtime buckets. "Under 90" starts at 40 minutes (feature length) so shorts and the many
 * titles TMDB lists with an unknown runtime of 0 don't flood it.
 */
enum class BrowseRuntime(val param: String, val minMinutes: Int?, val maxMinutes: Int?) {
    SHORT("short", 40, 89),
    MEDIUM("medium", 90, 150),
    LONG("long", 151, null);

    companion object {
        fun parse(value: String): BrowseRuntime? = when (value.trim().lowercase().replace('-', '_')) {
            "short", "under90", "under_90", "lt90" -> SHORT
            "medium", "90_150", "90to150" -> MEDIUM
            "long", "over150", "over_150", "gt150" -> LONG
            else -> null
        }
    }
}

sealed interface BrowseDecade {
    val param: String

    /** 2010 = 2010-2019. */
    data class Of(val startYear: Int) : BrowseDecade {
        override val param: String get() = "${startYear}s"
        val endYear: Int get() = startYear + 9
        fun contains(year: Int) = year in startYear..endYear
    }

    /** "1950s and earlier": everything up to the end of 1959. */
    data object Earlier : BrowseDecade {
        const val LAST_YEAR = 1959
        override val param: String = "earlier"
    }
}

sealed interface BrowseYear {
    val param: String

    data class Of(val year: Int) : BrowseYear {
        override val param: String get() = year.toString()
    }

    /** Movies only: not released yet. */
    data object Upcoming : BrowseYear {
        override val param: String = "upcoming"
    }
}

/**
 * Everything the Discover page / browse API filters by. The URL (see [toParams]) fully describes it, so a
 * view can be shared or bookmarked. Combination rules are applied by [BrowseParamParser]:
 * a year wins over a decade it isn't in, `popular` wins over `sort`.
 */
data class BrowseFilters(
    val type: MediaType = MediaType.movie,
    val decade: BrowseDecade? = null,
    val year: BrowseYear? = null,
    /** TMDB vote average floor, e.g. 7.0 = "7+". */
    val minRating: Double? = null,
    val genre: Genre? = null,
    /** TMDB watch provider id (8 = Netflix), filtered as flatrate in the user's region. */
    val provider: Int? = null,
    /** ISO 639-1 original language. */
    val language: String? = null,
    /** Movies only. */
    val runtime: BrowseRuntime? = null,
    val popular: BrowsePopular? = null,
    val sort: BrowseSort? = null,
    val hideRated: Boolean = false,
    val hideWatchlist: Boolean = false,
    /**
     * Leave out titles the viewer has seen: rated, or marked Watched ([LibraryMark.seen]). On by default, like
     * the Discover page's toggle; only `hideSeen=0` is written to URLs. The API turns it off unless asked
     * (see [BrowseParamParser.parse]).
     */
    val hideSeen: Boolean = true,
) {
    val isMovie: Boolean get() = type == MediaType.movie

    /** True when the user's own library hides some TMDB results. */
    val hidesLibrary: Boolean get() = hideRated || hideWatchlist || hideSeen

    /**
     * No filter chosen: only the type and the "seen" choice, which are view settings. Discover shows its
     * category rows for this state and the poster grid for anything else.
     */
    val isUnfiltered: Boolean get() = this == BrowseFilters(type = type, hideSeen = hideSeen)

    /** The decade the year menu should highlight: the explicit one, or the chosen year's. */
    val effectiveDecade: BrowseDecade?
        get() = when (val y = year) {
            is BrowseYear.Of -> decade ?: decadeOf(y.year)
            BrowseYear.Upcoming -> null
            null -> decade
        }

    /** Switch between movies and TV, keeping what still applies (genres map to the other type's nearest genre). */
    fun withType(newType: MediaType): BrowseFilters {
        if (newType == type) return this
        return copy(
            type = newType,
            genre = genre?.let { BrowseGenres.equivalent(it, newType) },
            runtime = if (newType == MediaType.movie) runtime else null,
            year = if (newType == MediaType.tvshow && year == BrowseYear.Upcoming) null else year,
        )
    }

    fun withDecade(newDecade: BrowseDecade?) = copy(decade = newDecade, year = null)

    fun withYear(newYear: BrowseYear?) = when (newYear) {
        BrowseYear.Upcoming -> copy(year = newYear, decade = null)
        else -> copy(year = newYear)
    }

    fun withPopular(newPopular: BrowsePopular?) = copy(popular = newPopular, sort = null)

    fun withSort(newSort: BrowseSort?) = copy(sort = newSort, popular = null)

    /** Canonical query parameters, in a stable order. `type` is always present. */
    fun toParams(): List<Pair<String, String>> = buildList {
        add("type" to typeParam(type))
        popular?.let { add("popular" to it.param) }
        decade?.let { add("decade" to it.param) }
        year?.let { add("year" to it.param) }
        minRating?.let { add("minRating" to formatRating(it)) }
        genre?.let { add("genre" to BrowseGenres.slug(it)) }
        provider?.let { add("provider" to it.toString()) }
        language?.let { add("language" to it) }
        runtime?.let { add("runtime" to it.param) }
        sort?.let { add("sort" to it.param) }
        if (hideRated) add("hideRated" to "1")
        if (hideWatchlist) add("hideWatchlist" to "1")
        if (!hideSeen) add("hideSeen" to "0")
    }

    /** Link to `/discover` for this state. Page 1 is implied and left out. */
    fun href(page: Int = 1): String = link(PATH, toParams() + if (page > 1) listOf("page" to page.toString()) else emptyList())

    companion object {
        /** The Discover page (formerly `/browse`, which now redirects here). */
        const val PATH = "/discover"

        fun link(path: String, params: List<Pair<String, String>>): String =
            path + "?" + params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }

        fun typeParam(type: MediaType) = when (type) {
            MediaType.movie -> "movie"
            MediaType.tvshow -> "tv"
        }

        fun decadeOf(year: Int): BrowseDecade.Of = BrowseDecade.Of(Math.floorDiv(year, 10) * 10)

        /** "7" for whole numbers, "7.5" otherwise. */
        fun formatRating(value: Double): String =
            if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }
}

/** TMDB's movie and TV genre lists (`/genre/movie/list`, `/genre/tv/list`), using the ids in [Genre]. */
object BrowseGenres {
    val MOVIE: List<Genre> = listOf(
        Genre.ACTION, Genre.ADVENTURE, Genre.ANIMATION, Genre.COMEDY, Genre.CRIME, Genre.DOCUMENTARY, Genre.DRAMA,
        Genre.FAMILY, Genre.FANTASY, Genre.HISTORY, Genre.HORROR, Genre.MUSIC, Genre.MYSTERY, Genre.ROMANCE,
        Genre.SCIENCE_FICTION, Genre.TV_MOVIE, Genre.THRILLER, Genre.WAR, Genre.WESTERN,
    )

    val TV: List<Genre> = listOf(
        Genre.ACTION_ADVENTURE, Genre.ANIMATION, Genre.COMEDY, Genre.CRIME, Genre.DOCUMENTARY, Genre.DRAMA,
        Genre.FAMILY, Genre.KIDS, Genre.MYSTERY, Genre.NEWS, Genre.REALITY, Genre.SCI_FI_FANTASY, Genre.SOAP,
        Genre.TALK, Genre.WAR_POLITICS, Genre.WESTERN,
    )

    private val MOVIE_TO_TV = mapOf(
        Genre.ACTION to Genre.ACTION_ADVENTURE,
        Genre.ADVENTURE to Genre.ACTION_ADVENTURE,
        Genre.SCIENCE_FICTION to Genre.SCI_FI_FANTASY,
        Genre.FANTASY to Genre.SCI_FI_FANTASY,
        Genre.WAR to Genre.WAR_POLITICS,
    )

    private val TV_TO_MOVIE = mapOf(
        Genre.ACTION_ADVENTURE to Genre.ACTION,
        Genre.SCI_FI_FANTASY to Genre.SCIENCE_FICTION,
        Genre.WAR_POLITICS to Genre.WAR,
        Genre.KIDS to Genre.FAMILY,
    )

    fun forType(type: MediaType): List<Genre> = if (type == MediaType.movie) MOVIE else TV

    /** The genre itself if the type has it, else the other type's nearest genre, else null (e.g. Horror on TV). */
    fun equivalent(genre: Genre, type: MediaType): Genre? = when {
        genre in forType(type) -> genre
        type == MediaType.tvshow -> MOVIE_TO_TV[genre]
        else -> TV_TO_MOVIE[genre]
    }

    /** `science-fiction`, `action-adventure`... */
    fun slug(genre: Genre): String = genre.name.lowercase().replace('_', '-')

    /** A TMDB id or a slug (`horror`, `Science_Fiction`, `sci-fi`), resolved for [type]. Null if unknown there. */
    fun parse(value: String, type: MediaType): Genre? {
        val key = value.trim().lowercase().replace(' ', '-').replace('_', '-').replace("&", "").replace("--", "-")
        val genre = key.toIntOrNull()?.let(Genre::fromTmdbId)
            ?: ALIASES[key]
            ?: Genre.entries.find { slug(it) == key }
            ?: return null
        return equivalent(genre, type)
    }

    private val ALIASES = mapOf(
        "sci-fi" to Genre.SCIENCE_FICTION,
        "scifi" to Genre.SCIENCE_FICTION,
        "action-adventure" to Genre.ACTION_ADVENTURE,
        "sci-fi-fantasy" to Genre.SCI_FI_FANTASY,
        "war-politics" to Genre.WAR_POLITICS,
        "kids" to Genre.KIDS,
    )
}
