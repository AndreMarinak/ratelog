# Fork roadmap

Goal: a self-hosted, Letterboxd-style tracker for movies and TV that Claude can fill with researched lists.
Base: [golmenero/ratelog](https://github.com/golmenero/ratelog). Conventions: [CLAUDE.md](CLAUDE.md).

Reserved migration numbers (so parallel work never collides): API tokens `V1000`, quick rating `V1010–1019`,
statuses/watchlist `V1020–1029`, tags `V1030–1039`, diary `V1040–1049`, imports/export `V1050–1059`,
smart lists `V1060–1069`, stats `V1070–1079`, external scores `V1080–1089`, dashboard `V1090–1099`.

## Build now (in suggested order)

- [x] **1. API for Claude**: token auth (`/settings/api`), lists CRUD, add titles by TMDB id or by title + year with strict matching. See [docs/api.md](docs/api.md). No MCP connector.
- [ ] **2. Optional quick rating**: one 0–10 score as an alternative to the 5 category sliders. Rankings must handle both kinds.
- [ ] **3. Watchlist and statuses**: Want to watch, Watching, Watched, Dropped. Works for movies and TV.
- [ ] **4. Custom tags and categories**: user-defined tags (e.g. "comfort watch", "mind-benders"), filterable alongside TMDB genres.
- [ ] **5. Watch diary and history**: log each watch with a date, including rewatches. Timeline/history page.
- [ ] **6. CSV export and more imports**: full export, plus imports from IMDb (ratings/watchlist CSV), Trakt, and a generic CSV format (title, year, tmdb_id, rating, status, date, tags, list).
- [ ] **7. Smart lists**: lists that fill themselves from rules (e.g. "Sci-fi rated 8+", "Watched in 2026", tag = X).
- [ ] **8. Stats page**: hours watched, watches per month, rating distribution, top genres and tags, streaks.
- [ ] **9. External scores and trailers on title pages**: TMDB score and trailers from TMDB; IMDb / Rotten Tomatoes scores (source being researched).
- [ ] **10. Home dashboard**: rows for Watching, Up next (watchlist), Recently watched, Recently added lists.

## Later (todo)

- [ ] Release calendar (upcoming episodes and releases for followed titles, plus an iCal feed)
- [ ] Per-episode progress for TV

## Out of scope

- Claude connector (MCP server)
- Where-to-watch badges (upstream already shows watch providers) and Netflix history import
- Other media types (games, books, music, anime), Plex/Jellyfin syncing
