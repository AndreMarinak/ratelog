# Ratelog API guide for AI assistants

Ratelog is a self-hosted, Letterboxd-style app for rating movies and TV, keeping ranked lists, a watchlist and stats.
Titles come from TMDB. This guide lets you act for the user through its JSON API.

- Base URL: `{{BASE_URL}}`
- Every endpoint below is under `{{BASE_URL}}/api/v1`. Bodies and answers are JSON (`Content-Type: application/json`).

## Authentication

Send the user's token on every request:

    Authorization: Bearer <YOUR_TOKEN>

Replace `<YOUR_TOKEN>` with the token the user gives you (it starts with `rl_`). If you have not been given one, ask for it
and never make one up. Do not print the token back or put it in a list, a review or a URL. Test it with `GET /api/v1/me`
(answers `{id, username, role}`). A missing or revoked token gives `401 unauthorized`.

## How titles are identified

- By **TMDB id + type**: `{"tmdbId": 949, "type": "movie"}`. Type is `movie` or `tv`. Movie and TV ids are separate on TMDB,
  so `type` is always required with `tmdbId`.
- Or by **title + year + type**: `{"title": "Heat", "year": 1995, "type": "movie"}`. Always send the release year; without
  it remakes and same-name titles come back ambiguous.
- Matching is strict: the exact title (ignoring case, accents, punctuation, a leading The/A/An) must match, and so must the
  year (or be within one year when only one title fits). Nothing is guessed.
- What cannot be placed comes back in `unresolved`: `{input, reason, message, candidates}` where `reason` is `not_found`,
  `ambiguous`, `invalid` or `tmdb_error`, and `candidates` are `{tmdbId, type, title, originalTitle, year, tmdbVoteAverage, overview}`.
  Pick the right one and send it again as `{"tmdbId": ..., "type": ...}`. If none is clearly right, do not guess: tell the user.
- Look titles up with `GET /api/v1/search?q=Heat&type=movie&year=1995` (`{results: [Candidate]}`), or the dry run
  `POST /api/v1/resolve` with `{"title": "Heat", "year": 1995, "type": "movie"}` (`{status: matched|ambiguous|not_found, match, candidates}`).

## Job 1: build a ranked list from research

1. Research the list. For each entry note **title + release year + type**.
2. Create it. Up to **100 items per request**, at most 50 lists per user. Items keep the order you send (rank order):

       POST /api/v1/lists
       {"name": "Heist classics", "isPublic": false, "numbered": true,
        "items": [{"title": "Heat", "year": 1995, "type": "movie"}, {"title": "Rififi", "year": 1955, "type": "movie"}]}

   Answer `201`: `{list: {id, name, url, ...}, items: AddItemsResult}`. Lists are private unless `"isPublic": true`.
   `"numbered": false` makes a plain collection without rank numbers (the order is still kept).
3. More than 100 titles: send the rest in batches to `POST /api/v1/lists/{id}/items` with `{"items": [...]}`.
4. Read the result. `AddItemsResult` is `{added, alreadyInList, unresolved, summary}`. One bad item never fails the batch.
   Fix everything in `unresolved` (see above) and add it with `POST /api/v1/lists/{id}/items`.
5. Tell the user the summary, anything you could not place, and the list link: `{{BASE_URL}}/list/{id}`.

## Job 2: read and fix a list

- `GET /api/v1/lists` your lists: `[{id, name, isPublic, isOfficial, itemCount, url, numbered}]`.
- `GET /api/v1/lists/{id}` the list with `items` in order (each has its `position`, `seen`, `status`, `score`) and
  `progress: {seen, total}`.
- `PATCH /api/v1/lists/{id}` with any of `{name, isPublic, numbered}`. `DELETE /api/v1/lists/{id}` answers `204`.
- Insert while adding: an item with `"position": 1` goes to #1 and the rest shift down (1-based; past the end appends).
- Move one title: `PATCH /api/v1/lists/{id}/items/{type}/{tmdbId}` with `{"position": 3}`.
- Set the order: `PUT /api/v1/lists/{id}/order` with `{"items": [{"tmdbId": 238, "type": "movie"}, ...]}`. Those come first in
  that order and the titles you leave out keep their order after them, so you can send only a new top 5. A title that is not
  in the list is `400 not_in_list` and nothing changes.
- Remove a title: `DELETE /api/v1/lists/{id}/items/{type}/{tmdbId}` (`204`).
- `GET /api/v1/lists/{id}/suggestions?limit=20` titles that fit your list, best first (`skip=movie:121,tv:1396` for the next page).

## Job 3: ratings

Scores go from 0 to 10. Read: `GET /api/v1/ratings` (optional `type=movie|tv`) gives `{movies: [...], tv: [...]}`, highest first;
`GET /api/v1/ratings/movie/{tmdbId}` or `/tv/{tmdbId}` for one title (`404 not_found` if it is not rated).

Rate a movie (`201` when new, `200` when replaced). Quick:

    PUT /api/v1/ratings/movie/949
    {"score": 8.5, "reviewTitle": "Still the best", "reviewText": "..."}

or detailed (all five categories and no `score`; the score is their average):

    {"directing": 9, "cinematography": 8, "acting": 9, "soundtrack": 7, "screenplay": 8}

Send `score` alone **or** all five categories, never a mix (`400 invalid_rating`). `reviewTitle` is up to 100 characters,
`reviewText` up to 5000. A PUT that replaces a rating keeps review fields you leave out; send `""` to clear one.
`DELETE /api/v1/ratings/movie/{tmdbId}` removes it (`204`).

Rate a TV **season**: `PUT /api/v1/ratings/tv/{tmdbId}/seasons/{season}` with the same body (season 1 or more, up to the
show's last season). Rate a **whole show** with one score: `PUT /api/v1/ratings/tv/{tmdbId}` with `{"score": 8}`.
A show's score is the average of its rated seasons when there are any, else its whole-show score. Deletes:
`DELETE /api/v1/ratings/tv/{tmdbId}/seasons/{season}`, and `DELETE /api/v1/ratings/tv/{tmdbId}` (whole-show score only).
Rating a title also marks it Watched.

## Job 4: watchlist and statuses

Statuses: `want_to_watch`, `watching`, `watched`, `dropped`. The watchlist is separate: any title can be on it, even a
watched one (a rewatch). Responses carry `status` and `onWatchlist`.

- `PUT /api/v1/watchlist/{type}/{tmdbId}` adds to the watchlist, `DELETE` removes it (`404` if it was not on it).
- `PUT /api/v1/statuses/{type}/{tmdbId}` with `{"status": "watching"}`; `DELETE` clears status and watchlist.
- `GET /api/v1/statuses?status=want_to_watch&type=movie` lists them, with `counts`.
- Many at once (max 100, same matching as lists):

      POST /api/v1/statuses
      {"status": "want_to_watch", "items": [{"title": "Heat", "year": 1995, "type": "movie"}, {"tmdbId": 1396, "type": "tv", "status": "watching"}]}

  Answer: `{updated, unchanged, unresolved, summary}`.

## Job 5: official and saved lists

Official lists are curated by the site's admin and shown to everyone, each person tracking their own "seen X of Y".

- `GET /api/v1/lists/official` every official list with your progress, the ones you saved first.
- `GET /api/v1/lists/{id}` reads one; `items` and `progress` are the user's own marks.
- `PUT /api/v1/lists/{id}/saved` saves it, `DELETE` un-saves it (`{id, saved, changed}`). `GET /api/v1/lists/saved` lists them.
- Only the owner changes a list: edits to someone else's official list answer `403 forbidden`.

## Job 6: recommendations

- `GET /api/v1/recommendations?type=all&limit=20` ("Recommended for you", from the user's ratings of 8 or more):
  `{seeds, titles}`; each title says what it is `because` of. Empty when nothing is rated.
- `GET /api/v1/titles/{type}/{tmdbId}/recommendations?hideSeen=true` for one title: `{series, moreLikeThis}`.

## Job 7: stats

`GET /api/v1/stats` (this year), `?year=2025` or `?year=all`: `{totals, hours, distribution, timeline, genres, decades,
generousGenre, harshestGenre, top, years}` for the user's own ratings. Hours count movies only. A bad year is `400 invalid_year`.

## Errors and limits

- Errors are `{"error": "<code>", "message": "..."}`. Common codes: `unauthorized` 401, `forbidden` 403, `not_found` 404
  (also for other people's lists), `invalid_json` / `invalid_parameter` / `invalid_type` / `invalid_rating` / `invalid_score`
  400, `too_many_items` 413 (over 100 items), `rate_limited` 429, `tmdb_error` 502, `tmdb_not_configured` 503.
- Rate limit: each token may send 600 reads and 60 writes a minute (plus a short burst). On `429` wait the `Retry-After`
  seconds and send the same request again. Batching items (up to 100 per request) keeps you far below it.

## Rules for you

- Ask the user about any title you are unsure of rather than guessing; report every `unresolved` title.
- Look before you change: read a list or rating before editing or deleting it, and delete only when asked.
- Never invent ids. Use `search` or the `candidates` you were given.
- Keep the token secret. Finish by telling the user what changed, with links like `{{BASE_URL}}/list/{id}`.
