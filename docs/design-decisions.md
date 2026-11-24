# Design decisions

Why this fork is built the way it is. Newest decisions go at the bottom of each section. Related: [roadmap.md](roadmap.md), [bugs-and-issues.md](bugs-and-issues.md), [api.md](api.md).

The decisions live in topic files under `docs/decisions/`. Read this index, then open only the topic files your change touches.

## Topics

- [Product, conventions and deploy](decisions/platform-and-deploy.md): why this is a Ratelog fork, migration numbering (`V1000+`), worktrees and tests, and the Railway deployment settings.
- [API and external data](decisions/api-and-external-data.md): the `/api/v1` security chain, tokens, batch import and title matching, plus the MDBList scores and the Rotten Tomatoes link.
- [Security and limits](decisions/security-and-limits.md): the 2026-11-23 hardening pass (sessions, sign-in throttling, rate limits, import limits, headers, cookies, pinned dependencies).
- [Ratings](decisions/ratings.md): quick ratings, tags, the Ratings tab and its filters, TV ratings by season, movie ratings saving in place, and ratings loading as you scroll.
- [Watchlist and Seen](decisions/watchlist-and-seen.md): Seen versus Watchlist, TV auto-status, upcoming titles, the follow bell, the Watchlist page, rewatching, and the rewatch log (Watched again).
- [Posters and tiles](decisions/posters-and-tiles.md): quick actions on every poster, rate and add to list from a tile, "Rate it?" after Seen, poster sizes and badges.
- [Lists](decisions/lists.md): the add-to-list sheet, list progress and filters, tick-off mode, the Lists tab, the compact list header, and "Suggested for this list".
- [Official and saved lists](decisions/official-and-saved-lists.md): official lists (table, visibility, admin switch, ordering, Discover row) and saving an official list.
- [Home, Discover and search](decisions/home-and-discover.md): the Home dashboard and "Recommended for you", Discover rows, filters and streaming services, search results and infinite scroll.
- [Title pages](decisions/title-pages.md): desktop and phone layouts, trailers, where to watch, series and "More like this", and back links.
- [People and credits](decisions/people-and-credits.md): director, writers, cast, age rating and tagline on title pages, person pages and People in search.
- [Community and accounts](decisions/community-and-accounts.md): the People page, Follow buttons and the Manager role.
- [Stats](decisions/stats.md): the "your year in movies" page and its API.
- [Navigation, UI shell and page reloads](decisions/navigation-and-reloads.md): "one Back leaves the page", the audit table of what saves in place versus reloads, header and navigation, modals and sheets.

## How to add a decision

1. Append it to the matching topic file above, under its own `##` heading with the date, for example `## Rewatch: the watchlist is independent of Seen (2026-11-24)`. Newest goes at the bottom.
2. Quote the user's request (with the date), then give the data and the trade-off: **why**, not only what.
3. If no topic file fits, create `docs/decisions/<topic>.md` (one `#` title, aim for 50 to 200 lines) and add a one-line entry to the list above.
4. If you add an action that saves in place or reloads, also add a row to the audit table in [navigation-and-reloads.md](decisions/navigation-and-reloads.md#when-the-page-reloads-audit-2026-11-23).
5. Link to a decision with the file and the heading anchor, for example `decisions/stats.md#stats-your-year-in-movies-2026-11-23`. Run `python tools/check-doc-links.py` before you commit; it must report 0 broken links.
