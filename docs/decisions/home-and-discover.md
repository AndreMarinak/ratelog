# Home, Discover and search

## Standing decisions

- **Search infinite scroll loads the server-rendered page** (`/?q=...&page=N`) and appends its tiles, instead of building tiles in JS from `/api/search`, so later pages carry the same quick actions and badges with no duplicated markup. The marks come from `SearchTileActionsInterceptor` (one query per media type), so the upstream search controller stays untouched.
- **Home is a dashboard of horizontal rows** (Letterboxd-style): trending movies, trending TV, in theaters and coming soon first (user preference), then personal rows (only when non-empty), then Classics and popular TV *(Classics and Popular TV moved to Discover, and "Recommended for you" joined the personal rows: see "Home vs Discover; Recommended for you (2026-11-23)")*; TMDB rows are cached (20 each) with "See all" links into `/discover`. A failing TMDB feed drops its row instead of erroring the page.
- **Discover genre rows are all-time, not "popular now"** (user feedback 2026-11-23: "almost strictly 2026 movies"): each genre row is TMDB's most-voted titles rated 7+, so it spans decades; "Popular now" stays the row for current releases. The "More" grid uses the same filters.
- **Home Classics row**: acclaimed films from before 2000 (TMDB `vote_average.desc`, 4,000+ votes, no documentaries), placed after your own rows; it walks pages 1-3 by ISO week so it changes weekly. *(Superseded 2026-11-23: the same row is now on Discover's Movies landing, see "Home vs Discover; Recommended for you".)*
- **Browse uses plain links for every filter** so the URL describes the view and works without JS (with JS they apply in place
  and replace the URL since 2026-11-23, see "One Back leaves the page"); the same "Browse by" bar sits at the top of the home page. The bar no longer has its own "Find a title" field (2026-11-23): with the header search it put two search boxes on Home (user: "I end up seeing two search boxes").
- **Browse renamed Discover** (user request, 2026-11-23). `/discover` is the canonical URL; `/browse?...` answers `301` to `/discover?...` with the query string unchanged, so old bookmarks keep working. `/api/v1/browse` is unchanged and `/api/v1/discover` is an alias of it. Code names (package `browse`, `BrowseFilters`, `BrowseHandler`) and the bar's "Browse by" label stay: the label reads as an action, as on Letterboxd.
- **Discover's landing is category rows; any filter is the grid.** With nothing chosen but Movies/TV (and the seen setting), Discover shows home-style rows: Popular now, then genres *(since 2026-11-23: Popular now, Recommended for you, Official lists, Classics on Movies, then genres)* (movies: Horror, Comedy, Action, Animation, Drama, Science Fiction, Thriller, Romance, Crime, Fantasy, Mystery, Family, Documentary; TV uses TMDB's TV genres: Drama, Comedy, Crime, Sci-Fi & Fantasy, Action & Adventure, Mystery, Animation, Documentary, Reality, Family, Kids, War & Politics, Western; News/Talk/Soap are left out because their posters are hosts and logos). Any filter, sort or page opens the poster grid. A row is exactly page 1 of its "More" grid, so both share the TMDB cache. Rows reuse home.css; the landing has no Sort menu (rows have fixed orderings).
- **"Hide titles I've seen" is on by default** (user request). Seen = rated (any season, for TV) or status Watched; Watching, Dropped and Want to watch stay visible. It is a view setting, not a filter: a switch above the bar, no chip, "Clear all" keeps it, and it doesn't turn the landing into the grid. URLs only carry it when off (`hideSeen=0`), so every bar link, row "More", Load more and pending-row fetch keeps the choice. The API keeps its old behavior (seen titles only hidden with `hideSeen=true`) so existing scripts don't change. While seen titles are hidden, the More menu's "Hide titles I've rated" is redundant, so it only appears to switch an old link's setting off.
- **Top up only when hiding removed titles:** fewer than 12 visible after hiding pulls the next TMDB page (cached), up to 3 pages per row or grid page. A page that is simply short, with nothing hidden, costs no extra calls.
- **Discover landing performance:** the first 4 rows are fetched by the server in parallel on a 4-thread pool with a short queue (so one page never runs more than 4 TMDB calls at once), waiting at most 2.5 s. Rows that are slower and rows 5+ render as poster-shaped skeletons that `discover.js` fetches from `/discover/row` as they come within 800px of the viewport, at most 3 at a time. A failing row is dropped (server or browser); only when every server row fails does the page show the TMDB message. Cold cache: 4 TMDB calls before first paint; warm: none. Tunable with `ratelog.discover.eager-rows`, `parallel-rows`, `wait-ms`.
- **The seen switch is a link** (`role="switch"`, `aria-checked`; Space toggles it via JS) to keep the "plain links, URL describes the view" rule. Row bookmarks post in place (fetch) instead of reloading, so rows loaded on scroll aren't lost.
- **Search results are a poster grid** reusing the home tiles (`static/search-results.css`, tiles from `home.css`): ~155px tiles at desktop width (`auto-fill, minmax(148px, 1fr)`), 3 per row on phones; title + year under the poster, the overview is no longer shown on tiles. Infinite-scroll pages build the same markup.

## Home vs Discover; Recommended for you (2026-11-23)

The user's decisions (2026-11-23), on splitting Home and Discover: *"Yes split like that but I like how the trending this week
is on homepage at top, same w in theatres this week and coming soon"*; *"Recommended for you on discover is good"*; and on a
"Because you watched X" row: *"not sure that seems same as first one, maybe recommended for you is based on rating"*. So there
is **no "Because you watched X" row**, and **one "Recommended for you" row, based on your ratings**.

- **Home** (in order): Trending movies this week, Trending TV this week, In theaters now, Coming soon (unchanged, still at the
  top), then Continue watching, Up next, **Recommended for you**, Recently rated, Your lists. **Classics and Popular TV left
  Home** (Home now makes 4 TMDB feed calls instead of 6 on a cold cache, none warm). `GET /api/v1/home` follows (no
  `popular_tv` row); the lazy recommended row isn't in it, `GET /api/v1/recommendations` is.
- **Discover landing:** Popular now, **Recommended for you** (in the page's Movies / TV mode), Official lists, then on Movies
  **Classics** (Home's row, moved), then the genre rows. Classics goes right after the curated rows rather than after the 13
  genres, where hardly anyone would reach it (same reasoning as Official lists). It is the same TMDB list as before
  (acclaimed pre-2000 films, rotating weekly through pages 1-3; `DiscoverRowSpec.feed`), with Discover's rules: "Hide titles
  I've seen" applies, tiles show the TMDB star, "More" opens highest rated (`/discover?type=movie&sort=rating`, Home's old
  link). It is one of the 4 rows the server loads first, so the landing still makes at most 4 TMDB calls before first paint.
- **No Popular TV row on Discover:** TV mode's "Popular now" already is TMDB's TV popularity ordering (`/discover/tv`,
  `popularity.desc`), the same titles as Home's old `/tv/popular` row, so a second row would duplicate it.
- **The rule** (`ForYouRule`): seeds are your titles rated **8+**, newest rating first; with fewer than 3 of those, your
  best-rated titles instead; at most **5**. Home mixes movies and TV, and when one type has all five newest, the oldest seed
  makes room for the other type's newest, so both are represented. Each seed's TMDB recommendations
  (`TmdbRecommendationsClient`, page 1: the same cached call as "More like this" on its title page) are merged
  **round-robin** (1st of every seed, then 2nd...), so one seed can't fill the row; a title recommended twice appears once.
  Posterless titles, released titles under 10 TMDB votes and the seeds are dropped.
- **Anything you've rated or given a status is left out, watchlist included.** The row's job is discovery: watchlist titles
  are already in "Up next" right above it on Home, Watching in "Continue watching", and Watched / Dropped are seen or
  rejected. Showing them with a filled bookmark would spend tiles on things you already know about. Bookmarking a tile in the
  row keeps it there (filled) until the next load.
- **No ratings, no row** (rather than a "Rate a few titles to get recommendations" prompt): Home already shows a welcome note
  to new users, and a prompt row in a page of posters looks like an error. Home only adds the row when "Recently rated" has
  something (no extra query) and TMDB is configured; Discover asks one `EXISTS` query for the mode's type
  (`DiscoverForYouAdvice`, lazy, so the grid and `/discover/row` fetches run none). If every recommendation turns out seen,
  the row removes itself on load.
- **Loaded lazily, so Home and Discover don't get slower:** the page renders the heading and 8 skeleton posters (the final
  height), and fetches `/for-you/row` (`?type=movie|tv` on Discover) once it comes within 800px of the viewport; the answer is
  Discover's `lazyRow` fragment, so the tiles and quick actions (`tile-actions`, the "⋯" chooser on touch) are the same as
  every other row. On Discover, `discover.js` loads it like its other pending rows (also after a Movies / TV swap); on Home a
  small `for-you.js` does. Cold: up to 5 TMDB calls in parallel on a 5-thread pool; the merged list is then cached per user,
  type and language for 5 minutes (`ratelog.recommendations.for-you-cache-minutes`), while your marks are read fresh every
  time (one query per type), so a title you rate elsewhere drops out on the next load. A partial answer (a seed failed) isn't
  cached.
- **No "More" link:** a grid of more recommendations would need its own page and paging over a merged list; the API's `limit`
  (up to 60) covers "more" for Claude. The row's tiles show the TMDB star and year like Discover's; there is no film / TV type
  badge on Home's mixed row (the posters and the title page make it clear).

## Discover: several streaming services; the Year menu stays open on a decade (2026-11-24)

Asked by the user on 2026-11-24: "I can only select one streaming service at a time, should be multi select, and the filter
shouldn't list all of them, should show like netflix... or something like that", and about the Year menu: "I never see the
popup it kinda glitches real quick … I'd have to click back on year to then see those popup".

- **Services are multi-select, OR'ed:** `provider=8|15|1899` (TMDB's own OR syntax, `with_watch_providers=8|15|1899`, region
  and `flatrate` as before) finds titles streaming on any of them. Ids are kept distinct and sorted, so the same choice is
  always the same URL and cache entry. Old links keep working: `provider=8` (one id), `provider=8,15` and a repeated
  `provider=8&provider=15` (Spring joins it as `8,15`) all parse; at most 30 ids. The API returns `providers` (all) next to
  `provider` (the first, for older clients).
- **The menu opens on the big US services, with logos, in the user's order:** Netflix, Prime Video, Disney+, HBO Max, Hulu,
  Apple TV+, Peacock, Paramount+ (`BrowseBarViews.FEATURED_SERVICES`). Ids were checked against TMDB's
  `/watch/providers/movie?watch_region=US` on 2026-11-24: Netflix 8, Prime Video 9, Disney+ 337, HBO Max 1899 (the old 384 and
  531 ids are gone from the US list), Hulu 15, Apple TV+ 350 (TMDB now calls it "Apple TV"; the store is 2). **Paramount+ and
  Peacock are two ids each** (Premium 2303 + Essential 2616; Peacock Premium 386 + Premium Plus 387): TMDB lists each plan
  separately and one plan alone misses titles (Paramount+ Premium alone: 1,192 movies, both plans: 1,387), so one row ticks
  both. Netflix's and Prime's ad plans (1796, 2100) were left out: same catalogue (+0.5%) and a longer URL. Other regions show
  the featured services TMDB lists there, topped up to 8 with the region's best-ranked services.
- **Checkbox rows that apply in place and keep the menu open** (each row is still a plain link that toggles its ids, so it
  works without JS). The trigger and the chip read "Netflix" or "Netflix + 2" (rows, not TMDB ids); the chip's ✕ and "Any
  service" clear them all.
- **"Show all services (N)"** is a `<details>` under the featured rows with the rest of the region's ~280 services in TMDB's
  priority order and a "Find a service" box (shown by `browse-bar.js`; without JS the list just opens). It opens by itself
  when it holds a ticked service. The box keeps its text when the menu comes back after a pick. The ticks are CSS (no icon per
  row) and logos load lazily, so the long list stays cheap. On phones rows are 44px, the full list is one column, and the featured rows' checkbox sits on the logo's corner so names like "Prime Video" aren't cut at 360px.
- **Picking a decade keeps the Year menu open on its years** (the glitch: the in-place swap redrew the bar and closed the
  menu, so the "Years in the 2020s" row only showed on a second tap). Options can carry `data-keep-open` (a decade, a service):
  `view-swap.js` keeps that menu open while loading, re-opens it on the new bar, focuses the same option (`data-option`),
  scrolls the years into view (`data-reveal`) and keeps an open "Show all" open. Picks made while it loads are queued and
  replayed on the new menu, so ticking two services quickly keeps both. A year, "This year" and the other menus still close
  the menu as before; Escape and an outside tap close it.
- **"This year" / "Last year"** head the Year menu (with the year as a hint), then "Upcoming" (movies only), then the decades
  under a "Decades" heading. They are single-year filters and drop a decade they aren't in.
- **The search page uses this Year menu too** (2026-11-24): same builder (`BrowseBarViews.yearMenu`, which now takes a link
  builder), fragment (`browseMenu`), CSS and script, without Upcoming and with a "Type a year" box; see
  [people-and-credits.md](people-and-credits.md#search-year-menu-2026-11-24).
- **No unstyled flash after a swap:** the bar brings its own `<link rel="stylesheet">`, and static files are served `no-store`,
  so every swap re-downloaded `browse-bar.css` and showed the bar unstyled for ~0.5 s (also part of the "glitch"). Region
  stylesheets are now kept loaded in `<head>` (`view-swap.js`). On phones an open panel also ends above the fixed bottom nav
  (`browse-bar.js` sizes it; it used to run under the nav, so its last rows couldn't be tapped).
