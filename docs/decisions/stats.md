# Stats

## Stats: your year in movies (2026-11-23)

From the later-features list the user picked one: *"go with the stats"* (your year in movies); everything else on that list is
on hold. Code: `org.ratelog.stats` (`StatsDAO`, `StatsRepository`, `StatsCalculator`, `GetStatsHandler`,
`StatsRuntimeFiller`, `web/StatsController`), `api/stats/ApiStatsController`, `templates/stats.html`, `static/stats.css`.

- **`/stats`, your own stats only.** Linked from the profile (chart icon next to the key), the phone menu sheet and the
  desktop header (an icon beside the profile one); no new bottom-nav tab. Title pages opened from it say "← Stats". Viewing
  someone else's stats is a possible follow-up, not in this version (the API has no user parameter either).
- **Year switcher:** this year (the default, also when the year is empty), every earlier year with something rated or seen,
  and "All time". Plain links (`/stats`, `/stats?year=2025`, `/stats?year=all`) that apply in place and replace the URL
  (`view-swap.js`, "One Back leaves the page"): one Back leaves Stats. A year that isn't valid (junk, the future) shows this
  year on the page and is `400 invalid_year` in the API. Years are calendar years in the server's time zone (the injected
  `Clock`), so a rating at 23:30 on Dec 31 counts in that year.
- **What counts as a title in a period** (`StatsCalculator`, one place for the rules): a movie when it was rated in the
  period, or (unrated) marked Watched in it, counted once at its rating's time. A show when seasons were rated in the period,
  with the **TV rule limited to the period**: its score is the average of those seasons and it counts at the last of them
  (All time: every rated season, i.e. its usual score). Without any rated season it counts through its whole-show score in
  the period it was given; while any season is rated the whole-show score is unused (rated seasons win), so it never counts
  then, not even in an earlier year. An unrated show counts when it was marked Watched. Counting titles, not rating events,
  keeps the headline, the months and the averages consistent (a 5-season binge is one show, not five titles).
  *(Since 2026-11-24 a title rewatched in the period counts in it too, once, see "Rewatches in Stats" below.)*
- **Headline:** Movies (with "N rated"), TV shows (with seasons rated), Ratings (movie + season + whole-show ratings in use),
  Average score (over rated titles, a show once), Hours watched. *(Since 2026-11-24 a line under the subtitle says
  "112 titles · 9 rewatches", see below.)*
- **Hours are movies only, and say so.** Runtimes come from the `title_extras` cache (V1080), which only title pages
  filled. A Stats view queues the movies with no cached runtime (up to 40 per view) on the extras pool (2 threads, same
  `type:tmdbId` key as title pages, TMDB half only, no MDBList call) and renders at once with "Movies only, based on 31 of 45
  movies so far: the rest are being looked up"; the next view counts them. A failed lookup waits for the cache's retry time.
  TV is left out: the cache has a show's episode runtime but not episode counts per season, and fetching them per show on
  page load is what the brief ruled out. No migration was needed (genres and release years are on `movies` / `tv`).
- **Rating distribution:** ten bars, 1-10; a score is rounded to the nearest point (scores go in quarter steps: 7.5 and 7.75
  count as 8, 7.25 as 7; anything under 1.5 counts as 1). Rounding rather than flooring because a detailed rating's average
  (7.8) is closer to 8.
- **Month by month** (year by year for All time, every year from the first to the last with data): titles per month in the
  same count as the headline, split into movies and TV (stacked, legend) *(since 2026-11-24 it counts watches: each title
  once when it counts, plus each rewatch in its month; see below)*. Series colors were run through the dataviz palette
  validator against the dark card surface: movies `#D2704F` (the app's clay accent, a step darker), TV `#5A93D8`; lightness,
  chroma, colorblind separation and contrast all pass. Single-series charts use the movie color.
- **Top genres** (8 rows; a title counts in each genre; movie and TV genre ids stay separate, as TMDB has them) with your
  average, plus a one-liner "Most generous with X (★ 9.1), harshest on Y (★ 7.5)" among genres with at least 3 rated titles.
  **Decades** by release year, oldest first.
- **Your highest rated:** the period's 10 best (score, then the most recent), as the app's usual poster tiles with the quick
  actions (the ★ chip shows the period score; for a show rated by season that is the average of the seasons rated that
  year), "#1 · 1994" under each. Two rows of five on desktop, three per row on phones.
- **Empty year:** "Nothing rated or marked seen in 2024 yet." with links to the newest year that has data and to All time.
- **Charts are CSS bars, drawn server-side** (the CSP allows no chart libraries from CDNs): inline percentage heights /
  widths, count labels above the vertical bars (phones can't hover; native `title` tooltips with a mouse). Every chart is
  `aria-hidden` and followed by a visually hidden table with the same numbers. Phones: stacked cards, two headline numbers
  per row, month initials ("J F M"), genre rows with the bar under the name; desktop: five headline numbers in a row and a
  two-column grid of charts.
- **Fast by construction:** three queries read everything the user rated or marked seen (movies, shows, seasons; one row
  each), the period is counted in memory, and one query per type reads the 10 tiles' titles and posters. No TMDB call during
  the request. Measured locally with 44 movies (42 rated), 5 shows and 4 season ratings over two years, warm: the whole
  page in 47-62 ms (median of 10 per view), `GET /api/v1/stats` in 27-34 ms.
- **API:** `GET /api/v1/stats?year=YYYY|all` returns the same numbers ([api.md](../api.md#stats)).

## Rewatches in Stats (2026-11-24)

From the rewatch log ([watchlist-and-seen.md](watchlist-and-seen.md#rewatch-log-watched-again-2026-11-24)); the user asked
*"how should it work with stats? maybe some ability to mark a movie as rewatched?"*. The rule: **a rewatch counts in the
period it happened**, and only while the title is seen (rows of an un-seen title are kept but skipped: `StatsDAO.findRewatches`
applies the `SeenState` rule in SQL). Everything else above stays as it was.

- **Titles watched in the period = first watches + titles rewatched in it, each title once.** The Movies and TV shows numbers
  include a title first seen years ago and rewatched this year; a title seen in February and rewatched in March counts once.
  A line under the subtitle sums them up: **"112 titles · 9 rewatches"** (the rewatch part only when there are any; a title
  rewatched twice counts 2 rewatches). A year with only a rewatch is not empty and shows in the year switcher.
- **A rewatch is not a rating:** "N rated", Ratings, Average score, the distribution, genres, decades and "Your highest rated"
  keep counting ratings and first watches only (a rewatch doesn't change what you thought of it; the toast's "Update your
  rating?" does).
- **Hours include each rewatch's runtime** (movies only, as before): a movie seen and rewatched twice in a year adds its
  runtime three times. "Based on N of M movies" counts each movie once; a rewatched movie with no cached runtime is queued
  for the same background lookup.
- **Month by month counts watches:** each first watch when it counts (as before) plus each rewatch in its month, so a busy
  rewatch month shows up. The hint and the table caption say "Watches per month"; the months can add up to more than the
  titles.
- **All time gets "Most rewatched":** a small card in the chart grid, up to 5 titles by rewatches (then the latest), each row
  a 34px poster, the title, type and year, and "Seen 4×" (1 + rewatches) in the seen green. Not on a single year (one or two
  rewatches a year make a thin list). One extra title lookup per type, only when there are rewatches.
- **API:** `totals.titles`, `totals.rewatches` and `mostRewatched` on `GET /api/v1/stats` ([api.md](../api.md#stats)).
