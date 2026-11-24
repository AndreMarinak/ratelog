# Security and limits

## Security hardening (2026-11-23)

The user asked for a hardening pass on 2026-11-23. The rules now:
- **Account changes apply to open sessions at once.** Every browser request re-reads the signed-in account from the
  database (one primary-key lookup; static files skip it), in `security/SessionRevalidationFilter`, which runs after the
  remember-me filter and before authorization. A deleted account, or a session that signed in before the password
  changed, is signed out (it continues as anonymous, so pages send it to the login page). A new role, username, email or
  language replaces the session's details and authorities, so the admin URL rule and every handler's
  `currentUser.role` check see the current role. Remember-me logins follow the same rules: they load the account fresh,
  and a password change invalidates the cookie. Changing your own password keeps your current session (the profile
  form refreshes it) and ends the others. API tokens already read the account on every request.
- **Sign-in throttling** (`security/LoginThrottle`, in memory, resets on restart): 10 failed sign-ins for one username
  within 15 minutes lock that username for 15 minutes; 30 failures from one client IP lock that IP for 15 minutes. While
  locked, sign-ins are refused before the password is checked, even a correct one, and the login page says "Too many
  failed sign-in attempts. Wait 15 minutes". Locks always expire, and attempts made during a lock don't extend it.
  Unknown usernames are counted exactly like real ones, so the answers never show which accounts exist. A correct
  password clears that username's count. The client IP is the `X-Real-IP` header Railway's edge sets
  (`RATELOG_CLIENT_IP_HEADER`), else the socket address. At most 10,000 keys are tracked.
- **Password rules for new and changed passwords:** at least 8 characters, at most 72 bytes (the most BCrypt uses).
  Signing in never applies them, so older, shorter passwords keep working, also as the "current password" when changing
  to a new one. `ADMIN_PASSWORD` keeps its own 12-character minimum. Changing your own account always needs your current
  password, the superadmin included; only a superadmin editing someone else skips it.
- **Request rate limit** (approved 2026-11-23, `security/RateLimitFilter` + `RequestRateLimiter`, in memory): one token
  bucket per key for reads and one for writes (POST, PUT, PATCH, DELETE). Keys: the API token for `/api/v1`, the account
  for browser sessions, the client IP otherwise. A bucket holds a minute's worth plus a burst and refills at the
  per-minute rate. Defaults: **600 reads a minute + 100 burst, 60 writes a minute + 30 burst**, set in `application.yml`
  (`ratelog.rate-limit.*`, env `RATELOG_RATE_LIMIT_ENABLED`, `..._READS_PER_MINUTE`, `..._READ_BURST`,
  `..._WRITES_PER_MINUTE`, `..._WRITE_BURST`). Static files and `/api/health` are exempt; sign-in posts are handled by the
  throttle above before this filter. Over the limit: `429` with `Retry-After`; the API answers its usual JSON error
  (`rate_limited`), the in-place scripts get `{ok: false, message}` (or the page's error message) and show it as a toast
  instead of re-posting (the list reorder editor then reloads to show the saved order), plain form posts get a short page. Sized so heavy normal use never trips it: ticking off a
  250-title list at 1.5 taps a second, Discover's lazy rows, the add-to-list sheet and Claude's batched list imports.
- **Letterboxd import limits** (`import/letterboxd/LetterboxdImportLimits`): at most **2 MB and 10,000 rated rows** per
  file (a ratings.csv row is about 60 bytes, so 10,000 rows is about 600 KB; a bigger export can be split, and
  re-importing skips titles already rated). The file is parsed during the upload, so format and size problems show at
  once; the TMDB lookups then run in the background (the upload returns right away, and Railway closes requests that
  are idle for 5 minutes). One import per account, two at once across the app, 4 lookup threads each, and all imports
  share **10 TMDB requests a second** of the app's 40, so pages keep the rest. An import stops starting new rows after
  60 minutes. The method-wide transaction is gone: each save commits on its own and no connection is held during
  lookups. The result is logged as counts only.
- **Cookies and proxy headers.** The session cookie is `Secure` and `SameSite=Lax`; browsers accept Secure cookies on
  `http://localhost`, and `SESSION_COOKIE_SECURE=false` allows signing in over plain http from another host (a phone on
  the LAN). The remember-me cookie is `SameSite=Lax` too. `security/ProxyHeadersConfig` replaces Spring's forwarded-header
  filter with one that trusts only `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-For` (what Railway's edge
  sets) and drops `Forwarded`, `X-Forwarded-Prefix`, `X-Forwarded-Port` and `X-Forwarded-Ssl`; redirects are relative
  (`Location: /path`, status 303 behind the proxy), the login redirect included. Cross-origin requests never carry the
  browser's cookies: the CORS mapping in `WebConfig` no longer allows credentials (API calls authenticate with a token).
- **Browser security headers** (`security/SecurityHeaders`): `Content-Security-Policy` with `default-src 'self'`, images
  from `'self'`, `data:`, `image.tmdb.org` and `i.ytimg.com`, frames only from `www.youtube-nocookie.com`,
  `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`; scripts and styles from
  `'self'` plus `'unsafe-inline'`, because templates still have inline `<script>` blocks, `on*=` handlers and `style=`
  attributes (moving them into the static files would allow a strict policy; roadmap). No fonts or scripts come from a
  CDN. Also `Referrer-Policy: strict-origin-when-cross-origin` (YouTube embeds need the origin),
  `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()` (nothing the trailer iframe asks
  for), `X-Frame-Options: DENY`. The policy is **enforced**: a browser walkthrough of every page (home, Discover, title
  pages with the trailer playing, lists, tick-off, watchlist, ratings, search, profile and its modals, community, admin,
  API settings, the add-account page, login) showed no violations. `RATELOG_CSP_REPORT_ONLY=true` switches it to
  report-only without new code, in case a page breaks. New pages and scripts must stay inside this policy (no CDN, no new hosts).
- **Admin page secrets.** The TMDB key is never sent back to the browser: the field starts empty, a hint shows
  "ends in …abcd", typing a new key replaces it and saving the empty field keeps it. The form only accepts the TMDB key
  (the remember-me signing key is not editable there).
- **Redirects back to the Referer** (follow buttons, deleting a rating, the status form) go through `BackPath`: only a
  same-site path is used, anything else falls back to a fixed page.
- **Logs never carry secrets.** Upstream HTTP clients put API keys in query strings and Spring's I/O errors repeat the
  URL, so the daily TMDB sync logs the title's TMDB id and exception class only, and `GlobalExceptionHandler` (which
  used to log nothing) and the API's catch-all log the request method, path and a stack trace whose messages have
  `api_key=`, `apikey=`, `token=`... values masked (`security/LogSafe`). Malformed requests log one line without a trace.
- **`limit` parameters are clamped to 1-1000** (`system/PageLimits`) on the feed, profile and ranking pages: negative
  values used to reach the database.
- **The container runs as an unprivileged user** (uid 10001) on the pinned runtime image; the app only writes to `/tmp`.
  The build image is `maven:3.9.16-eclipse-temurin-21`.
- **Pinned dependency versions.** Spring Boot 3.5.16 is the last free 3.5 release (free support ended 2026-06-30), so
  `pom.xml` overrides the managed versions of the bundled libraries that have newer patch releases, staying on the same
  minor line: Tomcat 10.1.62, PostgreSQL JDBC 42.7.15, Jackson 2.21.8, Logback 1.5.38, Thymeleaf and its Spring Security
  extras 3.1.7.RELEASE, Hibernate Validator 8.0.6.Final (checked on Maven Central, 2026-11-23). Spring Framework 6.2.19 and
  Spring Security 6.5.11 are already the last free releases. **To bump:** read
  `https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml` for the newest patch of the same minor line
  (never a new minor or major, e.g. Tomcat 10.1.x, not 11), change the property, run `mvn -B -ntp clean package` and the
  browser check. **Drop the overrides** when moving to Spring Boot 4 (roadmap), which manages newer versions itself.
