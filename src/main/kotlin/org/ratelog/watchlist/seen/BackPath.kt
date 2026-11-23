package org.ratelog.watchlist.seen

import java.net.URI

/**
 * Where a form post redirects back to: an explicit `back` field (kept even if the browser strips the Referer),
 * else the Referer, else [fallback]. Only a local path (+ query and #fragment) is ever used, so a redirect never
 * leaves the app.
 */
object BackPath {
    fun resolve(back: String?, referer: String?, fallback: String): String =
        local(back, keepFragment = true) ?: local(referer, keepFragment = false) ?: fallback

    /** The path part of [value] if it is a same-app path or URL, else null. */
    fun local(value: String?, keepFragment: Boolean): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.any { it.isISOControl() } || raw.contains('\\')) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        // A relative path ("watchlist") is refused; from a full URL (the Referer) only the path is kept.
        if (uri.scheme == null && !raw.startsWith("/")) return null
        val path = uri.rawPath?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: return null
        return path +
            (uri.rawQuery?.let { "?$it" } ?: "") +
            (if (keepFragment) uri.rawFragment?.let { "#$it" } ?: "" else "")
    }
}
