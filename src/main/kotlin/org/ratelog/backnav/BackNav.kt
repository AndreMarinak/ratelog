package org.ratelog.backnav

import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI

/*
 * Fork: back links on title pages (docs/design-decisions.md, "Back links on title pages").
 * A title page links back to the page you opened it from: its "origin", the last page in this tab that is not a title.
 * static/back-nav.js tracks the origin per tab; this file is the server's part: what each page is called
 * ("Discover", "Search results", a list's name...) and the link a title page renders before any JS runs,
 * taken from the Referer when it is one of these pages, else Home.
 */

/** A page's name in a back link: a message key, with one argument for the keys that take one (a list's name). */
data class BackNavLabel(val key: String, val arg: String? = null) {
    companion object {
        val HOME = BackNavLabel("nav.home")
        val SEARCH = BackNavLabel("backnav.search")
        val DISCOVER = BackNavLabel("nav.discover")
        val RATINGS = BackNavLabel("nav.ratings")
        val WATCHLIST = BackNavLabel("nav.watchlist")
        val LISTS = BackNavLabel("nav.lists")
        val OFFICIAL_LISTS = BackNavLabel("lists.official")
        val COMMUNITY = BackNavLabel("nav.community")
        val TAGS = BackNavLabel("nav.tags")
        val STATS = BackNavLabel("nav.stats")
        val OWN_PROFILE = BackNavLabel("backnav.profile.own")

        fun list(name: String) = BackNavLabel("backnav.name", name)
        fun profile(username: String) = BackNavLabel("list.back.profile", username)
    }
}

/**
 * The back link a title page renders on the server. [pending] means the page was opened from another title, so only
 * back-nav.js knows the origin: the link is kept invisible for a moment until the script fills it in.
 */
data class BackNavLink(
    val href: String,
    val label: BackNavLabel,
    val titleName: String?,
    val pending: Boolean = false,
)

/** A list as far as its back-link name is concerned. */
data class BackNavList(val ownerId: User.Id, val isPublic: Boolean, val name: String)

/** Names the back links need from the database. */
interface BackNavNames {
    fun list(listId: Long): BackNavList?
    fun username(userId: Long): String?
}

object BackNavPages {
    const val DEFAULT_HREF = "/"
    val DEFAULT_LABEL = BackNavLabel.HOME

    /** Title pages, and (fork, 2026-11-24) person pages, which take part in the back links like a title. */
    private val TITLE_PATH = Regex("^/(movie|tv|person)/\\d+/?$")
    private val LIST_PATH = Regex("^/list/(\\d+)/?$")
    private val PROFILE_PATH = Regex("^/profile/(\\d+)/?$")

    fun isTitle(path: String): Boolean = TITLE_PATH.matches(path)

    /** The name of a page that is known by its path alone, or null (titles, lists, profiles, settings...). */
    fun fixedLabel(path: String, query: String?): BackNavLabel? = when (path.trimEnd('/').ifEmpty { "/" }) {
        "/" -> if (param(query, "q").isNullOrBlank()) BackNavLabel.HOME else BackNavLabel.SEARCH
        "/discover", "/browse" -> BackNavLabel.DISCOVER
        "/ratings", "/movies", "/tvshows" -> BackNavLabel.RATINGS
        "/watchlist" -> BackNavLabel.WATCHLIST
        "/lists" -> BackNavLabel.LISTS
        "/lists/official" -> BackNavLabel.OFFICIAL_LISTS
        "/community" -> BackNavLabel.COMMUNITY
        "/tags" -> BackNavLabel.TAGS
        "/stats" -> BackNavLabel.STATS
        else -> null
    }

    /**
     * The name of the page at [path] for [viewer], looking up list and profile names. A list the viewer can't open
     * (private, someone else's) has no name here, so a crafted Referer can't reveal it.
     */
    fun label(path: String, query: String?, viewer: User.Id?, names: BackNavNames): BackNavLabel? {
        if (isTitle(path)) return null
        LIST_PATH.matchEntire(path)?.let { match ->
            val list = match.groupValues[1].toLongOrNull()?.let(names::list) ?: return null
            return if (list.isPublic || list.ownerId == viewer) BackNavLabel.list(list.name) else null
        }
        PROFILE_PATH.matchEntire(path)?.let { match ->
            val id = match.groupValues[1].toLongOrNull() ?: return null
            if (id == viewer?.value) return BackNavLabel.OWN_PROFILE
            return names.username(id)?.let(BackNavLabel::profile)
        }
        if (path.trimEnd('/') == "/profile") return BackNavLabel.OWN_PROFILE
        return fixedLabel(path, query)
    }

    /**
     * The link a title page renders before JS: the page in [referer] when it is a named page of this app
     * ([host] is the request's host), else Home. Only the Referer's path and query are used.
     */
    fun titleLink(referer: String?, host: String?, viewer: User.Id?, names: BackNavNames, titleName: String?): BackNavLink {
        val local = sameHostPath(referer, host)
            ?: return BackNavLink(DEFAULT_HREF, DEFAULT_LABEL, titleName)
        val uri = runCatching { URI(local) }.getOrNull()
            ?: return BackNavLink(DEFAULT_HREF, DEFAULT_LABEL, titleName)
        val path = uri.rawPath ?: "/"
        if (isTitle(path)) return BackNavLink(DEFAULT_HREF, DEFAULT_LABEL, titleName, pending = true)
        val label = label(path, uri.rawQuery, viewer, names)
            ?: return BackNavLink(DEFAULT_HREF, DEFAULT_LABEL, titleName)
        return BackNavLink(local, label, titleName)
    }

    /** The path (+ query) of [referer] if it is a URL on [host]; anything else (another site, garbage) is null. */
    fun sameHostPath(referer: String?, host: String?): String? {
        val raw = referer?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (host.isNullOrBlank()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || !host.equals(uri.host, ignoreCase = true)) return null
        return BackPath.local(raw, keepFragment = false)
    }

    private fun param(query: String?, name: String): String? =
        if (query.isNullOrEmpty()) null
        else runCatching {
            UriComponentsBuilder.fromUriString("/?$query").build().queryParams.getFirst(name)
                ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        }.getOrNull()
}
