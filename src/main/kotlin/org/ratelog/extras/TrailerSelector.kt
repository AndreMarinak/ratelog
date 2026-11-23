package org.ratelog.extras

/**
 * Picks the trailer to show from TMDB's video list: an official YouTube trailer if there is one,
 * otherwise any YouTube trailer, then teasers. Ties go to English, higher resolution, then the earliest upload
 * (usually the main trailer rather than a late "final" one).
 */
object TrailerSelector {
    /** YouTube ids are 11 chars today; the key ends up in URLs, so anything else is rejected. */
    private val YOUTUBE_KEY = Regex("^[A-Za-z0-9_-]{6,32}$")

    fun select(videos: List<TmdbVideo>): String? =
        videos
            .filter { it.site.equals("YouTube", ignoreCase = true) && it.key != null && YOUTUBE_KEY.matches(it.key) }
            .mapNotNull { video -> tier(video)?.let { video to it } }
            .sortedWith(
                compareBy<Pair<TmdbVideo, Int>> { it.second }
                    .thenBy { if (it.first.language.equals("en", ignoreCase = true)) 0 else 1 }
                    .thenByDescending { it.first.size ?: 0 }
                    .thenBy { it.first.publishedAt ?: "￿" }
            )
            .firstOrNull()
            ?.first
            ?.key

    private fun tier(video: TmdbVideo): Int? {
        val official = video.official == true
        return when {
            video.type.equals("Trailer", ignoreCase = true) -> if (official) 0 else 1
            video.type.equals("Teaser", ignoreCase = true) -> if (official) 2 else 3
            else -> null
        }
    }
}
