package org.ratelog.security

/**
 * Browser security headers for the web UI (set in SecurityConfig).
 *
 * The CSP lists exactly what the pages load: the app's own scripts, styles, fonts and icons; TMDB posters and logos;
 * YouTube thumbnails and the privacy-enhanced trailer embed. `data:` images are CSS-inlined SVG icons.
 * Scripts and styles still allow inline code because templates have inline `<script>` blocks, `on*=` handlers and
 * `style=` attributes; moving those into the static files would allow dropping `'unsafe-inline'` (roadmap).
 * No `upgrade-insecure-requests`: it would break plain-http local runs.
 */
object SecurityHeaders {
    val CONTENT_SECURITY_POLICY: String = listOf(
        "default-src 'self'",
        "script-src 'self' 'unsafe-inline'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: https://image.tmdb.org https://i.ytimg.com",
        "font-src 'self'",
        "connect-src 'self'",
        "frame-src https://www.youtube-nocookie.com",
        "object-src 'none'",
        "base-uri 'self'",
        "form-action 'self'",
        "frame-ancestors 'none'",
    ).joinToString("; ")

    /** Only features the browsers know (unknown names log console errors), and none the trailer iframe asks for. */
    const val PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
}
