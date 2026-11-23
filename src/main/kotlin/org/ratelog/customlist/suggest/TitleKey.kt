package org.ratelog.customlist.suggest

import org.ratelog.MediaType

/** A movie or show by TMDB id. TMDB's movie and TV ids are separate namespaces, so the type is part of the key. */
data class TitleKey(val type: MediaType, val tmdbId: Int) {
    /** `movie:862` / `tv:1396`: the key tile-actions.js and the poster tiles use (`data-key`). */
    val tileKey: String get() = (if (type == MediaType.movie) "movie:" else "tv:") + tmdbId

    /** `m862` / `t1396`: the short form the row sends back in `skip`, so a long session still fits in a URL. */
    val compact: String get() = (if (type == MediaType.movie) "m" else "t") + tmdbId

    companion object {
        private val COMPACT = Regex("""^([mt])(\d{1,9})$""")
        private val LONG = Regex("""^(movie|tv|tvshow):(\d{1,9})$""")
        private val SEPARATORS = Regex("""[\s.,;]+""")

        /** `m862`, `t1396`, `movie:862`, `tv:1396` or `tvshow:1396`; anything else is null. */
        fun parse(value: String): TitleKey? {
            val raw = value.trim().lowercase()
            COMPACT.matchEntire(raw)?.let { m ->
                val id = m.groupValues[2].toInt().takeIf { it > 0 } ?: return null
                return TitleKey(if (m.groupValues[1] == "m") MediaType.movie else MediaType.tvshow, id)
            }
            LONG.matchEntire(raw)?.let { m ->
                val id = m.groupValues[2].toInt().takeIf { it > 0 } ?: return null
                return TitleKey(if (m.groupValues[1] == "movie") MediaType.movie else MediaType.tvshow, id)
            }
            return null
        }

        /**
         * A `skip` parameter: keys separated by dots, commas or spaces. Unreadable keys are ignored; only the last
         * [max] are kept (the row sends the most recent ones last), so a crafted value can't make the server do much.
         */
        fun parseList(raw: String?, max: Int): Set<TitleKey> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.takeLast(max * 16).split(SEPARATORS).mapNotNull { parse(it) }.distinct().takeLast(max).toSet()
        }
    }
}
