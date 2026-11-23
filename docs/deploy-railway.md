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
| `TMDB_API_KEY` | TMDB v3 "API Key" (32 characters). When set, it overrides the value in Admin > Configuration on every start |
| `PORT` | `8080` (pin it so it matches the domain's target port) |
| `MDBLIST_API_KEY` | optional: [MDBList](https://mdblist.com/preferences/) API key for IMDb / Rotten Tomatoes / Metacritic / Letterboxd scores on title pages (free tier: 1,000 requests/day; scores are cached). Without it the scores are hidden. |
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

## Reset the admin password

If you're locked out (e.g. `ADMIN_PASSWORD` was set to the wrong text on first start):
1. Generate a bcrypt hash for a new temporary password (Claude can do this; Spring's `BCrypt.hashpw(password, BCrypt.gensalt(10))`).
2. Railway → **Postgres** service → **Console** → `psql -U $PGUSER -d $PGDATABASE`, then at the `railway=#` prompt
   (not in the shell, which would mangle the `$` signs):
   ```sql
   UPDATE users SET password_hash = '<bcrypt hash>' WHERE username = 'admin';
   ```
   Expect `UPDATE 1`, then `\q`.
3. Log in with the temporary password and immediately change it (Profile → pencil).
