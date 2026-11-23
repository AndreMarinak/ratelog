# Fork roadmap

Goal: a self-hosted, Letterboxd-style tracker for movies and TV that Claude can fill with researched lists.
Base: [golmenero/ratelog](https://github.com/golmenero/ratelog). Conventions: [CLAUDE.md](../CLAUDE.md).

Reserved migration numbers (so parallel work never collides): API tokens `V1000`, quick rating `V1010–1019`,
statuses/watchlist `V1020–1029`, tags `V1030–1039`, diary `V1040–1049`, imports/export `V1050–1059`,
smart lists `V1060–1069`, stats `V1070–1079`, external scores `V1080–1089`, dashboard `V1090–1099`.

## Build now (in suggested order)

- [x] **1. API for Claude**: token auth (`/settings/api`), lists CRUD, add titles by TMDB id or by title + year with strict matching. See [docs/api.md](api.md). No MCP connector.
- [x] **2. Optional quick rating**: one 0–10 score as an alternative to the 5 category sliders. Rankings must handle both kinds. API: `/api/v1/ratings` ([docs/api.md](api.md#ratings)).
- [x] **3. Watchlist and statuses**: Want to watch, Watching, Watched, Dropped on every title; `/watchlist` page; rating auto-sets Watched. API: `/api/v1/statuses`.
- [x] **4. Custom tags and categories**: user-defined tags with colors on title pages, `/tags` management, tag filter on Top pages, tags during list import. API: `/api/v1/tags`.
- [ ] **5. Watch diary and history**: log each watch with a date, including rewatches. Timeline/history page.
- [ ] **6. CSV export and more imports**: full export, plus imports from IMDb (ratings/watchlist CSV), Trakt, and a generic CSV format (title, year, tmdb_id, rating, status, date, tags, list).
- [ ] **7. Smart lists**: lists that fill themselves from rules (e.g. "Sci-fi rated 8+", "Watched in 2026", tag = X).
- [ ] **8. Stats page**: hours watched, watches per month, rating distribution, top genres and tags, streaks.
- [x] **9. External scores and trailers on title pages**: TMDB score, runtime and click-to-load trailers from TMDB; IMDb / Rotten Tomatoes / Metacritic / Letterboxd scores via MDBList (optional `MDBLIST_API_KEY`), cached in `title_extras`. Also `GET /api/v1/titles/{type}/{tmdbId}`.
- [x] **10. Home dashboard + Lists tab + Browse**: scrollable rows (Continue watching, Up next, Recently rated, Your lists, Trending movies/TV, In theaters, Coming soon, Popular TV) with one-click watchlist bookmarks; Lists tab (`/lists`: mine, people I follow, other public lists); sticky header search (`/` shortcut) and "Ratelog" wordmark; `/browse` with the Letterboxd-style "Browse by" bar (also on the home page). API: `/api/v1/home`, `/api/v1/browse`.
- [ ] **11. Finish the series** (home row): for movies you've watched/rated that belong to a TMDB collection (e.g. Toy Story),
  show the entries you haven't seen yet, in release order ("You've seen Toy Story and Toy Story 2 -> next: Toy Story 3").
  Optional TV counterpart: "New season of a show you've watched".
- [x] **12. List progress**: on `/list/{id}` (yours or someone's public list) each poster shows its rank, your status and your score;
  "You've seen 9 of 25" with a bar; All · Not seen · Seen tabs (`?show=unseen|seen`, ranks never renumber); "Add the N unseen to my watchlist"
  (only titles with no status). "9/25 seen" on `/lists` cards, a back link above the title. API: `status`/`score`/`seen` per item and
  `progress` on `GET /api/v1/lists/{id}` ([docs/api.md](api.md#list-progress)).
- [ ] **13. Poster hover card**: hovering (or long-pressing on phones) a poster anywhere shows a small card with the year, basic
  scores (TMDB / IMDb, your rating) and quick actions: add to watchlist, add to a list. Requested 2026-11-23.

## Fixes and housekeeping done

- Railway deploy (`railway.toml`, `docs/deploy-railway.md`), `ADMIN_PASSWORD` bootstrap, `TMDB_API_KEY` env var always wins, readable TMDB error page, https redirects behind the proxy.
- Removed upstream's Buy Me a Coffee button/script; AGPL source link points to this fork; brighter home posters.
- Sharper images everywhere (size-matched TMDB images + 2x `srcset`); Home is the leftmost tab on the phone's bottom bar.

## Later (todo)

- [ ] Release calendar (upcoming episodes and releases for followed titles, plus an iCal feed)
- [ ] Per-episode progress for TV

## Out of scope

- Claude connector (MCP server)
- Where-to-watch badges (upstream already shows watch providers) and Netflix history import
- Other media types (games, books, music, anime), Plex/Jellyfin syncing
