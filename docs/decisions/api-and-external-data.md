# API and external data

## API (`/api/v1`)

- **Separate stateless security chain** (`ApiSecurityConfig`, `@Order(1)`, matcher `/api/v1/**`): token auth only, no sessions, no CSRF. The browser chain is untouched; a logged-in browser session does *not* authenticate API calls.
- **Tokens:** `rl_` + 32 random bytes (base64url), stored as SHA-256 hashes, shown once, revocable at `/settings/api`. Accepted as `Authorization: Bearer` or `X-API-Key`. Last-used time is written at most once a minute.
- **Other users' lists return 404, not 403**, so ids can't be probed.
- **Batch item import never fails as a whole:** each item is `added`, `alreadyInList` or `unresolved` (with a reason and candidates). Max 100 items per request.
- **Strict title matching** (`TitleMatcher`): exact normalized title (case, accents, punctuation, leading article, `&`/`and`) against the English or original title; with a year, exact year wins, else ±1 year if only one fits; otherwise `ambiguous`/`not_found` with candidates. Rationale: a wrong title silently added is worse than asking. Real cases: Aladdin (1992) and Mulan (1998) have same-name/same-year knockoffs on TMDB; Hamilton's TMDB date moved to its 2025 re-release.
- **`type` is required with `tmdbId`** because TMDB movie and TV ids are separate namespaces.
- **API never logs TMDB exception messages** (they contain the `api_key` URL); TMDB failures map to `tmdb_error` / `tmdb_not_configured`.

- **API guide for AI assistants (user, 2026-11-24: "make a documentation page under the key thing so someone could copy and paste it into Claude or chat or whatnot"):** `/settings/api` has a "Use Ratelog with an AI assistant" box: the guide in a read-only text area, a Copy button (clipboard API, falling back to selecting the text) and a Download (.md) link; the same text is at `GET /settings/api/guide.md` for logged-in users. The text is `src/main/resources/api-guide.md` (about 130 lines, a condensed `docs/api.md`) with `{{BASE_URL}}` filled from the request (so it names the site it is served from), a `<YOUR_TOKEN>` placeholder (never a real token) and a warning that a token gives full account access. Admin-only features and tags (hidden behind a flag) are left out. Why a file: one place to keep current; `CLAUDE.md`'s documentation table requires updating it with `docs/api.md`.

## Standing decisions

- **External scores via MDBList.** There's no free official source: Rotten Tomatoes licenses only to partners and bans scraping; IMDb's API costs ~$400k/yr; OMDb lacks RT audience scores and TV. MDBList returns IMDb, RT critics/audience, Metacritic and Letterboxd in one call (free 1,000/day). Cached per title (daily for new releases, weekly within a year, monthly older, 7 days for "no data"), process-wide backoff on 429. RT is labeled "Rotten Tomatoes®" and linked; the app never scrapes RT itself. Fine for a private, login-only household instance; not for a public site.
- **The MDBList key is pasted on Admin → Configuration** (user, 2026-11-23: "make it the same where I paste it in"). Same
  form as the TMDB key: a password field that starts empty, only the last 4 characters shown back ("…abcd"), empty = keep. The
  saved key wins over `MDBLIST_API_KEY` (still the fallback, noted on the page when it's the only source) and is read on every
  lookup (`ConfigMdbListKeySource`, one indexed row), so it works without a restart; a different key also lifts the
  one-hour pause a rejected key causes. Unlike the TMDB key, the variable is not copied into the database at startup, so a
  key pasted on the page is never overwritten by a redeploy.
- **Rotten Tomatoes search link next to IMDb's** (user, 2026-11-23: "how the IMDb search thing is there can u add a rotten
  tomatoes through search url"). Title pages (movies and TV) show a "Rotten Tomatoes® ↗" badge styled like the IMDb link
  (external-link icon, new tab, `rel="noopener noreferrer"`) whenever the English title is known. It links to RT's own search
  (`rottentomatoes.com/search?search=<title>`) because there is no free way to resolve RT's page id (see "External scores via
  MDBList"); the app never fetches RT. When MDBList has RT scores, the existing score badge is already that link, so the plain
  badge is left out and there is never a second RT badge.
