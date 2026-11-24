# Testing

Two layers:

1. **Unit tests** (`mvn -B -ntp test`, no database): handlers with the in-memory fakes. See CLAUDE.md "Build and test".
2. **Browser walkthroughs** (`tools/uitest/`): Puppeteer drives a real Chrome against a running app, at desktop and phone
   widths, and checks that each screen works end to end with no console errors, no failed requests and no sideways scroll.

## Run the app for tests

Use your own port and database so nothing else is disturbed:

```bash
docker compose up -d postgres
docker exec ratelog-postgres createdb -U postgres ratelog_mytest      # once
mvn -B -ntp -DskipTests package                                         # clean package if Kotlin changed
TMDB_API_KEY=... POSTGRES_DB=ratelog_mytest PORT=8125 java -jar target/ratelog-1.0-SNAPSHOT.jar
```

- The suites need a TMDB key (home rows, Discover, title pages). On this laptop it can be read from an existing test database:
  `export TMDB_API_KEY="$(docker exec ratelog-postgres psql -U postgres -d ratelog_uitest -Atc "select value from general_config where key='tmdb_api_key'")"`
  (pass it only through the environment; never print it).
- A fresh database gets the default `admin` / `admin` login, which is what the suite uses.
- When done: stop the app and `docker exec ratelog-postgres dropdb -U postgres ratelog_mytest`.

## Run the suite

```bash
cd tools/uitest && npm ci          # once: puppeteer-core 23.11.1 only, no Chrome download (uses the installed Chrome)
node tools/uitest/run.js --base http://localhost:8125
node tools/uitest/run.js --base http://localhost:8125 --suite home,seen --viewports desktop,phone390
```

| Flag | Default | |
|---|---|---|
| `--base` | (required) | app URL, any port |
| `--suite` | all | comma list of suite names (below) |
| `--viewports` | `desktop,phone390,phone360` | `desktop` 1440x900, `laptop` 1280x720, `phone390` 390x844 @3x touch, `phone360` 360x740 @2x touch |
| `--out` | `tools/uitest/screenshots/<stamp>/` | screenshots, one folder per suite, `<viewport>-<nn>-<name>.png` (failures: `...-FAIL-<step>.png`) |
| `--user`, `--password` | `admin` / `admin` (or `UITEST_USER`, `UITEST_PASSWORD`) | the account the suite logs in as |

Output: one line per step (`[suite/viewport] OK   step (details)` or `FAIL step: reason`), then `suite: N/M passed` per suite, then
`ALL PASSED` or `FAILED`. The exit code is non-zero on any failing step or an incomplete cleanup. Chrome is found at the usual
Windows / macOS / Linux paths; set `CHROME_PATH` otherwise.

Against a shared app (the long-lived 8110 one): run suites one after another, never in parallel (rate limit: 60 writes/min per
account), and never with a wrong password (login lockout after 10 failures per username in 15 minutes).

## The self-seeding rule

Every suite must pass on a fresh database, on a database full of leftover data, and on a second run in a row. So:

- **The runner makes its own API token** through `/settings/api` (named `uitest <stamp>`). It is kept in memory only: never
  printed, never written to a file. It is revoked at the end.
- **Before anything runs, it snapshots** every rating and status of the account (`GET /api/v1/ratings`, `/api/v1/statuses`;
  since 2026-11-24 a status also records `onWatchlist`, so a Watched title on the watchlist is put back as both).
  At the end it puts back every rating / status that differs (whatever a step changed, even a title it picked at random) and
  deletes every list whose name starts with `uitest <stamp>`. Ctrl-C runs the same cleanup.
- **Each suite seeds what it uses**: `setup()` once (lists, with unique `uitest <stamp> ...` names; ids come from the API
  response) and `reset()` before each viewport (exact ratings / statuses on the titles the steps touch, because the previous
  viewport changed them).
- **Rewatches** (2026-11-24) are not in the snapshot: a step that logs one removes it again (the toast's Undo) and compares
  `watchCount` (`GET /api/v1/titles/...`) before and after, so a row left by a failed run never breaks the next.
- **Steps never use fixed list ids or "whatever is in the database".** A title id is fine only if `reset()` put that title in
  the state the step expects. Dates move: the "upcoming" movie is taken from TMDB's coming-soon row at run time, not hard-coded.
- Known noise is ignored centrally (`lib/browser.js` `IGNORED`): YouTube's missing `maxresdefault` thumbnails and
  `favicon.ico`. Any other console error, page error or HTTP 4xx/5xx fails the step it happened in; a step that expects one
  passes `{ allow: /regex/ }`.

## Suites

| Suite | Covers | Seeds |
|---|---|---|
| `home` | home rows, poster sharpness, row scroll, bookmark in place, header search (`/`), Discover rows / grid / load more / `/browse` redirect, title page actions + rate modal, add / remove from a list in place, official list mark / Lists tab / Discover row / unmark, Lists tab, list page back link + progress + tabs in place, phone bottom nav + menu sheet, nav links, Ratings tab switch + filters, tags hidden | two lists (Toy Story rated, Inside Out + WALL-E unseen; one public for the official step) |
| `seen` | Upcoming badge (home, search, title page, list), home / Discover / search tiles Seen + Watchlist in place, Lion King's four actions, Seen -> "Rate it?" -> Skip, Watchlist on a seen title (no confirm, stays seen; 2026-11-24), quick rating from the nudge + locked Seen, Rate / Add to list sheets, follow bell (upcoming movie, returning vs ended show), TV Watching / Seen, /watchlist Remove + Undo, Seen / Watching move tiles, rewatch log (2026-11-24): Seen's menu on a seen title (Watched again in place, toast Undo; a rated movie's menu explains; Severance un-seen through "Mark as not seen"), To watch's Watched again (logs it, leaves To watch, Undo puts both back, Remove keeps it seen), counts read from the API before and after, list tick-off mode across tab swaps | watchlist (Up, Dune: Part Two, The Bear, The Last of Us, the upcoming movie), show statuses, Coco Watched, two lists |
| `tilerate` | tile star picker (sheet on phones), every copy updates, tiles fit their actions, Coming soon tiles have no star, add to list from a tile, edit / delete a quick rating from search, detailed rating -> title page edit modal, `?rate=detailed`, TV tile score links to seasons, Discover grid, watchlist rows + rating from the star, list page rate in place + progress, tick-off "Rate it?" prompt moving, ranking cards, To rate view, no-JS star link | Matrix 8.5, Interstellar 9, Heat detailed, Toy Story + Inception seen unrated, The Bear S1 8, watchlist titles, a 10-title list and a one-title list |
| `title` | trailer thumbnail size on a movie and a show (fills the column on desktop, HD image or fallback; compact on phones), trailer modal, movie Rate modal in place (quick create, quick edit, detailed edit with bars, delete; panel score, Seen, toast, no reload), `?rate=edit` saving in place with the URL replaced, Delete rating in the movie editor in place (confirmation, API says gone, create mode has no delete), Delete rating for a TV season, 404 pages instead of a JSON 500 | The Incredibles rated 7.5, Breaking Bad season 1 rated 9, Parasite unrated with no status |
| `recs` | series row and More like this: skeleton in the first HTML, lazy rows, order and current tile, poster sizes, Seen on a series tile and the page Seen button move the progress (desktop), bookmark in place + Seen hides it next visit (desktop), no series row for a film outside a collection, the upcoming movie's series entry, TV rows after the seasons, phone rows scroll inside | the Toy Story films unseen |
| `backnav` | back link on title pages: Home for a direct open, Discover grid (URL + scroll restored), Discover row -> related -> related with the previous-title step, reload keeps it, browser Back restores the row, Search results, a list's name (tab + scroll kept), each page's own label, pending state from a title, no-JS Referer link, long names on one line | a 20-title Disney list with six titles unseen |
| `savedlists` | Save on an official card on the Lists tab (in place, toast, the card moves first), Save beside an official list title (in place), `/lists/official` saved lists first (newest save first), title page "Official lists" chips with saved ones first and ranks, un-save puts the list back | two official public lists with Pulp Fiction at #3 and #1 (saves dropped with the lists) |

Not ported yet from the old scratchpad scripts (still to do; each needs its own seeding first): `inlists` (the add-to-list
sheet in depth: ranks, rapid taps, cancel, many lists), `listorder` (sort menu, reorder by drag / arrows / number, numbered vs
plain lists, non-owner view), `listsuggest` (Suggested for this list row: + Add, More suggestions, Not interested, official
lists of other users), `smallfixes` (Ratings filters applying in place, the page loader, hidden official lists seen by a second
user) and `secfix` (security headers / rate-limit / lockout checks). Several of them need a second user account, which the runner
does not create yet.

## Status

Updated for master `5a03f3d` (2026-11-23): Classics is checked on Discover (it left Home), the Ratings step no longer expects the
count select (rankings load as you scroll), and the phone menu sheet has 5 items (Stats added). Run against the main session's
port 8110 app on `5a03f3d`: home 58/58 (after the update), seen 50/50, tilerate 54/54, title 21/21, recs 25/25, backnav 39/39.

Updated for the rewatch branch (2026-11-24, `feat/rewatch`): the watchlist is independent of Seen, so the seen suite expects the
bookmark on seen tiles and no confirm on the title page, and adds a "Rewatch" step on /watchlist (53 steps). `setTitle`, the
snapshot and the restore know `onWatchlist`. Run on `feat/rewatch` (fresh `ratelog_rewatch` database, port 8235, desktop / 390 /
360): **250/250** (home 58, seen 53, tilerate 54, title 21, recs 25, backnav 39).

Updated for the rewatch log (2026-11-24, `feat/rewatchlog`): on a seen title the title page's Seen opens a menu, so the
seen suite un-sees Severance through "Mark as not seen" and checks the rated Lion King's menu instead of a locked toast; the old
"Rewatch" tag step became two self-seeding Watched again steps (title page menu + Undo, To watch + Undo + Remove) that compare
`watchCount` before and after. Run on `feat/rewatchlog` (`ratelog_rewatchlog` database with leftover data, port 8122, desktop /
390 / 360): **277/277** (home 58, seen 56, tilerate 54, title 27, recs 25, backnav 39, savedlists 18).

Updated for master `7addbcf` (2026-11-23): poster actions go through `t.tileAct()` (the "⋯" chooser on touch, hover + click
with a mouse), the official toggle is used from the list's "⋯" List actions menu (with its confirm), list tabs replace the URL
(one Back leaves the list), the watchlist is opened per type (`?type=movie` / `?type=tv`; it opens on Movies), a show rated by
season links its ★ to the seasons, and TV season deletes save in place.

Last verification (2026-11-23, master `7addbcf`, fresh `ratelog_uitests` database on port 8125, desktop / 390 / 360): two
full runs in a row, both **247/247** (home 58, seen 50, tilerate 54, title 21, recs 25, backnav 39), no retries, nothing
skipped; each run's cleanup left the account with no ratings, statuses or `uitest` lists (the next run's snapshot read 0).

## Safari (iPhone) is not covered

The suite drives Chrome; its phone viewports emulate an iPhone's size, touch and user agent, not Safari's layout engine. On
2026-11-24 a WebKit-only layout bug (the poster "⋯" 12px from the corner, see [bugs-and-issues.md](bugs-and-issues.md#fixed))
passed every check here and reached the user's iPhone. Until the suite gets a WebKit pass, check layout changes to posters,
sheets and sticky bars in WebKit too: Playwright's WebKit with `devices['iPhone 13']` measures the same boxes (log in from Node
and hand WebKit the session cookie with `addCookies`: WebKit on Windows drops the app's Secure cookie over plain http).

## Add a step

Suites are `tools/uitest/suites/<name>.js` modules: `{ name, description, setup(env), reset(env, data), run(t) }`. Inside
`run`, each check is a `t.step(name, async () => { ...; return 'detail for the report'; })`. Helpers on `t`
(`lib/browser.js`):

- `t.go(url)`, `t.navClick(sel)`, `t.press(handle)` / `t.pressSel(sel)` (tap on phones, click on desktop), `t.waitFor(fn, arg, ms)`
- `t.tileAct(stack, 'watchlist' | 'seen' | 'rate' | 'lists')`: a poster's quick action as a person uses it (opens the "⋯"
  chooser on touch viewports, hovers the poster with a mouse). Never click the hidden poster buttons by script.
- `t.mark()` + `t.stillHere(token)`: the action saved in place (no reload / navigation)
- `t.toast(text?)`, `t.noOverflow()`, `t.shot(name, fullPage?)`, `t.assert(cond, msg)`
- `t.api.must(method, path, body)` / `t.api.call(...)` (the run's token), `t.data` (what `setup` returned, plus `prefix`), `t.mobile`, `t.vp`

Rules: wait on events (`waitFor`, `waitForNavigation`), not fixed sleeps; the laptop is often busy. A step that times out (or
reads a page that a navigation just replaced) is retried once and the report says so; pass `{ retry: false }` when a step can't be repeated safely. If the step needs data, seed it
in `reset()` (or at the start of the step) through the API, by the ids or names you created. Register a new suite name in
`ORDER` in `run.js`.
