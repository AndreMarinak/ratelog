# Deploying on Railway

The repo builds with its `Dockerfile` (see `railway.toml`). No SQL is needed: Flyway creates and migrates all tables on startup.

## 1. Services

In the Railway project:
1. **Postgres**: `+ New` → `Database` → `PostgreSQL`. Keep the service name `Postgres` (the variable references below use it).
2. **Ratelog**: the service deployed from GitHub repo `AndreMarinak/ratelog`, branch `master`.

## 2. Variables on the Ratelog service

| Variable | Value |
|---|---|
| `POSTGRES_HOST` | `${{Postgres.PGHOST}}` |
| `POSTGRES_PORT` | `${{Postgres.PGPORT}}` |
| `POSTGRES_DB` | `${{Postgres.PGDATABASE}}` |
| `POSTGRES_USER` | `${{Postgres.PGUSER}}` |
| `POSTGRES_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
| `ADMIN_PASSWORD` | a password of 12+ characters; replaces the seeded `admin`/`admin` login on first start |
| `TMDB_API_KEY` | TMDB v3 "API Key" (only read on first start; later changes go in Admin > Configuration) |
| `PORT` | `8080` (pin it so it matches the domain's target port) |
| `JAVA_TOOL_OPTIONS` | optional: `-XX:MaxRAMPercentage=75` |

## 3. Domain

Ratelog service → `Settings` → `Networking` → `Generate Domain` with **target port 8080**. Log in as `admin` with your `ADMIN_PASSWORD`.

A **502** from the domain means nothing healthy is listening: usually missing `POSTGRES_*` variables (the app crash-loops
trying `localhost:5432`), a target port that doesn't match `PORT`, or the app still starting (first boot takes about a minute).
Check the Deploy Logs for `Started RatelogApplicationKt` or the first `Caused by:` line.

## 4. Checks

- Deploy logs should show Flyway migrating to version `1000+` and `Started RatelogApplicationKt`.
- `https://<your-domain>/api/health` returns `{"status":"ok"}`.
- If logs warn that `admin` still uses the default password, `ADMIN_PASSWORD` was missing or under 12 characters: fix it and redeploy, or change the password in the UI right away.
