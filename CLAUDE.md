# Ratelog (personal fork)

Fork of [golmenero/ratelog](https://github.com/golmenero/ratelog): self-hosted, Letterboxd-style ratings for movies and TV.
Kotlin 2.4 + Spring Boot 3.5 (Thymeleaf server-rendered UI, Spring Data JDBC, Flyway, PostgreSQL), Arrow `Either` for errors, TMDB for metadata.
Fork features are tracked in [docs/roadmap.md](docs/roadmap.md); design decisions in [docs/design-decisions.md](docs/design-decisions.md); bugs/issues in [docs/bugs-and-issues.md](docs/bugs-and-issues.md). **Keep all documentation under `docs/`** and update those files in the same commit as the related change.

## Build and test

- Java 21. Maven is not on PATH here: use `C:/Users/amari/tools/apache-maven-3.9.11/bin/mvn` (no wrapper in the repo).
- `mvn -B -ntp test`: unit tests only, no DB needed (handlers tested with `src/test/kotlin/org/ratelog/test/InMemory*Repository` fakes and mockito-kotlin mocks of `TmdbClient`).
- Run locally: `docker compose up -d postgres`, then `mvn -DskipTests package` and `PORT=8085 java -jar target/ratelog-1.0-SNAPSHOT.jar`. Default login `admin` / `admin`.
- TMDB key: set `TMDB_API_KEY` env var (v3 "API Key") on first start, or Admin > Configuration. Detail pages and title lookups need it.

## Code conventions (follow upstream's style)

- Vertical slices: `feature/action/XxxController.kt` -> `XxxHandler.kt` (returns `Either<XxxError, T>`, errors are a sealed interface) -> `XxxRepository` (interface + `Impl`) -> `XxxDAO.kt` (Spring Data JDBC entity + `CrudRepository`).
- Value objects live in `VOs.kt`; `MediaType` is `movie` / `tvshow`.
- UI strings go in `src/main/resources/messages.properties` (English; other languages fall back to it). Templates in `templates/`, modals in `templates/fragments/`. Icons are Lucide (`<i data-lucide="name">`).
- Every new handler gets a unit test using the in-memory fakes.

## Fork rules

- **Migrations:** fork migrations are numbered `V1000+` (`spring.flyway.out-of-order: true`) so upstream's `V22`, `V23`... never collide. Never edit an existing migration.
- Prefer new files and new packages over editing upstream files; keep edits to upstream files small. Merging upstream: `git fetch upstream && git merge upstream/master`.
- New JSON endpoints go under `/api/v1/` in `org.ratelog.api` and are covered by `ApiSecurityConfig` (token auth, stateless, no CSRF). Errors: throw `ApiException` / `badRequest()` / `notFound()`.

## Importing lists for the user (Claude workflow)

The user wants Claude to build lists from research ("best heist films", "underrated 2010s sci-fi") and import them. Full reference: [docs/api.md](docs/api.md).

1. Needs `RATELOG_URL` and `RATELOG_TOKEN` (token from the profile's key icon, `/settings/api`). Ask the user if missing; never print the token back.
2. Research the list. For each entry record **title + release year + type** (`movie` or `tv`). Years matter: without one, remakes and same-name titles come back as ambiguous.
3. `POST /api/v1/lists` with `{"name": ..., "items": [{"title": ..., "year": ..., "type": ...}]}` (max 100 items per request; send more with `POST /api/v1/lists/{id}/items`).
4. Read `unresolved` in the response. For `ambiguous`/`not_found`, pick the right entry from `candidates` (or `GET /api/v1/search?q=...`) and re-send it as `{"tmdbId": ..., "type": ...}`. Never guess silently; tell the user about anything you couldn't place.
5. Report the summary and the list URL (`$RATELOG_URL/list/{id}`).
