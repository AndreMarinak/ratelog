# Fork roadmap

Goal: a self-hosted, Letterboxd-style tracker for movies and TV that Claude can fill with researched lists.
Base: [golmenero/ratelog](https://github.com/golmenero/ratelog). Conventions: [CLAUDE.md](../CLAUDE.md).

Reserved migration numbers (so parallel work never collides): API tokens `V1000`, quick rating `V1010–1019`,
statuses/watchlist `V1020–1029`, tags `V1030–1039`, diary `V1040–1049`, imports/export `V1050–1059`,
smart lists `V1060–1069`, stats `V1070–1079`, external scores `V1080–1089`, dashboard `V1090–1099`,
lists (official lists and later list features) `V1120–1129` (`V1120` official lists, `V1121` list options),
accounts and roles `V1130–1139`, TV whole-show rating `V1140` (`tv_ratings.show_score`).

## Build now (in suggested order)

- [x] **1. API for Claude**: token auth (`/settings/api`), lists CRUD, add titles by TMDB id or by title + year with strict matching. See [docs/api.md](api.md). No MCP connector.
- [x] **2. Optional quick rating**: one 0–10 score as an alternative to the 5 category sliders. Rankings must handle both kinds. API: `/api/v1/ratings` ([docs/api.md](api.md#ratings)).
- [x] **3. Watchlist and statuses**: Want to watch, Watching, Watched, Dropped on every title; `/watchlist` page; rating auto-sets Watched. API: `/api/v1/statuses`. Simplified in the UI by #16.
- [x] **4. Custom tags and categories**: user-defined tags with colors on title pages, `/tags` management, tag filter on Top pages, tags during list import. API: `/api/v1/tags`.
- [ ] **5. Watch diary and history**: log each watch with a date, including rewatches. Timeline/history page.
- [ ] **6. CSV export and more imports**: full export, plus imports from IMDb (ratings/watchlist CSV), Trakt, and a generic CSV format (title, year, tmdb_id, rating, status, date, tags, list).
- [ ] **7. Smart lists**: lists that fill themselves from rules (e.g. "Sci-fi rated 8+", "Watched in 2026", tag = X).
- [ ] **8. Stats page**: hours watched, watches per month, rating distribution, top genres and tags, streaks.
  **Mostly covered (2026-11-23, #31):** `/stats` per year. Still open: tags, streaks (needs the diary, #5), TV hours.
- [x] **9. External scores and trailers on title pages**: TMDB score, runtime and click-to-load trailers from TMDB; IMDb / Rotten Tomatoes / Metacritic / Letterboxd scores via MDBList (optional `MDBLIST_API_KEY`), cached in `title_extras`. Also `GET /api/v1/titles/{type}/{tmdbId}`.
- [x] **10. Home dashboard + Lists tab + Browse**: scrollable rows (Continue watching, Up next, Recently rated, Your lists, Trending movies/TV, In theaters, Coming soon, Popular TV; Popular TV moved to Discover in 29) with one-click watchlist bookmarks; Lists tab (`/lists`: mine, people I follow, other public lists); sticky header search (`/` shortcut) and "Ratelog" wordmark; `/browse` with the Letterboxd-style "Browse by" bar (also on the home page). API: `/api/v1/home`, `/api/v1/browse`.
- [ ] **11. Finish the series** (home row): for movies you've watched/rated that belong to a TMDB collection (e.g. Toy Story),
  show the entries you haven't seen yet, in release order ("You've seen Toy Story and Toy Story 2 -> next: Toy Story 3").
  Optional TV counterpart: "New season of a show you've watched".
  **Partly covered (2026-11-23, #17):** every movie page in a collection shows the whole series with your Seen marks and
  "You've seen 2 of 5 · Next: Toy Story 3". Still open: the home row that gathers series you've started.
- [x] **12. List progress**: on `/list/{id}` (yours or someone's public list) each poster shows its rank, your status and your score;
  "You've seen 9 of 25" with a bar; All · Not seen · Seen tabs (`?show=unseen|seen`, ranks never renumber); "Add the N unseen to my watchlist"
  (only titles with no status). "9/25 seen" on `/lists` cards, a back link above the title. API: `status`/`score`/`seen` per item and
  `progress` on `GET /api/v1/lists/{id}` ([docs/api.md](api.md#list-progress)).
- [ ] **13. Poster hover card**: hovering (or long-pressing on phones) a poster anywhere shows a small card with the year, basic
  scores (TMDB / IMDb, your rating) and quick actions: add to watchlist, add to a list. Requested 2026-11-23. First step done in #16:
  every poster has Seen and Watchlist toggles (always visible on touch); #18 added your rating, ★ and Add to list on every poster.
- [x] **14. Discover** (was Browse): `/discover` with category rows (Popular now + genres, movies and TV) when no filter is set,
  the poster grid otherwise, and a "Hide titles I've seen" switch that is on by default. `/browse` redirects; API alias `/api/v1/discover`.
  No migration needed (`V1100–1109` stay reserved for browse/discover). Requested 2026-11-23.
- [x] **15. Official lists** (Letterboxd-style): admins mark any list official (switch under the list title, or `isOfficial`
  in the API); public official lists are shown to every account in an "Official lists" row at the top of the Lists tab,
  a row on the Discover landing right after Popular now, and `/lists/official`, each with the viewer's own "9/25 seen".
  API: `isOfficial` on lists, `GET /api/v1/lists/official`, any token can read a public official list ([docs/api.md](api.md#official-lists)).
  Table `official_lists` (`V1120`). Requested 2026-11-23 for the admin's 26 researched lists. Since 2026-11-23 (later request) an
  official list can be private = **hidden** ("Official · Hidden", its owner's only) until made public; marking never changes visibility.
- [x] **16. Seen and Watchlist, Letterboxd-simple** (requested 2026-11-23): title pages have four equal toggles (Seen, Watchlist,
  Rate, Add to list; shows add a small Watching / Dropped control); the follow bell only where it does something; "Rate it?" after
  Seen on unrated movies (optional). Poster tiles everywhere get a Seen eye beside the bookmark. `/watchlist` tabs read To watch ·
  Watching · Seen · Dropped, with Seen / Watching / Remove (Undo) on every tile. Lists get "Mark what you've seen" tick-off mode.
  "Upcoming · Dec 18" badges on tiles and title pages. Rating a show's season makes it Watching, Watched once every aired season
  is rated. All in place, no reloads. No migration (data model and API unchanged).
- [x] **17. In your lists** (requested 2026-11-23): the title page's add-to-list sheet ticks the lists that already have the
  title ("In 2 of your lists", its place as "#3"), lists with it first; a tap adds it or takes it out (removing asks first,
  naming its rank) and the sheet stays open. The title page button reads "In 2 lists" once it's in any. "New list" moved to
  the top of the sheet. All in place. API: `inLists` on `GET /api/v1/titles/{type}/{tmdbId}` ([docs/api.md](api.md#title-details-scores-and-trailers)).
  No migration.
- [x] **18. Series and "More like this" on title pages** (requested 2026-11-23): movies in a TMDB collection get "Part of the Toy
  Story Collection" (every entry in release order, "You're here", your Seen marks, "You've seen 2 of 5 · Next: Toy Story 3"); movies
  and shows get "More like this" (TMDB recommendations, seen titles hidden). Loaded lazily under the page, TMDB cached (a day; a week
  for collections), quick actions in place. API: `GET /api/v1/titles/{type}/{tmdbId}/recommendations`
  ([docs/api.md](api.md#recommendations)). No migration.
- [x] **19. List order**: every list has an editable order. Owners get "Edit order" on `/list/{id}` (`?reorder=1`): the list becomes thin
  rows (grip, rank, poster, title) to drag up or down (mouse anywhere on the row, touch by the grip, auto-scroll at the edges),
  or type a new number, or use top / up / down / bottom; every move saves in place
  with a toast, and the forms work without JS. A "Numbered list" setting (create / edit list modals, default on) shows ranks;
  plain lists are collections without numbers. Everyone gets a "Sort" menu (list order, reverse, title, newest / oldest release,
  your rating, recently added) that never changes the saved order. API: `numbered` on lists, `position` when adding items,
  `PATCH /api/v1/lists/{id}/items/{type}/{tmdbId}` and `PUT /api/v1/lists/{id}/order` ([docs/api.md](api.md#list-order)).
  Table `custom_list_options` (`V1121`). Requested 2026-11-23 ("are lists able to have some more order?").
- [x] **20. Rate and add to list from every poster** (requested 2026-11-23): every poster (home, Discover, search, lists, the
  Ratings tab, the watchlist, title pages) gets Add to list and a ★ in its bottom-right corner, beside the bookmark and eye. ★ on a
  movie opens a 1-10 quick-rating picker (popover / phone bottom sheet) that saves in place; once rated the poster always shows
  "★ 8.5" and the picker edits it (a detailed rating opens the title page's edit modal instead, never flattened); shows go to
  their seasons. Add to list opens the title page's sheet in place. "Rate it?" after Seen on any poster and in tick-off mode
  (optional, can be turned off). Ratings tab: a "To rate" view of titles seen but not rated (`/ratings/unrated`). Watchlist
  buttons line up the same way on every tile (Watching stays for shows). No migration; no API change.
- [x] **21. Manager role** (requested 2026-11-23): a user who can also add plain user accounts (`/accounts/new`, "Add account" in
  the header and the phone menu). Admins give or take the role in the admin page. No migration needed (`V1130–1139` reserved for
  accounts and roles). `GET /api/v1/me` reports `MANAGER`.
- [x] **Security hardening** (2026-11-23): role, deletion and password changes apply to open sessions; sign-in throttling;
  8-character minimum for new passwords; request rate limit (`429`); Letterboxd import limits (2 MB, 10,000 rows, background);
  Secure / SameSite cookies, trusted proxy headers only, CSP and other browser headers; masked TMDB key in the admin page;
  same-site-only redirects; no secrets in logs; non-root container; patched Tomcat, pgjdbc, Jackson, Logback, Thymeleaf,
  Hibernate Validator. Details in [design-decisions.md](design-decisions.md#security-hardening-2026-11-23).
- [x] **22. Back links on title pages** (requested 2026-11-23): movie and TV pages show "← Discover" / "← Search results" /
  "← Best Disney Movies" / "← Watchlist"... above the title, back to the page you opened it from with its URL and scroll as you
  left them. Title-to-title taps keep the first page as the target (no growing trail), plus one step to the title right before
  ("← Discover › Toy Story 3"). Opened directly: "← Home". Works without JS (from the Referer). No migration, no API change.
- [x] **23. Suggested for this list** (requested 2026-11-23, "like Spotify's Recommended songs under a playlist"): your own
  list pages get a lazy "Suggested for this list" row under the list: poster tiles from the TMDB recommendations of up to 10 of
  the list's titles (scored by how many recommend each and how high, weighted by TMDB popularity and rating), a franchise's
  missing entries first, the list's own movie / show mix, titles already in it left out. "+ Add" saves in place (the tile turns
  "Added", the list grows with the new rank, toast), "More suggestions" gives a new batch, "Not interested" hides one for the
  session. Not on other people's lists, empty lists, Edit order or tick-off. API: `GET /api/v1/lists/{id}/suggestions`
  ([docs/api.md](api.md#list-suggestions)). No migration.
- [x] **26. Community People and clear Follow buttons** (requested 2026-11-23): Community lists every other account (A-Z, initial,
  rating count, profile link) with a Follow button, beside the Activity feed (People first on phones); "Following 2 of 5".
  Follow buttons on Community and profiles are labelled: "+ Follow" / "✓ Following" (reads "Unfollow" on hover; touch screens
  ask before unfollowing), same size in every state, in place, and they post the state they show (`POST /user/follow/set`).
  User search is case-insensitive. Admins are listed like everyone else. No migration, no API change.
- [x] **29. Home vs Discover; Recommended for you** (decided 2026-11-23: keep trending / in theaters / coming soon on top of
  Home, "Recommended for you on discover is good", no "Because you watched X", "recommended for you is based on rating"): one
  lazy "Recommended for you" row on Home (movies + TV, after Up next) and Discover (the Movies / TV mode, after Popular now),
  from TMDB recommendations of your titles rated 8+ (else your best-rated; up to 5, merged round-robin), never anything you've
  rated or given a status. Classics and Popular TV left Home; Classics is a Discover Movies row (TV's Popular now already is
  Popular TV). API: `GET /api/v1/recommendations` ([docs/api.md](api.md#recommended-for-you)). No migration. Details in
  [design-decisions.md](design-decisions.md#home-vs-discover-recommended-for-you-2026-11-23).
- [x] **30. Ratings load as you scroll** (requested 2026-11-23, "can we just have it load more as u scroll?"): the Movies and TV
  rankings show 30, then the next 30 as you near the end (ranks continue, filters apply to every page, "That's all 45" at the
  end), with a "Show more" link as the fallback (works without JS). No history entry per page; a reload or Back shows what you
  had loaded. The count select is gone. The To rate tabs also switch in place now. No migration, no API change.
- [x] **31. Stats: your year in movies** (picked 2026-11-23 from the later-features list: "go with the stats"): `/stats`
  (profile, phone menu sheet, desktop header icon) for your own ratings, with a year switcher (this year, earlier years with
  data, All time; it switches in place and replaces the URL). Movies, TV shows (and seasons rated), ratings, average score,
  hours watched (movies only, from the cached runtimes, looked up in the background: "based on N of M movies" until
  complete), a 1-10 rating distribution, month by month (year by year for All time) split into movies and TV, top genres
  with your average (plus most generous / harshest genre), release decades, your 10 highest rated as posters, and an empty
  state. API: `GET /api/v1/stats?year=YYYY|all` ([docs/api.md](api.md#stats)). No migration. Follow-ups: other people's
  stats, TV hours (episode counts), tags, streaks. Details in
  [design-decisions.md](design-decisions.md#stats-your-year-in-movies-2026-11-23).
- [x] **32. People: person pages and People in search** (asked 2026-11-24: "am I able to click on a name like tom hanks and
  see his movies by search"): `/person/{id}` with photo, known for, dates, a short bio (More), and the filmography as poster
  tiles with quick actions: department tabs (Acting, Directing, ...), All / Movies / TV, Newest / Most popular, applied in
  place. Search shows a People row above the titles (Any type), and a Year box next to Any / Movies / TV. One cached TMDB
  request per person. API: `GET /api/v1/people/{id}`, `people=true` on `GET /api/v1/search`
  ([docs/api.md](api.md#people)). No migration. Title-page credits link here when `ratelog.features.people` is on. Details in
  [design-decisions.md](design-decisions.md#people-person-pages-and-people-in-search-2026-11-24).

## Fixes and housekeeping done

- [x] **Movie ratings save in place** (asked 2026-11-24, "also does editing the rating when clicked on movie cause a refresh?"): the movie page's Rate modal
  (quick / detailed, create / edit) and the delete confirmation no longer reload the page; toast, "Your rating" and the action
  panel update in place, like the TV seasons. No migration, no API change.
- [x] **Watchlist opens on Movies; Discover's Official lists follow Movies / TV** (requested 2026-11-23, "watchlist tab should
  default to movies like everything else"; TV mode shows "only lists with TV"): `/watchlist` lands on Movies with a Movies | TV
  switch (no All; a bare Watching link opens on TV), and Discover's Official lists row only shows lists with a movie (Movies) or
  a show (TV), no row when none. No migration, no API change.

- [x] **MDBList key on Admin → Configuration** (requested 2026-11-23): paste the scores key like the TMDB key (masked, works at
  once, wins over `MDBLIST_API_KEY`). See [design-decisions](design-decisions.md).
- [x] **24. "All seen" in list tick-off mode** (requested 2026-11-23): one tap (after a confirmation) marks every unseen,
  released title in the list as seen, with the same rules as the poster eye. No migration.
- [x] **25. Poster actions that don't cover the poster** (requested 2026-11-23, "these buttons cover a lot of the tiles"): at
  rest a poster only shows the Seen eye (when seen) and "★ 8.5" (when rated), small, bottom right. Phones and tablets get one
  "⋯" button per poster that opens a chooser (Watchlist, Seen, Rate, Add to list; a bottom sheet on phones); with a mouse the
  four quick buttons appear on hover / keyboard focus. The Seen green is a quiet sage everywhere. No server change.
- [x] **27. Compact list header** (requested 2026-11-23, "there's a lot here and it takes up a lot of space and looks
  messy/unorganized w all the buttons"): the list page's title gets one "⋯" List actions menu (Add N unseen to watchlist, Edit
  order, Edit list, Make / Remove official, Delete list), a small meta line (Official, Public, "You've seen 3 of 14" + slim bar),
  and one toolbar row (All / Not seen / Seen, Sort, Mark what you've seen). Header 301 → 169px at 1440, 483 → 227px on phones.
  The bulk watchlist add now saves in place with a toast. No migration, no server change.
- [x] **28. TV: rate a show as a whole; season ratings save in place** (requested 2026-11-23: "on a tv show rating when im
  rating mult seasons it reloads the page after each rating", "I should be able to rate show as a whole and then if u rate any
  season at all then the overall rating becomes the average"). Rating, editing and deleting a season no longer reloads the TV
  page (modal closes, toast, seasons and panel update). A show can get one whole-show score (TV page "Rate" / "Whole show"
  card, or a poster's ★ / "⋯"); once any season is rated the show's score is their average and the whole-show score is kept
  unused (it comes back if every season rating is deleted). Rated means seen (a whole-show score marks it Watched). Everywhere
  a show's score shows follows the rule. API: `PUT` / `DELETE /api/v1/ratings/tv/{tmdbId}`, `scoreSource` and `showScore` on
  `TvRating`. Migration `V1140`. Details in [design-decisions.md](design-decisions.md#tv-rate-a-show-as-a-whole-or-by-season-seasons-save-in-place-2026-11-23).
- Railway deploy (`railway.toml`, `docs/deploy-railway.md`), `ADMIN_PASSWORD` bootstrap, `TMDB_API_KEY` env var always wins, readable TMDB error page, https redirects behind the proxy.
- Removed upstream's Buy Me a Coffee button/script; AGPL source link points to this fork; brighter home posters.
- Sharper images everywhere (size-matched TMDB images + 2x `srcset`); Home is the leftmost tab on the phone's bottom bar.
- 2026-11-23 batch: one Ratings tab (Movies | TV Shows switch), Browse renamed Discover, title page redesign (desktop three columns,
  phone action bar), small actions save in place (no reload), Classics home row, tags hidden behind a feature flag, compact
  Discover bar on phones, no white overscroll. Details in [design-decisions.md](design-decisions.md).
- 2026-11-23 small fixes: Ratings filters apply on change with an ✕ per filter (in place, URL + Back), no full-screen loader
  (a thin top bar after 400 ms), a Rotten Tomatoes search link next to IMDb's on title pages, hidden (private) official lists.
  No migration.
- 2026-11-23 smoother phone navigation: filters replace, navigation pushes (one Back leaves Ratings, a list, Discover or the
  watchlist); Discover's filters, the list Sort menu and the watchlist tabs apply in place; coming back to Discover's grid or
  search results brings back the pages you had loaded and the scroll position. No migration, no server change.

Gap research against Letterboxd, the TV trackers and the discovery apps (ranked): [competitor-gaps.md](competitor-gaps.md).

## Later (todo)

- [ ] Release calendar (upcoming episodes and releases for followed titles, plus an iCal feed)
- [ ] **Spring Boot 4 migration** (Spring Framework 7, Spring Security 7, Jackson 3): Boot 3.5's free support ended 2026-06-30,
  and newer Spring fixes ship only in Boot 4. Target 4.1.x or 4.2.x (4.0's free support ends 2026-12-31). Then drop the
  version overrides in `pom.xml` (design-decisions.md, "Pinned dependency versions").
- [ ] **Strict CSP:** move the inline `<script>` blocks and `on*=` handlers into the static `.js` files, then drop
  `'unsafe-inline'` from `script-src` (`security/SecurityHeaders.kt`).
- [ ] Dependabot or Renovate for Maven and the Docker images, so patch releases arrive as pull requests.
- [ ] Send the TMDB key as a header (v4 "API Read Access Token" as `Authorization: Bearer`) instead of in the URL.
- [ ] Per-episode progress for TV

## Out of scope

- Claude connector (MCP server)
- Where-to-watch badges (upstream already shows watch providers) and Netflix history import
- Other media types (games, books, music, anime), Plex/Jellyfin syncing
