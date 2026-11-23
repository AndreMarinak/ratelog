package org.ratelog.extras.web

import org.ratelog.MediaType
import org.ratelog.extras.gettitleinfo.TitleInfo
import java.net.URLEncoder
import java.util.Locale

/** Display-ready values for `fragments/title-info.html`. */
class TitleInfoView(val info: TitleInfo) {
    val isMovie: Boolean get() = info.type == MediaType.movie

    val tmdbScore: String? get() = info.tmdbVoteAverage?.let(::oneDecimal)
    val tmdbVotes: String? get() = info.tmdbVoteCount?.toLong()?.let(::compactCount)
    val runtimeMinutes: Int? get() = info.runtimeMinutes
    val numberOfSeasons: Int? get() = info.numberOfSeasons

    val trailerKey: String? get() = info.trailerYoutubeKey
    val trailerEmbedUrl: String? get() = trailerKey?.let { "https://www.youtube-nocookie.com/embed/$it?autoplay=1&rel=0" }
    val trailerThumbnailUrl: String? get() = trailerKey?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    val trailerWatchUrl: String? get() = info.trailerUrl

    val imdbUrl: String? get() = info.imdbUrl
    val imdbRating: String? get() = info.scores?.imdbRating?.let(::oneDecimal)
    val imdbVotes: String? get() = info.scores?.imdbVotes?.let(::compactCount)

    val rtCritics: Int? get() = info.scores?.rtCritics
    val rtAudience: Int? get() = info.scores?.rtAudience
    val hasRt: Boolean get() = rtCritics != null || rtAudience != null
    val rtSearchUrl: String? get() = info.title?.let { "https://www.rottentomatoes.com/search?search=" + URLEncoder.encode(it, Charsets.UTF_8) }

    val metacritic: Int? get() = info.scores?.metacritic
    val metacriticTone: String? get() = metacritic?.let { if (it >= 61) "good" else if (it >= 40) "mixed" else "bad" }

    val letterboxd: String? get() = info.scores?.letterboxd?.let(::oneDecimal)
    /** Letterboxd resolves TMDB movie ids itself; it has no reliable equivalent for shows. */
    val letterboxdUrl: String? get() = if (isMovie) "https://letterboxd.com/tmdb/${info.tmdbId}/" else null

    val hasScores: Boolean get() = info.scores != null
    val hasFacts: Boolean get() = tmdbScore != null || runtimeMinutes != null || numberOfSeasons != null

    companion object {
        fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

        /** 950 -> "950", 25_400 -> "25K", 2_134_567 -> "2.1M". */
        fun compactCount(value: Long): String = when {
            value >= 1_000_000 -> trimZero(String.format(Locale.ROOT, "%.1f", value / 1_000_000.0)) + "M"
            value >= 10_000 -> "${value / 1_000}K"
            value >= 1_000 -> trimZero(String.format(Locale.ROOT, "%.1f", value / 1_000.0)) + "K"
            else -> value.toString()
        }

        private fun trimZero(value: String) = value.removeSuffix(".0")
    }
}
