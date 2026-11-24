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
| `MDBLIST_API_KEY` | optional: [MDBList](https://mdblist.com/preferences/) API key for IMDb / Rotten Tomatoes / Metacritic / Letterboxd scores on title pages (free tier: 1,000 requests/day; scores are cached). Without it the scores are hidden. Easier: paste the key on **Admin → Configuration** ("MDBList API key", like the TMDB key); a saved key wins over this variable and works without a redeploy. |
| `JAVA_TOOL_OPTIONS` | optional: `-XX:MaxRAMPercentage=75` |

Optional security settings (the defaults are right for Railway; see "Security hardening" in [design-decisions.md](design-decisions.md)):

| Variable | Default | What it does |
|---|---|---|
| `RATELOG_RATE_LIMIT_ENABLED` | `true` | general "too many requests" limit per account / API token / IP (`429` + `Retry-After`) |
| `RATELOG_RATE_LIMIT_READS_PER_MINUTE`, `RATELOG_RATE_LIMIT_READ_BURST` | `600`, `100` | read requests (GET) per minute, plus a burst |
| `RATELOG_RATE_LIMIT_WRITES_PER_MINUTE`, `RATELOG_RATE_LIMIT_WRITE_BURST` | `60`, `30` | writes (POST, PUT, PATCH, DELETE) per minute, plus a burst |
| `RATELOG_CLIENT_IP_HEADER` | `X-Real-IP` | header with the client IP (Railway's edge sets it), used for sign-in throttling and anonymous rate limits |
| `RATELOG_CSP_REPORT_ONLY` | `false` | `true` sends the Content-Security-Policy as report-only (browsers log violations instead of blocking): an escape hatch if a page breaks after a change |
| `SESSION_COOKIE_SECURE` | `true` | keep it on Railway; `false` only for signing in over plain http from another host |

## 3. Domain

Ratelog service → `Settings` → `Networking` → `Generate Domain` with **target port 8080**. Log in as `admin` with your `ADMIN_PASSWORD`.

A **502** from the domain means nothing healthy is listening: usually missing `POSTGRES_*` variables (the app crash-loops
trying `localhost:5432`), a target port that doesn't match `PORT`, or the app still starting (first boot takes about a minute).
Check the Deploy Logs for `Started RatelogApplicationKt` or the first `Caused by:` line.

## 4. Checks

- Deploy logs should show Flyway migrating to version `1000+` and `Started RatelogApplicationKt`.
- `https://<your-domain>/api/health` returns `{"status":"ok"}`.
- If logs warn that `admin` still uses the default password, `ADMIN_PASSWORD` was missing or under 12 characters: fix it and redeploy, or change the password in the UI right away.
- The first deploy log line `Starting RatelogApplicationKt ... using Java 21.0.X` shows the runtime version: it should match the pinned image in the `Dockerfile` (`21.0.13.1` or later). If it is older, redeploy with the build cache cleared.
- The container runs as an unprivileged user (uid 10001) since 2026-11-23. Nothing on Railway needs changing for it; the app only writes to `/tmp`.
- Response headers include `Content-Security-Policy`, `Referrer-Policy`, `Permissions-Policy` and `X-Frame-Options: DENY`, and the `JSESSIONID` cookie is `Secure; SameSite=Lax`.
- Sign-ins lock for 15 minutes after 10 wrong passwords for one username (or 30 from one IP); the counts live in memory, so a redeploy clears them. New and changed passwords need 8+ characters.

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
