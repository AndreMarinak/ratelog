# Product, conventions and deploy

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
- **Browser tests live in the repo (`tools/uitest/`) and seed their own data** (user request 2026-11-23: "make sure this doesn't happen again with testing"). The walkthroughs used to live in a temp scratchpad (a reboot could wipe them) and leaned on leftover data (`/list/17`, titles that had to be rated or unrated, a token file), so they failed on a second run or another database. Now the runner makes its own API token (memory only), snapshots the account's ratings and statuses, seeds lists named `uitest <stamp> ...`, looks everything up by what it created, and at the end deletes those lists, puts every changed rating / status back and revokes the token. Trade-off: restored ratings get a new `createdAt`, so "Recently rated" order can shift after a run on a long-lived database. How to run it: [testing.md](../testing.md).

## Deployment (Railway)

- **Dockerfile build, `/api/health` healthcheck, `PORT=8080` pinned** to match the domain's target port; `server.forward-headers-strategy=framework` so the app knows it is on https behind the proxy (HSTS, Secure cookies). Since the security hardening below, only the headers Railway's edge sets are trusted and redirects are relative.
- **`ADMIN_PASSWORD`** replaces the seeded `admin`/`admin` password at startup, but only while the default is still active and only if 12+ characters; it never overwrites a password changed in the UI.
- **`TMDB_API_KEY` env var always wins** over the value stored in Admin > Configuration (upstream only read it on first start). Obvious placeholders (`<...>`, `REPLACE_ME`, whitespace) are refused with a warning. To manage the key in the UI instead, remove the variable.
- **Readable TMDB error page** instead of a raw JSON 500 when TMDB rejects the key or is down.
- **Runtime image pinned to an exact tag** (`eclipse-temurin:21.0.13.1_2-jre-alpine`, 2026-11-23) instead of the floating `21-jre-alpine`: a rebuild could reuse an old cached copy of a floating tag and miss Java security fixes. Bump the tag deliberately.
