# Design decisions

Why this fork is built the way it is. Newest decisions go at the bottom of each section. Related: [roadmap.md](roadmap.md), [bugs-and-issues.md](bugs-and-issues.md), [api.md](api.md).

## Product direction

- **Fork Ratelog instead of building from scratch or adopting Floppy/Scrob/Yamtrack.** The user preferred Ratelog's Letterboxd-like look and its five-category rating. Missing features (API, watchlist, tags...) are added in the fork; upstream updates are merged from `upstream/master`.
- **Movies and TV, streaming-first.** No Plex/Jellyfin integration and no media-server features; logging is manual, bulk lists come from Claude via the API.
- **No MCP connector.** The user only wants a JSON API that Claude can call; a connector was explicitly declined.
- **Upstream branding removed, credit kept.** The Buy Me a Coffee button and its third-party script were removed. AGPL-3.0 requires keeping the license and a link to the running code's source, so the profile footer links to this fork and the README credits golmenero/ratelog.

## Codebase conventions

- **Fork migrations are numbered `V1000+`** with `spring.flyway.out-of-order: true`. Upstream uses `V1..V21` and will keep adding `V22, V23...`; separate ranges mean no collisions, and out-of-order lets lower numbers apply after higher ones (verified when `V1010` applied after `V1030` on a copy of production).
- **Reserved migration ranges per feature** (see roadmap) so parallel work never collides.
- **Prefer new files over editing upstream files.** Model data for upstream pages is added with `@ControllerAdvice(assignableTypes = ...)` or interceptors instead of editing upstream controllers; templates get one `th:replace` include line per feature. Keeps upstream merges small.
- **Parallel development in git worktrees** (`../ratelog-worktrees/<feature>`), one agent per feature, append-only `messages.properties` sections per feature, merged and tested on `master` one at a time.

## API (`/api/v1`)

- **Separate stateless security chain** (`ApiSecurityConfig`, `@Order(1)`, matcher `/api/v1/**`): token auth only, no sessions, no CSRF. The browser chain is untouched; a logged-in browser session does *not* authenticate API calls.
- **Tokens:** `rl_` + 32 random bytes (base64url), stored as SHA-256 hashes, shown once, revocable at `/settings/api`. Accepted as `Authorization: Bearer` or `X-API-Key`. Last-used time is written at most once a minute.
- **Other users' lists return 404, not 403**, so ids can't be probed.
- **Batch item import never fails as a whole:** each item is `added`, `alreadyInList` or `unresolved` (with a reason and candidates). Max 100 items per request.
- **Strict title matching** (`TitleMatcher`): exact normalized title (case, accents, punctuation, leading article, `&`/`and`) against the English or original title; with a year, exact year wins, else ±1 year if only one fits; otherwise `ambiguous`/`not_found` with candidates. Rationale: a wrong title silently added is worse than asking. Real cases: Aladdin (1992) and Mulan (1998) have same-name/same-year knockoffs on TMDB; Hamilton's TMDB date moved to its 2025 re-release.
- **`type` is required with `tmdbId`** because TMDB movie and TV ids are separate namespaces.
- **API never logs TMDB exception messages** (they contain the `api_key` URL); TMDB failures map to `tmdb_error` / `tmdb_not_configured`.

## Features

- **Quick rating:** category columns nullable; a rating is either all five categories or none (+ score), enforced in handlers and by DB CHECK constraints. Category rankings exclude quick ratings; overall rankings include them. Letterboxd imports create quick ratings.
- **Statuses are separate from upstream's "follow"** (follow = release alerts). Rating a title auto-sets Watched (from none / Want to watch / Watching) via a repository wrapper, so upstream rating handlers stay untouched.
- **Tags are per user**; the Top-page tag filter narrows the list but keeps overall ranks (same as the genre filter).
- **External scores via MDBList.** There's no free official source: Rotten Tomatoes licenses only to partners and bans scraping; IMDb's API costs ~$400k/yr; OMDb lacks RT audience scores and TV. MDBList returns IMDb, RT critics/audience, Metacritic and Letterboxd in one call (free 1,000/day). Cached per title (daily for new releases, weekly within a year, monthly older, 7 days for "no data"), process-wide backoff on 429. RT is labeled "Rotten Tomatoes®" and linked; the app never scrapes RT itself. Fine for a private, login-only household instance; not for a public site.
- **Quick watchlist toggle by TMDB id** (`POST /watchlist/toggle`): poster tiles only know TMDB ids, so the endpoint saves the title locally if needed. The bookmark only toggles *Want to watch*; Watching/Watched/Dropped are left alone (changed on the title page).
- **"New list" inside the add-to-list modal** creates the list and adds the title in one step, then opens the list (same destination as adding to an existing list).
- **Confirmation before destructive one-click actions** (remove from list, remove tag, revoke token) uses a `data-confirm` attribute + native `confirm()` (`static/confirm.js`): works inside modals and without extra UI code.

## Deployment (Railway)

- **Dockerfile build, `/api/health` healthcheck, `PORT=8080` pinned** to match the domain's target port; `server.forward-headers-strategy=framework` so redirects use https behind the proxy.
- **`ADMIN_PASSWORD`** replaces the seeded `admin`/`admin` password at startup, but only while the default is still active and only if 12+ characters; it never overwrites a password changed in the UI.
- **`TMDB_API_KEY` env var always wins** over the value stored in Admin > Configuration (upstream only read it on first start). Obvious placeholders (`<...>`, `REPLACE_ME`, whitespace) are refused with a warning. To manage the key in the UI instead, remove the variable.
- **Readable TMDB error page** instead of a raw JSON 500 when TMDB rejects the key or is down.

## UI

- **Title pages use a two-column layout:** poster (+ where to watch) in a sticky left column; overview, genres, actions, scores/trailer, watchlist status, tags, your rating and seasons in the right column. Single column on phones.
- **Modals are capped to the viewport** (`max-height: 100dvh - 32px`, internal scroll) and become bottom sheets on phones.
- **Poster sizes match display size:** tiles `w342` (+ `w500` for 2x screens), title poster `w500` (+ `w780`), ranked-list thumbnails `w185` (+ `w342`), provider logos `w92`. Upstream used `w185` tiles stretched to ~230px, which looked blurry.
- **Home posters at near-full brightness** with a bottom gradient behind titles (upstream dimmed them to 60%).
- **Home is a dashboard of horizontal rows** (Letterboxd-style): Trending movies first (user preference), then personal rows (only when non-empty), then the other cached TMDB rows (20 each) with "See all" links into `/browse`. A failing TMDB feed drops its row instead of erroring the page.
- **Navigation:** desktop shows logo + "Ratelog" wordmark (both link home), Movies · TV Shows · Browse · Watchlist · Lists · Tags · Community, and an always-visible search field (`/` focuses it). At 900px and below: logo + search + menu sheet on top, and a 5-tab bottom bar with Home leftmost (user preference), then Movies, TV, Watchlist, Lists.
- **Lists get their own tab** (`/lists`), separate from the watchlist: the watchlist is a status ("want to watch") on titles; lists are curated, ordered collections (often imported by Claude).
- **Browse uses plain links for every filter** so the URL describes the view and works without JS; the same "Browse by" bar sits at the top of the home page.
- **One-click bookmark on posters** only toggles Want to watch; titles already Watching/Watched/Dropped show no bookmark so a click can't silently downgrade them.
- **Browse renamed Discover** (user request, 2026-11-23). `/discover` is the canonical URL; `/browse?...` answers `301` to `/discover?...` with the query string unchanged, so old bookmarks keep working. `/api/v1/browse` is unchanged and `/api/v1/discover` is an alias of it. Code names (package `browse`, `BrowseFilters`, `BrowseHandler`) and the bar's "Browse by" label stay: the label reads as an action, as on Letterboxd.
- **Discover's landing is category rows; any filter is the grid.** With nothing chosen but Movies/TV (and the seen setting), Discover shows home-style rows: Popular now, then genres (movies: Horror, Comedy, Action, Animation, Drama, Science Fiction, Thriller, Romance, Crime, Fantasy, Mystery, Family, Documentary; TV uses TMDB's TV genres: Drama, Comedy, Crime, Sci-Fi & Fantasy, Action & Adventure, Mystery, Animation, Documentary, Reality, Family, Kids, War & Politics, Western; News/Talk/Soap are left out because their posters are hosts and logos). Any filter, sort or page opens the poster grid. A row is exactly page 1 of its "More" grid, so both share the TMDB cache. Rows reuse home.css; the landing has no Sort menu (rows have fixed orderings).
- **"Hide titles I've seen" is on by default** (user request). Seen = rated (any season, for TV) or status Watched; Watching, Dropped and Want to watch stay visible. It is a view setting, not a filter: a switch above the bar, no chip, "Clear all" keeps it, and it doesn't turn the landing into the grid. URLs only carry it when off (`hideSeen=0`), so every bar link, row "More", Load more and pending-row fetch keeps the choice. The API keeps its old behavior (seen titles only hidden with `hideSeen=true`) so existing scripts don't change. While seen titles are hidden, the More menu's "Hide titles I've rated" is redundant, so it only appears to switch an old link's setting off.
- **Top up only when hiding removed titles:** fewer than 12 visible after hiding pulls the next TMDB page (cached), up to 3 pages per row or grid page. A page that is simply short, with nothing hidden, costs no extra calls.
- **Discover landing performance:** the first 4 rows are fetched by the server in parallel on a 4-thread pool with a short queue (so one page never runs more than 4 TMDB calls at once), waiting at most 2.5 s. Rows that are slower and rows 5+ render as poster-shaped skeletons that `discover.js` fetches from `/discover/row` as they come within 800px of the viewport, at most 3 at a time. A failing row is dropped (server or browser); only when every server row fails does the page show the TMDB message. Cold cache: 4 TMDB calls before first paint; warm: none. Tunable with `ratelog.discover.eager-rows`, `parallel-rows`, `wait-ms`.
- **The seen switch is a link** (`role="switch"`, `aria-checked`; Space toggles it via JS) to keep the "plain links, URL describes the view" rule. Row bookmarks post in place (fetch) instead of reloading, so rows loaded on scroll aren't lost.
