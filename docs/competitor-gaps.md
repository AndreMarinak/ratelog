# What other trackers have that this app doesn't (Nov 2026)

Three research passes on 2026-11-23 compared this fork against:
- Letterboxd;
- the TV trackers: Serializd, Trakt, TV Time, Simkl, Sequel and Hobi;
- the discovery apps: JustWatch, Reelgood, IMDb, Plex Discover, Mubi, Criticker, Taste.io, Likewise, Kinorium and MovieLens.

Value is for this user: a solo user who watches on their own streaming services and gets lists curated by Claude. Effort is S/M/L.
Related: [roadmap.md](roadmap.md), [design-decisions.md](design-decisions.md).

## Ranked gaps (merged across the three reports)

| # | Gap | Who has it | Value | Effort | Data source / note |
|---|---|---|---|---|---|
| 1 | **"More like this"** row on title pages | Letterboxd, IMDb, Reelgood, MovieLens | H | S | TMDB `/{type}/{id}/recommendations` (+ `/similar`); hide seen titles |
| 2 | **Cast & crew, person pages, people in search** (filmography with "seen 7 of 23") | Letterboxd, IMDb, Kinorium | H | M | TMDB `/credits`, `/person/{id}/combined_credits`, discover `with_cast` / `with_crew` |
| 3 | **List notes and description**, a ranked on/off toggle, list sort and filter (list order, rating, year, shuffle; fade watched) | Letterboxd | H | S–M | Small schema change; the API should accept per-entry notes so Claude can say why a title is on the list. Seen filter and progress are in progress (#12). |
| 4 | **Saved "my services"**, then a **"Now on your services" watchlist row** and alerts | JustWatch, Plex, Letterboxd Pro, Reelgood | H | S–M | Daily TMDB `/watch/providers` diff for watchlist titles; discover `with_watch_providers` (`a\|b` = OR) |
| 5 | **Per-episode progress**, which unlocks a real TV "Up next" (next unwatched episode), an episode calendar + iCal, and time-spent stats | Trakt, TV Time, Serializd, Simkl, Sequel | H | L | TMDB season/episode endpoints. Today "Up next" is only Want to watch titles and "Continue watching" is only status Watching. |
| 6 | **Diary with rewatches and likes** (a like separate from rating; history kept on rewatch) | Letterboxd, Serializd, Trakt | H | M | Roadmap #5. If likes get a heart, "follow" should move to a bell icon. |
| 7 | **"Because you liked X"** home row | IMDb, Likewise, Taste | H | S | TMDB recommendations seeded from your 8+ ratings, minus titles seen or on the watchlist |
| 8 | **"Surprise me" picker**: random pick from the watchlist, filtered by service, runtime and genre | Reelgood Roulette, Kinorium | H | S | Own data plus the existing Browse filters |
| 9 | **Title page polish**: backdrop header, details (studio, country, language), release dates by type (the digital date tells you when it can stream) | Letterboxd | M | S | TMDB `/release_dates`, `/images` |
| 10 | **Stats + Year in review** (hours, top directors/actors, genres, streaks) | Letterboxd Pro, Trakt, Serializd, TV Time | H | M | Roadmap #8; best after the diary (#6) and credits (#2) |
| 11 | **Wider poster quick actions** (mark watched, rate, like, watchlist, add to list) | Letterboxd long-press | H | M | Roadmap #13 |
| 12 | Small status/notes extras: **"Not interested"** dismiss, **"On hold"** status, **private notes** on a title | Netflix, Simkl, Trakt, Serializd | M | S | Own data |
| 13 | **Mood shelves** curated by Claude ("cozy Sunday", "under 90 minutes, funny") | Netflix AI Discover, Likewise | H | S | Lists/tags via the API plus TMDB `with_keywords` and runtime presets |
| 14 | Imports (Trakt, IMDb; TV Time only from a saved export) and export | Trakt, Simkl, Letterboxd | M | M | Roadmap #6. TV Time shut down on 2026-07-15. |

**Later / low value for a solo user:**
- Leaving-soon and new-on-service feeds: TMDB has no "date added", so these need the Streaming Availability or Watchmode APIs.
- Oscar badges: Wikidata P166/P1411 joined on its TMDB id fields P4947/P4983.
- Price-drop alerts.
- Taste matching and group picks: needs more real users.
- Episode reactions, check-ins, phone widgets.
- Social feeds, comments, HQ pages.

Letterboxd has no multi-season TV, so this app's TV support already goes beyond it.

## Compliance notes

- **JustWatch credit:** TMDB requires a JustWatch credit wherever watch-provider data is shown. It's being added under "Where to watch" on title pages.
- **TMDB terms:** non-commercial use, cache for at most 6 months. They also forbid using TMDB content "in connection with... an AI based Application", which is a gray area for Claude reading TMDB data through this app's API. This is a personal, non-commercial instance; revisit if that changes.
- **JustWatch:** no public API (partners only), and the unofficial wrappers amount to scraping. Don't use them.
- **Rotten Tomatoes:** never scrape it (see the scores decision in design-decisions.md).

## Sources (selected)

- Letterboxd: https://letterboxd.com/about/pro/ · https://letterboxd.com/about/faq/ · https://letterboxd.com/about/importing-data/
- Trakt: https://forums.trakt.tv/t/rewatching-tv-shows/19094 · https://alternativeto.net/news/2025/12/trakt-rolls-out-continue-watching-and-start-watching-smart-lists-in-new-lite-design
- TV Time shutdown: https://techcrunch.com/2026/07/02/popular-tv-tracking-app-tv-time-is-shutting-down-as-company-focuses-on-ai
- Plex availability alerts: https://support.plex.tv/articles/push-notifications/
- Reelgood Roulette: https://bgr.com/2028118/reelgood-roulette-help-decide-what-to-watch
- Netflix AI Discover: https://about.netflix.com/en/news/introducing-ai-discover
- TMDB terms and watch providers: https://www.themoviedb.org/api-terms-of-use · https://developer.themoviedb.org/reference/movie-watch-providers
- Watchmode: https://api.watchmode.com/llms.txt · JustWatch partners: https://apis.justwatch.com/docs/content_partner
