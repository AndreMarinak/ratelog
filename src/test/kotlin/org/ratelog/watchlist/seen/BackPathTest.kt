package org.ratelog.watchlist.seen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackPathTest {

    @Test
    fun `back wins, then the referer's path and query, then the fallback`() {
        assertEquals("/watchlist?status=watching&type=tv", BackPath.resolve("/watchlist?status=watching&type=tv", "http://h/x", "/f"))
        assertEquals("/discover?type=tv", BackPath.resolve(null, "http://localhost:8150/discover?type=tv", "/f"))
        assertEquals("/discover?type=tv", BackPath.resolve("  ", "http://localhost:8150/discover?type=tv#top", "/f"), "the referer's fragment is dropped")
        assertEquals("/f", BackPath.resolve(null, null, "/f"))
    }

    @Test
    fun `only local paths are used`() {
        assertEquals("/f", BackPath.resolve("watchlist", null, "/f"), "relative")
        assertEquals("/x", BackPath.resolve("//evil.example/x", null, "/f"), "protocol-relative keeps only the path, never the host")
        assertEquals("/f", BackPath.resolve("/\\evil.example", null, "/f"), "backslash tricks")
        assertEquals("/f", BackPath.resolve("/a\r\nLocation: http://evil", null, "/f"), "header injection")
        assertEquals("/f", BackPath.resolve("javascript:alert(1)", null, "/f"))
        assertEquals("/x", BackPath.resolve("https://evil.example/x", null, "/f"), "a full URL keeps only its path")
        assertEquals("/list/7?mark=1#list-item-3", BackPath.resolve("/list/7?mark=1#list-item-3", null, "/f"))
    }
}
