# Ratelog API (v1)

JSON API for scripts and assistants (e.g. Claude) to manage your lists. Added in this fork.

## Auth

1. In the web UI, open your profile and click the **key** icon (or go to `/settings/api`).
2. Create a token. It is shown **once**; copy it.
3. Send it on every request, either way:
   - `Authorization: Bearer rl_...`
   - `X-API-Key: rl_...`

Browser sessions do not work on `/api/v1` (and the API needs no CSRF token). Revoke tokens on the same page.

## Titles

A title is identified by its **TMDB id + type**. Types: `"movie"` or `"tv"` (`"tvshow"`, `"show"`, `"series"`, `"film"` are also accepted).
Movie and TV ids are separate on TMDB, so **`type` is required with `tmdbId`**.

When adding items you can skip ids and send `title` (+ `year`, + `type` if known). Matching is strict:
- Exact title (ignoring case, accents, punctuation, a leading "The/A/An", `&` vs `and`) against the English or original title.
- With `year`: the same year wins; otherwise ±1 year is accepted if only one title fits.
- Without `year`: only matches if exactly one title has that name.
- Anything else comes back in `unresolved` with `candidates` (it is never guessed).

## Endpoints

| Method | Path | Body / params | Returns |
|---|---|---|---|
| GET | `/api/v1/me` | | `{id, username, role}` |
| GET | `/api/v1/search` | `q`, optional `type`, `year` | `{results: [Candidate]}` (raw TMDB search) |
| POST | `/api/v1/resolve` | `{title, year?, type?}` | `{status: matched\|ambiguous\|not_found, match, candidates}` (dry run, saves nothing) |
| GET | `/api/v1/lists` | | `[{id, name, isPublic, itemCount, url}]` |
| POST | `/api/v1/lists` | `{name, isPublic?, items?: [Item]}` | `201 {list, items: AddItemsResult \| null}` |
| GET | `/api/v1/lists/{id}` | | `{id, name, isPublic, url, items: [{position, tmdbId, type, title, year, posterUrl}]}` |
| PATCH | `/api/v1/lists/{id}` | `{name?, isPublic?}` | list detail |
| DELETE | `/api/v1/lists/{id}` | | `204` |
| POST | `/api/v1/lists/{id}/items` | `{items: [Item]}` | `AddItemsResult` |
| DELETE | `/api/v1/lists/{id}/items/{type}/{tmdbId}` | | `204` |

**Item**: `{"tmdbId": 949, "type": "movie"}` or `{"title": "Heat", "year": 1995, "type": "movie"}`. Unknown extra fields are ignored.
Items are appended in the order sent. At most **100 items per request**; at most 50 lists per user.

**AddItemsResult**:
```json
{
  "added":         [{"tmdbId": 949, "type": "movie", "title": "Heat", "year": 1995, "posterUrl": "..."}],
  "alreadyInList": [],
  "unresolved":    [{"input": {...}, "reason": "ambiguous", "message": "...", "candidates": [Candidate]}],
  "summary": "1 added, 0 already in list, 1 unresolved"
}
```
`reason` is one of `not_found`, `ambiguous`, `invalid`, `tmdb_error`. One bad item never fails the batch.

**Candidate**: `{tmdbId, type, title, originalTitle, year, tmdbVoteAverage, overview}`.

## Title details (scores and trailers)

`GET /api/v1/titles/{type}/{tmdbId}` (`type` is `movie` or `tv`, same spellings as above). Like opening the title's page,
it saves the title locally if needed. Returns the same extras the movie / TV pages show, plus your own rating:

```json
{
  "tmdbId": 603, "type": "movie", "title": "The Matrix", "year": 1999,
  "posterUrl": "https://image.tmdb.org/t/p/w342/...", "url": "/movie/603",
  "tmdbVoteAverage": 8.2, "tmdbVoteCount": 26543,
  "runtimeMinutes": 136, "episodeRuntimeMinutes": null, "numberOfSeasons": null,
  "trailerUrl": "https://www.youtube.com/watch?v=...",
  "imdbId": "tt0133093", "imdbUrl": "https://www.imdb.com/title/tt0133093/",
  "externalScores": {
    "imdbRating": 8.7, "imdbVotes": 2134567,
    "rottenTomatoesCritics": 83, "rottenTomatoesAudience": 85,
    "metacritic": 73, "letterboxd": 4.2,
    "fetchedAt": "2026-11-22T12:00:00Z", "source": "MDBList"
  },
  "userRating": {"score": 8.4, "reviewTitle": "...", "reviewText": "...", "seasons": null}
}
```

- `runtimeMinutes` is set for movies, `episodeRuntimeMinutes` + `numberOfSeasons` for shows.
- `externalScores` is `null` when the server has no `MDBLIST_API_KEY`, MDBList has nothing for the title, or it is
  rate limited; any single score inside it can be `null` too. Rotten Tomatoes and Metacritic are 0-100, IMDb 0-10, Letterboxd 0-5.
- `userRating` is `null` when you haven't rated the title. For shows, `score` is the average of your season ratings and
  `seasons` lists them: `[{season, score, reviewTitle, reviewText}]`.
- Extras are cached (TMDB data weekly; scores daily for titles out under 30 days, weekly under a year, monthly after),
  so values can lag the source a little. Errors: `invalid_type`, `invalid_parameter`, `not_found`, `tmdb_not_configured`, `tmdb_error`.

## Errors

Non-2xx responses are `{"error": "<code>", "message": "..."}`. Codes: `unauthorized` (401), `not_found` (404, also for other users' lists),
`invalid_json`, `invalid_parameter`, `invalid_type`, `invalid_name`, `list_limit` (400), `too_many_items` (413),
`tmdb_not_configured` (503), `tmdb_error` (502), `internal_error` (500).

## Example

```bash
export RATELOG_URL=http://localhost:8080 RATELOG_TOKEN=rl_...
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Heist classics","items":[{"title":"Heat","year":1995},{"title":"Rififi","year":1955},{"tmdbId":1422,"type":"movie"}]}' \
  "$RATELOG_URL/api/v1/lists"
```
