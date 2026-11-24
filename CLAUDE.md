# Ratelog (personal fork)

Fork of [golmenero/ratelog](https://github.com/golmenero/ratelog): self-hosted, Letterboxd-style ratings for movies and TV.
Kotlin 2.4 + Spring Boot 3.5 (Thymeleaf server-rendered UI, Spring Data JDBC, Flyway, PostgreSQL), Arrow `Either` for errors, TMDB for metadata.
Live: https://ratelog-production.up.railway.app (Railway deploys `master` on every push).

## TOP PRIORITY: run agents so work never backs up

The user wants many things built in parallel, but on 2026-11-23 six agents at once (each running Maven, a copy of the app and
Chrome) pushed this laptop to 100% CPU, a "small popup" agent took 2+ hours, and finished branches waited for each other. Rules:

1. **At most 3 coding agents at once.** Research / list-import agents (no builds) may run alongside, at most 5 agents total.
   New requests wait in a queue (tell the user what's queued); start the next agent the moment one finishes.
2. **One feature per agent, small and specific.** Never pile new requests onto a running agent. Only a tiny tweak to the exact
   screen it is building, sent early, may be added; anything else becomes its own queued agent.
3. **Time-box every agent:** the brief says "commit a working checkpoint within 45 minutes and report within about 75". Big
   features are split into phases, each its own commit and report. If an agent is late, tell it to wrap up and ship what works.
4. **Agents test their feature, not the world.** While building: the targeted unit tests. At the end: ONE full clean build
   (0 failures) and a SHORT browser check of their feature (1440px + one phone width, with screenshots). The full regression
   walkthrough is run once by the main session after merging, not by every agent.
5. **Merge and ship continuously.** Merge each finished branch right away (keep both sides of docs / `messages.properties`
   conflicts), then one clean build + one walkthrough run per batch, then push. Never let more than 2 finished branches wait.
6. **Start agents from the latest master**, give each its own port and database, and don't run two agents on the same
   files at once (run them one after the other instead).
7. **Browser tests seed their own data** (create their own lists / ratings through the API, never rely on ids or titles left
   in a database), so they pass on any database and on a second run.
8. **Status on request:** for each agent, how long it has run, its stage (building / testing / docs / committed) and what's queued.

## Documentation: keep it current (required)

All documentation lives under `docs/`. **A change is not done until its docs are updated, in the same commit** (or the same merge for
agent branches). Before every commit and push, check each file below and update the ones the change touches:

| File | Update it when |
|---|---|
| [docs/design-decisions.md](docs/design-decisions.md) | you choose how something works or looks: write the decision **and why** (user request with date, data, trade-off) |
| [docs/bugs-and-issues.md](docs/bugs-and-issues.md) | you find a bug (cause + fix), or leave one open (move it from Open to Fixed when fixed) |
| [docs/roadmap.md](docs/roadmap.md) | a feature is requested, started or finished; new migration ranges |
| [docs/api.md](docs/api.md) | any `/api/v1` endpoint, field or error code changes |
| [docs/deploy-railway.md](docs/deploy-railway.md) | env vars, Railway setup or recovery steps change |
| [docs/competitor-gaps.md](docs/competitor-gaps.md) | a researched gap is built or re-ranked |
| [docs/testing.md](docs/testing.md) | a browser suite, step or the way the app is run for tests changes |

- Edit statements that are no longer true; don't leave stale text. A replaced decision stays, marked *(Superseded by ...)*.
- Record the user's own requests and preferences as decisions, with the date (they explain the "why" later).
- Parallel agents update docs in their branch; on merge, keep both sides of doc conflicts.
- Nothing outside `docs/` except this file, `README.md` and upstream's `CHANGELOG.md`.

## Build and test

- Java 21. Maven is not on PATH here: use `C:/Users/amari/tools/apache-maven-3.9.11/bin/mvn` (no wrapper in the repo).
- `mvn -B -ntp test`: unit tests only, no DB needed (handlers tested with `src/test/kotlin/org/ratelog/test/InMemory*Repository` fakes and mockito-kotlin mocks of `TmdbClient`).
- Use `mvn -B -ntp clean package` after merging branches: incremental Kotlin compiles keep stale classes (`NoSuchMethodError` in tests).
- Run locally: `docker compose up -d postgres`, then `mvn -DskipTests package` and `POSTGRES_DB=<db> PORT=8085 java -jar target/ratelog-1.0-SNAPSHOT.jar`. Default login `admin` / `admin`. Use a separate database and port per agent.
- TMDB key: set `TMDB_API_KEY` env var (v3 "API Key") on first start, or Admin > Configuration. Detail pages and title lookups need it.

## Before every push (the user's rule: "don't compromise on tests")

1. Full test suite on a clean build, 0 failures (count from `target/surefire-reports`).
2. Browser check of every changed page in the real app, desktop (1440px) and phone (390px and 360px), with screenshots: no console
   errors, layout fits, the change works end to end.
   Run the walkthrough suite: `node tools/uitest/run.js --base http://localhost:PORT` (all suites, 1440 / 390 / 360; it seeds and cleans up
   its own data, `--suite home,seen` to narrow it). Setup and what each suite covers: [docs/testing.md](docs/testing.md).
3. Docs updated (see above). Then push `master` and confirm the Railway deploy came up (`/api/health`).

## Code conventions (follow upstream's style)

- Vertical slices: `feature/action/XxxController.kt` -> `XxxHandler.kt` (returns `Either<XxxError, T>`, errors are a sealed interface) -> `XxxRepository` (interface + `Impl`) -> `XxxDAO.kt` (Spring Data JDBC entity + `CrudRepository`).
- Value objects live in `VOs.kt`; `MediaType` is `movie` / `tvshow`.
- UI strings go in `src/main/resources/messages.properties` (English; other languages fall back to it). Templates in `templates/`, modals in `templates/fragments/`. Icons are Lucide (`<i data-lucide="name">`).
- Every new handler gets a unit test using the in-memory fakes.

## Fork rules

- **Migrations:** fork migrations are numbered `V1000+` (`spring.flyway.out-of-order: true`) so upstream's `V22`, `V23`... never collide. Never edit an existing migration. Use the feature's reserved range in [docs/roadmap.md](docs/roadmap.md).
- Prefer new files and new packages over editing upstream files; keep edits to upstream files small (`@ControllerAdvice`, interceptors, repository decorators, one `th:replace` line per template). Merging upstream: `git fetch upstream && git merge upstream/master`.
- New JSON endpoints go under `/api/v1/` in `org.ratelog.api` and are covered by `ApiSecurityConfig` (token auth, stateless, no CSRF). Errors: throw `ApiException` / `badRequest()` / `notFound()`.
- **Parallel work:** one git worktree per feature (`../ratelog-worktrees/<name>`, branch `feat/<name>`), append-only `messages.properties` sections per feature, merged into `master` with `--no-ff` one at a time, tested after each merge.
- **Small actions save in place** (no reload, a toast confirms): add `data-inplace="<regions>"` to the form (`static/inplace.js`). Navigation and whole-result changes load a page. The audit table is in design-decisions.md; add new actions to it.
- **Feature flags:** `ratelog.features.*` in `application.yml`, exposed to templates by `system/FeatureFlagsAdvice.kt` (tags are hidden: `tagsEnabled`).
- Buttons ignore `hidden` (base `button { display: inline-flex }`): add `&[hidden] { display: none }` to any button you toggle.
- **Stay inside the Content-Security-Policy** (`security/SecurityHeaders.kt`): scripts, styles and fonts only from the app itself
  (no CDNs), images only from the app, `data:`, `image.tmdb.org` and `i.ytimg.com`, iframes only from `www.youtube-nocookie.com`.
  Prefer static `.js` files over new inline `<script>` blocks and `on*=` handlers. The browser check must show no CSP errors.
- In-place `fetch` handlers must cope with `429` (request rate limit): show the response's message as a toast, don't re-post.

## Importing lists for the user (Claude workflow)

The user wants Claude to build lists from research ("best heist films", "underrated 2010s sci-fi") and import them. Full reference: [docs/api.md](docs/api.md).

1. Needs `RATELOG_URL` and `RATELOG_TOKEN` (token from the profile's key icon, `/settings/api`). Ask the user if missing; never print the token back.
2. Research the list. For each entry record **title + release year + type** (`movie` or `tv`). Years matter: without one, remakes and same-name titles come back as ambiguous.
3. `POST /api/v1/lists` with `{"name": ..., "items": [{"title": ..., "year": ..., "type": ...}]}` (max 100 items per request; send more with `POST /api/v1/lists/{id}/items`).
   Items keep the order sent (ranked lists: send them in rank order). Fix the order later with `"position"` on an item, `PATCH .../items/{type}/{tmdbId}` or `PUT .../order`; unranked collections get `"numbered": false`.
4. Read `unresolved` in the response. For `ambiguous`/`not_found`, pick the right entry from `candidates` (or `GET /api/v1/search?q=...`) and re-send it as `{"tmdbId": ..., "type": ...}`. Never guess silently; tell the user about anything you couldn't place.
5. Report the summary and the list URL (`$RATELOG_URL/list/{id}`).
6. Researched site-wide lists go on the **admin** account and only the main ones are marked official (`PATCH /api/v1/lists/{id}` `{"isOfficial": true, "isPublic": true}`: marking alone never publishes, and a private official list stays hidden); the user's second account (`andre`) only gets lists the user asks for.
