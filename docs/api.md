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
| GET | `/api/v1/lists` | | your lists: `[{id, name, isPublic, isOfficial, itemCount, url}]` |
| GET | `/api/v1/lists/official` | | every official list, with your progress (see [Official lists](#official-lists)) |
| POST | `/api/v1/lists` | `{name, isPublic?, isOfficial?, items?: [Item]}` | `201 {list, items: AddItemsResult \| null}` |
| GET | `/api/v1/lists/{id}` | | `{id, name, isPublic, isOfficial, url, items: [ListItem], progress: {seen, total}}` (see [List progress](#list-progress)). Your lists and official lists |
| PATCH | `/api/v1/lists/{id}` | `{name?, isPublic?, isOfficial?}` | list detail |
| DELETE | `/api/v1/lists/{id}` | | `204` |
| POST | `/api/v1/lists/{id}/items` | `{items: [Item]}` | `AddItemsResult` |
| DELETE | `/api/v1/lists/{id}/items/{type}/{tmdbId}` | | `204` |

**Item**: `{"tmdbId": 949, "type": "movie"}` or `{"title": "Heat", "year": 1995, "type": "movie"}`. Unknown extra fields are ignored.
Optional `"tags": ["heist", "comfort watch"]` adds those tags to the title (see [Tags](#tags)), whether it was just added or already in the list.
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
An item whose tags can't be applied (bad tag name, tag limit reached) is `invalid` and is not added.

**Candidate**: `{tmdbId, type, title, originalTitle, year, tmdbVoteAverage, overview}`.

### List progress

List detail responses (`GET`, `PATCH` and the `list` in `POST /api/v1/lists`) say how much of the list **you** (the token's user) have seen:

```json
{
  "id": 1, "name": "Best Disney Movies - Top 50", "isPublic": true, "isOfficial": false, "url": "/list/1",
  "items": [
    {"position": 1, "tmdbId": 354912, "type": "movie", "title": "Coco", "year": 2017, "posterUrl": "...",
     "status": "watched", "score": 9.0, "seen": true},
    {"position": 2, "tmdbId": 14160, "type": "movie", "title": "Up", "year": 2009, "posterUrl": "...",
     "status": null, "score": null, "seen": false}
  ],
  "progress": {"seen": 1, "total": 2}
}
```

- **ListItem**: `{position, tmdbId, type, title, year, posterUrl, status, score, seen}`. The first six fields are unchanged.
  `status` is your watch status (`want_to_watch`, `watching`, `watched`, `dropped`) or `null`; `score` is your rating or `null`.
- `seen` is `true` when its status is `watched`, or when you rated it: a movie with any status, a show only if it has no status (a show you're Watching, have on your watchlist or Dropped isn't seen, even with rated seasons). Watching, Want to watch and Dropped are otherwise not seen. Rating a show's season sets it to `watching`, and to `watched` once every aired season is rated.
- `progress.total` counts the items in the response. Items are in list order; `position` can have gaps after removals.
- To put the unseen ones on your watchlist from the API, pick the items with `status == null && !seen` and send them to
  `POST /api/v1/statuses` with `"status": "want_to_watch"`. Filter first: that endpoint **replaces** an existing status.
  The list page's "Add the N unseen to my watchlist" button applies the same filter and never changes an existing status.

### Official lists

Admin-curated lists that **every account** sees (Lists tab, Discover, `/lists/official`), each person tracking their own
"seen X of Y". Added in this fork. Official lists are always public.

- **Reading:** any token can `GET /api/v1/lists/{id}` an official list, whoever owns it; `items` and `progress` are the
  token user's own marks. Other people's ordinary lists are still `404`, public or not.
- **Listing:** `GET /api/v1/lists/official` returns all of them, most recently active first (created, or a title added):
  ```json
  [{"id": 12, "name": "AFI's 100 Years...100 Movies", "isPublic": true, "isOfficial": true, "itemCount": 100,
    "url": "/list/12", "owner": "admin", "progress": {"seen": 37, "total": 100}}]
  ```
  `GET /api/v1/lists` still returns only your own lists (each with `isOfficial`).
- **Marking:** `PATCH /api/v1/lists/{id}` with `{"isOfficial": true}` or `false`, or `"isOfficial": true` on `POST /api/v1/lists`.
  **Admin tokens only** (role ADMIN or SUPERADMIN, see `GET /api/v1/me`), and on **any** list, including other people's
  private ones. Anyone else changing it gets `403 forbidden` and nothing changes; sending the value it already has is fine
  for any token (so a `GET` response can be sent back as is). The web page has the same switch under the list title, for admins.
- **Official implies public:** marking makes the list public. While it is official it can't be made private: `isPublic: false`
  is `400 official_list_public` (send `{"isOfficial": false, "isPublic": false}` to do both; un-marking runs first), and the
  web edit form keeps it public. Un-marking leaves it public; the owner can make it private again.
- **Changing an official list** (name, visibility, items, delete) is still the owner's job: other tokens get `403 forbidden`
  (not `404`, since everyone can read it). An admin can un-mark someone else's list but not rename it. Deleting an official
  list removes it from the official lists too.

```bash
# Mark every list named in ids.txt official (admin token). Lists show newest activity first.
while read -r id; do
  curl -s -X PATCH -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json" \
    -d '{"isOfficial": true}' "$RATELOG_URL/api/v1/lists/$id" > /dev/null
done < ids.txt
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/lists/official"
```

## Tags

Your own labels for titles ("comfort watch", "mind-benders"). Tags are private to your account and are shown on title pages,
on `/tags`, and as a filter on the Top movies / Top TV pages.

- **Names**: 1-40 characters, trimmed, inner whitespace collapsed. Unique per user **ignoring case**; wherever tags are given by
  name, an existing tag is matched ignoring case and a new name creates the tag.
- **Colors** (optional): `clay`, `amber`, `green`, `teal`, `blue`, `violet`, `pink`, `gray`. `""` or `"none"` means no color.
- At most 200 tags per user; at most 50 tags in one `PUT`.

| Method | Path | Body / params | Returns |
|---|---|---|---|
| GET | `/api/v1/tags` | | `[Tag]` sorted by name |
| POST | `/api/v1/tags` | `{name, color?}` | `201 Tag` (`409 tag_exists` if you already have it) |
| PATCH | `/api/v1/tags/{id}` | `{name?, color?}` (omitted = unchanged) | `Tag` |
| DELETE | `/api/v1/tags/{id}` | | `204` (the tag is removed from every title) |
| GET | `/api/v1/tags/{id}/titles` | | `{tag: Tag, titles: [{tmdbId, type, title, year, posterUrl, score}]}` sorted by title; `score` is your rating or `null` |
| GET | `/api/v1/titles/{type}/{tmdbId}/tags` | | `TitleTags` (`title: null`, `tags: []` if Ratelog has never seen the title) |
| PUT | `/api/v1/titles/{type}/{tmdbId}/tags` | `{"tags": ["comfort watch", "mind-benders"]}` | `TitleTags` |

**Tag**: `{id, name, color, titleCount, url}` (`url` is the web page, `/tags/{id}`).

**TitleTags**: `{tmdbId, type, title, tags: [Tag], created: [name]}`. `created` lists tags the `PUT` had to create.

`PUT` **replaces** the title's tags with exactly the given list (`[]` clears them) and imports the title from TMDB first if needed,
like adding it to a list. To add one tag without dropping the others, `GET` the title's tags, append, and `PUT` the result,
or pass `tags` on list items. Other users' tags are never visible: their ids come back as `404`.

```bash
curl -s -X PUT -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json" \
  -d '{"tags":["comfort watch","date night"]}' "$RATELOG_URL/api/v1/titles/movie/116149/tags"
```

## Ratings

A rating is **quick** (one overall `score`) or **detailed** (the five categories; its score is their average).
TV is rated per season, and a show's score is the average of its rated seasons. Scores go from 0 to 10.

| Method | Path | Body / params | Returns |
|---|---|---|---|
| GET | `/api/v1/ratings` | optional `type` (`movie` or `tv`) | `{movies: [MovieRating], tv: [TvRating]}`, highest score first. A list excluded by `type` is left out |
| GET | `/api/v1/ratings/movie/{tmdbId}` | | `MovieRating` |
| PUT | `/api/v1/ratings/movie/{tmdbId}` | `RatingBody` | `201` (new) or `200` (replaced) with `MovieRating` |
| DELETE | `/api/v1/ratings/movie/{tmdbId}` | | `204` |
| GET | `/api/v1/ratings/tv/{tmdbId}` | | `TvRating` |
| PUT | `/api/v1/ratings/tv/{tmdbId}/seasons/{season}` | `RatingBody` | `201` (new season) or `200` (replaced) with the whole `TvRating` |
| DELETE | `/api/v1/ratings/tv/{tmdbId}/seasons/{season}` | | `204`. Deleting the last rated season removes the show's rating |

**RatingBody**: quick `{"score": 8.5}`, or detailed `{"directing": 9, "cinematography": 8, "acting": 9, "soundtrack": 7, "screenplay": 8}`.
Optional `reviewTitle` (up to 100 characters) and `reviewText` (up to 5000).
- Send `score` alone or all five categories without `score`. Anything else (a score plus categories, or only some categories) is `400 invalid_rating`.
- PUT replaces the scores, so it also switches a rating between quick and detailed.
- On a PUT that replaces a rating, review fields you leave out keep their saved value. Send `""` to clear one.
- PUT saves the title from TMDB first if Ratelog doesn't have it yet (same as adding it to a list). `season` must be between 1 and the show's last season.

**MovieRating**: `{tmdbId, type: "movie", title, year, posterUrl, mode: "quick" | "detailed", score, categories, reviewTitle, reviewText, createdAt, url}`.
`categories` is `{directing, cinematography, acting, soundtrack, screenplay}`, or `null` for a quick rating.

**TvRating**: `{tmdbId, type: "tv", title, year, posterUrl, score, seasons: [{season, mode, score, categories, reviewTitle, reviewText, createdAt}], url}`.

Rating errors: `invalid_rating`, `invalid_score`, `invalid_review`, `invalid_season` (400), `not_found` (404: TMDB doesn't know the id, or you haven't rated it),
`conflict` (409: another request created the same rating at the same moment; retry).

```bash
curl -s -X PUT -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json" \
  -d '{"score": 8.5, "reviewTitle": "Still the best"}' "$RATELOG_URL/api/v1/ratings/movie/949"
```

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
  "userRating": {"score": 8.4, "reviewTitle": "...", "reviewText": "...", "seasons": null},
  "inLists": [{"id": 4, "name": "Sci-fi canon", "rank": 3, "url": "/list/4"}]
}
```

- `runtimeMinutes` is set for movies, `episodeRuntimeMinutes` + `numberOfSeasons` for shows.
- `externalScores` is `null` when the server has no `MDBLIST_API_KEY`, MDBList has nothing for the title, or it is
  rate limited; any single score inside it can be `null` too. Rotten Tomatoes and Metacritic are 0-100, IMDb 0-10, Letterboxd 0-5.
- `userRating` is `null` when you haven't rated the title. For shows, `score` is the average of your season ratings and
  `seasons` lists them: `[{season, score, reviewTitle, reviewText}]`.
- `inLists` names **your own** lists that contain the title, newest list first (`[]` when none); `rank` is its place in
  the list (1 = first, counted in list order like the list page, so unlike `position` it has no gaps). Added in this fork.
- Extras are cached (TMDB data weekly; scores daily for titles out under 30 days, weekly under a year, monthly after),
  so values can lag the source a little. Errors: `invalid_type`, `invalid_parameter`, `not_found`, `tmdb_not_configured`, `tmdb_error`.

## Home

The home dashboard as JSON: the same rows as the web home page (`/`), in the same order: trending movies, trending TV, in theaters, coming soon, then your own rows (only when they have something in them),
then popular TV.

| Method | Path | Body / params | Returns |
|---|---|---|---|
| GET | `/api/v1/home` | | `{rows: [Row], tmdb: "ok" \| "not_configured" \| "unavailable"}` |

**Row**: `{id, title, personal, titles: [HomeTitle]}`, or for `your_lists`: `{id, title, personal, lists: [HomeList]}`. At most 20 entries per row.
Row ids, in this order:

| id | What |
|---|---|
| `continue_watching` | Your titles with status `watching`, latest change first |
| `up_next` | Your `want_to_watch` titles, latest change first |
| `recently_rated` | Your ratings, newest first (a show once, at its latest season rating) |
| `your_lists` | Your lists, public and private, most recently changed first |
| `trending_movies`, `trending_tv` | TMDB trending this week |
| `in_theaters` | TMDB now playing in your region |
| `coming_soon` | TMDB upcoming in your region, future release dates only, soonest first |
| `popular_tv` | TMDB popular TV |

**HomeTitle**: `{tmdbId, type, title, year, posterUrl, status, score, releaseDate}`. `status` (`want_to_watch`, `watching`, `watched`, `dropped`) and `score`
are yours, `null` when you have none. `releaseDate` is an ISO date: TMDB's on TMDB rows, the stored one on your own rows.

**HomeList**: `{id, name, isPublic, itemCount, url, posterUrls}`. `posterUrls` are the first (up to 4) posters, in list order.

TMDB rows use your metadata language; the region of that language (en: US, fr: FR, ...) picks what is in theaters and coming soon.
They are cached for an hour. If TMDB fails, its rows are left out and `tmdb` says `unavailable` (`not_configured` without an API key);
the request itself never fails because of TMDB.

```bash
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/home"
```

## Browse / Discover

`GET /api/v1/browse` (or its alias `GET /api/v1/discover`, same parameters and response) lists TMDB titles by filters,
like the web page's poster grid at `/discover` (formerly `/browse`, which now redirects there). The parameters are the
page's, so a `/discover?...` link's query string works here too, with one difference: **the page hides titles you've
seen by default, the API only with `hideSeen=true`**. Every parameter is optional; any invalid value is a `400`.

| Param | Values | Notes |
|---|---|---|
| `type` | `movie` (default), `tv` | `400 invalid_type` otherwise |
| `decade` | `2010s` (or `2010`), `1870s`-`2030s`, `earlier` (= up to 1959) | |
| `year` | `1874`-(this year + 10), or `upcoming` (movies only: not released yet) | a `year` outside `decade` replaces it |
| `minRating` | number above 0 and below 10, e.g. `7` or `7.5` | TMDB vote average floor; needs 100 votes (movies) / 50 (TV) |
| `genre` | TMDB genre id (`878`) or slug (`science-fiction`, `horror`, `action-adventure`...) | a genre from the other type maps to the nearest one (`action` on TV = Action & Adventure); none = `400` |
| `provider` | TMDB watch provider id (`8` Netflix, `337` Disney+, `9` Prime Video...) | streaming (flatrate) in your region, from your metadata language (`en` = US, `es` = ES...) |
| `language` | ISO 639-1 original language (`ko`, `ja`, `fr`...) | |
| `runtime` | `short` (40-89 min), `medium` (90-150), `long` (over 150) | movies only |
| `popular` | `week` (TMDB trending this week), `now` (by popularity), `alltime` (most voted) | overrides `sort` |
| `sort` | `popularity` (default), `rating` (needs 300 votes for movies / 200 for TV), `newest` (up to today), `oldest`, `title` | |
| `hideRated`, `hideWatchlist` | `true` / `false` (`1`/`0`) | leave out titles you rated / that are on your Want to watch list |
| `hideSeen` | `true` / `false` (`1`/`0`); API default `false` | leave out titles you've seen: rated (any season, for TV) or marked Watched. The `/discover` page defaults to `true` and writes `hideSeen=0` when showing them |
| `page` | `1`-`500` | |

```json
{
  "type": "movie",
  "filters": {"decade": "1990s", "year": null, "minRating": 7.0, "genre": "science-fiction", "genreId": 878, "provider": null,
              "language": null, "runtime": null, "popular": null, "sort": "rating", "hideRated": false, "hideWatchlist": false,
              "hideSeen": false},
  "page": 1, "nextPage": 2, "totalPages": 37, "totalResults": 731,
  "ignored": [],
  "results": [{"tmdbId": 603, "type": "movie", "title": "The Matrix", "year": 1999, "posterUrl": "https://image.tmdb.org/t/p/w342/...",
               "tmdbVoteAverage": 8.2, "url": "/movie/603", "userScore": 9.0, "status": "watched"}]
}
```

- Ask for `nextPage` to continue; it is `null` at the end. When `hideSeen` / `hideRated` / `hideWatchlist` (or `popular=week` plus filters)
  hide titles and fewer than 12 are left, the next TMDB pages top the response up (up to 3 pages), so `nextPage` may skip numbers.
  Results are about 20 per page, fewer when hidden.
- `popular=week` reads TMDB's trending list: `genre`, `language`, `decade`/`year` and `minRating` are checked on its results, but
  `provider` and `runtime` can't be, and come back in `ignored` (so does a `sort` or `decade` overridden by another parameter).
- `userScore` / `status` are your own rating and watch status (null if none). Titles are titled in your metadata language.
- TMDB pages are cached for about 45 minutes. Errors: `invalid_type`, `invalid_parameter` (400), `tmdb_not_configured` (503), `tmdb_error` (502).

```bash
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/browse?type=movie&decade=1980s&genre=horror&minRating=7&hideRated=true"
# Same endpoint under the new name, leaving out everything you've already seen:
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/discover?type=tv&genre=crime&hideSeen=true"
```

## Errors

Non-2xx responses are `{"error": "<code>", "message": "..."}`. Codes: `unauthorized` (401), `forbidden` (403: changing `isOfficial`
without an admin token, or changing someone else's official list), `not_found` (404, also for other users' lists, except official ones, and tags),
`invalid_json`, `invalid_parameter`, `invalid_type`, `invalid_name`, `invalid_color`, `list_limit`, `tag_limit`, `official_list_public` (400),
`tag_exists` (409), `too_many_items` (413),
`tmdb_not_configured` (503), `tmdb_error` (502), `internal_error` (500).

## Example

```bash
export RATELOG_URL=http://localhost:8080 RATELOG_TOKEN=rl_...
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Heist classics","items":[{"title":"Heat","year":1995},{"title":"Rififi","year":1955},{"tmdbId":1422,"type":"movie"}]}' \
  "$RATELOG_URL/api/v1/lists"
```

## Statuses (watchlist)

Each title can have one status per user: `want_to_watch`, `watching`, `watched` or `dropped` (or none).
Also accepted on input: `WANT_TO_WATCH`, `want-to-watch`, `watchlist`, `plan_to_watch` (= want_to_watch), `seen`, `completed` (= watched).
Statuses are separate from following a title (premiere tracking). Rating a title in Ratelog sets it to `watched` automatically
unless it is `dropped`. The web page is `/watchlist`.

| Method | Path | Body / params | Returns |
|---|---|---|---|
| GET | `/api/v1/statuses` | optional `status`, `type` | `{items: [StatusItem], counts: {want_to_watch, watching, watched, dropped}}` |
| PUT | `/api/v1/statuses/{type}/{tmdbId}` | `{status}` | `StatusChange` (the title is saved locally first if needed) |
| DELETE | `/api/v1/statuses/{type}/{tmdbId}` | | `204`, or `404` if the title has no status |
| POST | `/api/v1/statuses` | `{status?, items: [StatusItemInput]}` | `BulkStatusResult` |

**StatusItem**: `{tmdbId, type, title, year, posterUrl, status, score, updatedAt}`. `score` is your own score (null if unrated); newest first.
`counts` respects the `type` filter.

**StatusChange**: `{tmdbId, type, title, year, posterUrl, status, previousStatus}`.

**StatusItemInput**: an Item (see above: `{tmdbId, type}` or `{title, year?, type?}`) plus `status`. The top-level `status` is the default
for items without one. Same strict title matching as list items; at most **100 items per request**.

**BulkStatusResult**:
```json
{
  "updated":    [{"tmdbId": 949, "type": "movie", "title": "Heat", "year": 1995, "posterUrl": "...", "status": "want_to_watch", "previousStatus": null}],
  "unchanged":  [],
  "unresolved": [{"input": {...}, "reason": "ambiguous", "message": "...", "candidates": [Candidate]}],
  "summary": "1 updated, 0 unchanged, 1 unresolved"
}
```
`unchanged` means the title already had that status. `reason` is one of `not_found`, `ambiguous`, `invalid` (bad type/id/status, or no status), `tmdb_error`.
One bad item never fails the batch. An unknown `status` query parameter or body field is a `400 invalid_status`.

```bash
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json" \
  -d '{"status":"want_to_watch","items":[{"title":"Heat","year":1995},{"tmdbId":1396,"type":"tv","status":"watching"}]}' \
  "$RATELOG_URL/api/v1/statuses"
curl -s -X PUT -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json" \
  -d '{"status":"dropped"}' "$RATELOG_URL/api/v1/statuses/movie/949"
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/statuses?status=want_to_watch&type=movie"
```

## Recommendations

`GET /api/v1/titles/{type}/{tmdbId}/recommendations`: what a title page shows under the title. Movies in a TMDB collection get
`series` (every entry, in release order); movies and shows get `moreLikeThis` (TMDB recommendations, up to 20). The title itself and
its series entries are left out of `moreLikeThis`, as are posterless titles and released titles with fewer than 10 TMDB votes.

| Param | Values | Effect |
|---|---|---|
| `hideSeen` | `true` / `false` (`1`/`0`); default `false` | leave titles you've seen out of `moreLikeThis` (the web page does). The series always lists every entry |

```json
{
  "tmdbId": 863, "type": "movie",
  "series": {
    "collectionId": 10194, "name": "Toy Story Collection", "seen": 2, "released": 5,
    "titles": [
      {"tmdbId": 862, "type": "movie", "title": "Toy Story", "year": 1995, "releaseDate": "1995-11-22",
       "posterUrl": "https://image.tmdb.org/t/p/w342/...", "url": "/movie/862", "tmdbVoteAverage": 8.0,
       "upcoming": false, "seen": true, "status": "watched", "userScore": 9.0, "current": false},
      {"tmdbId": 863, "type": "movie", "title": "Toy Story 2", "year": 1999, "releaseDate": "1999-10-30",
       "posterUrl": "...", "url": "/movie/863", "tmdbVoteAverage": 7.6,
       "upcoming": false, "seen": true, "status": "watched", "userScore": null, "current": true}
    ]
  },
  "moreLikeThis": [
    {"tmdbId": 585, "type": "movie", "title": "Monsters, Inc.", "year": 2001, "releaseDate": "2001-11-01",
     "posterUrl": "...", "url": "/movie/585", "tmdbVoteAverage": 7.8,
     "upcoming": false, "seen": false, "status": null, "userScore": null, "current": null}
  ]
}
```

- `series` is `null` for shows and for movies that aren't in a collection of two or more. `seen` / `released` count entries that
  are out (upcoming and undated ones can't be seen yet); `current` marks the requested title.
- `seen` per title follows the poster eye: a movie you rated or marked Watched; a show marked Watched, or rated with no status.
  `status` and `userScore` are your own (null if none). Titles are in your metadata language.
- Nothing is saved locally. TMDB answers are cached (recommendations a day, collections a week).
- Errors: `invalid_type`, `invalid_parameter` (400), `not_found` (404: TMDB has no such title), `tmdb_not_configured` (503), `tmdb_error` (502).

```bash
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/titles/movie/863/recommendations"
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/titles/tv/1396/recommendations?hideSeen=true"
```
