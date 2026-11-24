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
- **Before anything runs, it snapshots** every rating and status of the account (`GET /api/v1/ratings`, `/api/v1/statuses`).
  At the end it puts back every rating / status that differs (whatever a step changed, even a title it picked at random) and
  deletes every list whose name starts with `uitest <stamp>`. Ctrl-C runs the same cleanup.
- **Each suite seeds what it uses**: `setup()` once (lists, with unique `uitest <stamp> ...` names; ids come from the API
  response) and `reset()` before each viewport (exact ratings / statuses on the titles the steps touch, because the previous
  viewport changed them).
- **Steps never use fixed list ids or "whatever is in the database".** A title id is fine only if `reset()` put that title in
  the state the step expects. Dates move: the "upcoming" movie is taken from TMDB's coming-soon row at run time, not hard-coded.
- Known noise is ignored centrally (`lib/browser.js` `IGNORED`): YouTube's missing `maxresdefault` thumbnails and
  `favicon.ico`. Any other console error, page error or HTTP 4xx/5xx fails the step it happened in; a step that expects one
  passes `{ allow: /regex/ }`.

## Suites

| Suite | Covers | Seeds |
|---|---|---|
| `home` | home rows, poster sharpness, row scroll, bookmark in place, header search (`/`), Discover rows / grid / load more / `/browse` redirect, title page actions + rate modal, add / remove from a list in place, official list mark / Lists tab / Discover row / unmark, Lists tab, list page back link + progress + tabs in place, phone bottom nav + menu sheet, nav links, Ratings tab switch + filters, tags hidden | two lists (Toy Story rated, Inside Out + WALL-E unseen; one public for the official step) |
| `seen` | Upcoming badge (home, search, title page, list), home / Discover / search tiles Seen + Watchlist in place, Lion King's four actions, Seen -> "Rate it?" -> Skip, Watchlist replacing Seen, quick rating from the nudge + locked Seen, Rate / Add to list sheets, follow bell (upcoming movie, returning vs ended show), TV Watching / Seen, /watchlist Remove + Undo, Seen / Watching move tiles, list tick-off mode across tab swaps | watchlist (Up, Dune: Part Two, The Bear, The Last of Us, the upcoming movie), show statuses, two lists |
| `tilerate` | tile star picker (sheet on phones), every copy updates, tiles fit their actions, Coming soon tiles have no star, add to list from a tile, edit / delete a quick rating from search, detailed rating -> title page edit modal, `?rate=detailed`, TV tile score links to seasons, Discover grid, watchlist rows + rating from the star, list page rate in place + progress, tick-off "Rate it?" prompt moving, ranking cards, To rate view, no-JS star link | Matrix 8.5, Interstellar 9, Heat detailed, Toy Story + Inception seen unrated, The Bear S1 8, watchlist titles, a 10-title list and a one-title list |

Not ported yet from the old scratchpad scripts (still to do; each needs its own seeding first): trailer / Delete rating / 404 checks, `recs`, `backnav`, `inlists` (the add-to-list
sheet in depth: ranks, rapid taps, cancel, many lists), `listorder` (sort menu, reorder by drag / arrows / number, numbered vs
plain lists, non-owner view), `listsuggest` (Suggested for this list row: + Add, More suggestions, Not interested, official
lists of other users), `smallfixes` (Ratings filters applying in place, the page loader, hidden official lists seen by a second
user) and `secfix` (security headers / rate-limit / lockout checks). Several of them need a second user account, which the runner
does not create yet.

## Add a step

Suites are `tools/uitest/suites/<name>.js` modules: `{ name, description, setup(env), reset(env, data), run(t) }`. Inside
`run`, each check is a `t.step(name, async () => { ...; return 'detail for the report'; })`. Helpers on `t`
(`lib/browser.js`):

- `t.go(url)`, `t.navClick(sel)`, `t.press(handle)` / `t.pressSel(sel)` (tap on phones, click on desktop), `t.waitFor(fn, arg, ms)`
- `t.mark()` + `t.stillHere(token)`: the action saved in place (no reload / navigation)
- `t.toast(text?)`, `t.noOverflow()`, `t.shot(name, fullPage?)`, `t.assert(cond, msg)`
- `t.api.must(method, path, body)` / `t.api.call(...)` (the run's token), `t.data` (what `setup` returned, plus `prefix`), `t.mobile`, `t.vp`

Rules: wait on events (`waitFor`, `waitForNavigation`), not fixed sleeps; the laptop is often busy. A step that times out is
retried once and the report says so; pass `{ retry: false }` when a step can't be repeated safely. If the step needs data, seed it
in `reset()` (or at the start of the step) through the API, by the ids or names you created. Register a new suite name in
`ORDER` in `run.js`.
