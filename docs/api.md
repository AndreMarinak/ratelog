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
| GET | `/api/v1/me` | | `{id, username, role}`; `role` is `USER`, `MANAGER`, `ADMIN` or `SUPERADMIN` |
| GET | `/api/v1/search` | `q`, optional `type`, `year` | `{results: [Candidate]}` (raw TMDB search) |
| POST | `/api/v1/resolve` | `{title, year?, type?}` | `{status: matched\|ambiguous\|not_found, match, candidates}` (dry run, saves nothing) |
| GET | `/api/v1/lists` | | your lists: `[{id, name, isPublic, isOfficial, itemCount, url, numbered}]` |
| GET | `/api/v1/lists/official` | | every official list, with your progress (see [Official lists](#official-lists)) |
| POST | `/api/v1/lists` | `{name, isPublic?, isOfficial?, numbered?, items?: [Item]}` | `201 {list, items: AddItemsResult \| null}` |
| GET | `/api/v1/lists/{id}` | | `{id, name, isPublic, isOfficial, numbered, url, items: [ListItem], progress: {seen, total}}` (see [List progress](#list-progress)). Your lists and official lists |
| PATCH | `/api/v1/lists/{id}` | `{name?, isPublic?, isOfficial?, numbered?}` | list detail |
| DELETE | `/api/v1/lists/{id}` | | `204` |
| POST | `/api/v1/lists/{id}/items` | `{items: [Item]}` | `AddItemsResult` |
| PATCH | `/api/v1/lists/{id}/items/{type}/{tmdbId}` | `{position}` | list detail (see [List order](#list-order)) |
| PUT | `/api/v1/lists/{id}/order` | `{items: [{tmdbId, type}]}` | list detail (see [List order](#list-order)) |
| DELETE | `/api/v1/lists/{id}/items/{type}/{tmdbId}` | | `204` |
| GET | `/api/v1/lists/{id}/suggestions` | optional `limit`, `skip` | titles that fit your list, best first (see [List suggestions](#list-suggestions)). Your own lists only |
| GET | `/api/v1/recommendations` | optional `type` (`movie`, `tv`, `all`), `limit` | "Recommended for you" from your ratings (see [Recommended for you](#recommended-for-you)) |
| GET | `/api/v1/stats` | optional `year` (`2025`, `all`; default this year) | your stats, the numbers of the Stats page (see [Stats](#stats)) |

**Item**: `{"tmdbId": 949, "type": "movie"}` or `{"title": "Heat", "year": 1995, "type": "movie"}`. Unknown extra fields are ignored.
Optional `"tags": ["heist", "comfort watch"]` adds those tags to the title (see [Tags](#tags)), whether it was just added or already in the list.
Items are appended in the order sent; an item with `"position": 3` is inserted at #3 instead (see [List order](#list-order)).
At most **100 items per request**; at most 50 lists per user.

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

- **ListItem**: `{position, tmdbId, type, title, year, posterUrl, status, score, seen}`. `position` is the 1-based place in the list
  (the rank the list page shows).
  `status` is your watch status (`want_to_watch`, `watching`, `watched`, `dropped`) or `null`; `score` is your rating or `null`.
- `seen` is `true` when its status is `watched`, or when you rated it: a movie with any status, a show only if it has no status (a show you're Watching, have on your watchlist or Dropped isn't seen, even with rated seasons). Watching, Want to watch and Dropped are otherwise not seen. Rating a show's season sets it to `watching`, and to `watched` once every aired season is rated.
- `progress.total` counts the items in the response. Items are in list order, and `position` is 1, 2, 3... with no gaps
  (before list order it was the stored sort key, which could have gaps after removals).
- To put the unseen ones on your watchlist from the API, pick the items with `status == null && !seen` and send them to
  `POST /api/v1/statuses` with `"status": "want_to_watch"`. Filter first: that endpoint **replaces** an existing status.
  The list page's "Add N unseen to watchlist" (in its "⋯" menu) applies the same filter and never changes an existing status.

### Official lists

Admin-curated lists that **every account** sees (Lists tab, Discover, `/lists/official`), each person tracking their own
"seen X of Y". Added in this fork. Only **public** official lists are shown to everyone; a private one is **hidden** (see below).

- **Reading:** any token can `GET /api/v1/lists/{id}` a public official list, whoever owns it; `items` and `progress` are the
  token user's own marks. Other people's ordinary lists, and their hidden official lists, are still `404`, public or not.
- **Listing:** `GET /api/v1/lists/official` returns every public one, most recently active first (created, or a title added):
  ```json
  [{"id": 12, "name": "AFI's 100 Years...100 Movies", "isPublic": true, "isOfficial": true, "itemCount": 100,
    "url": "/list/12", "owner": "admin", "progress": {"seen": 37, "total": 100}}]
  ```
  `GET /api/v1/lists` still returns only your own lists (each with `isOfficial`).
- **Marking:** `PATCH /api/v1/lists/{id}` with `{"isOfficial": true}` or `false`, or `"isOfficial": true` on `POST /api/v1/lists`.
  **Admin tokens only** (role ADMIN or SUPERADMIN, see `GET /api/v1/me`; MANAGER is not an admin role), and on **any** list, including other people's
  private ones. Anyone else changing it gets `403 forbidden` and nothing changes; sending the value it already has is fine
  for any token (so a `GET` response can be sent back as is). The web page has the same switch under the list title, for admins.
- **Hidden official lists (since 2026-11-23):** `isOfficial` and `isPublic` are independent. Marking or un-marking **never
  changes `isPublic`**; a private official list is hidden: only its owner can read it (the site shows them "Official ·
  Hidden"), and it is missing from `GET /api/v1/lists/official`, the Lists tab and Discover until it is made public. The owner
  hides or shows it with `PATCH {"isPublic": false}` / `{"isPublic": true}`. `POST` with `"isOfficial": true` and no `isPublic`
  creates a **hidden** one (private is the default for every list); send `"isPublic": true` to publish it at once. The old
  `400 official_list_public` error no longer exists.
- **Changing an official list** (name, visibility, items, delete) is still the owner's job: other tokens get `403 forbidden` on a
  public official list (not `404`, since everyone can read it) and `404` on a hidden one. An admin can un-mark someone else's list
  (hidden ones too) but not rename it. Deleting an official list removes it from the official lists too.

```bash
# Mark every list named in ids.txt official and public (admin token, your own lists). Lists show newest activity first.
# Without "isPublic": true a private list stays hidden.
while read -r id; do
  curl -s -X PATCH -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json" \
    -d '{"isOfficial": true, "isPublic": true}' "$RATELOG_URL/api/v1/lists/$id" > /dev/null
done < ids.txt
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/lists/official"

# Hide an official list for now (it stays official), then publish it again.
curl -s -X PATCH -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json" \
  -d '{"isPublic": false}' "$RATELOG_URL/api/v1/lists/12"
curl -s -X PATCH -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json" \
  -d '{"isPublic": true}' "$RATELOG_URL/api/v1/lists/12"
```

### List order

Every list has an order: the order you added titles in, until you change it. Added in this fork.

- **Numbered or plain:** `numbered: true` (the default, and every list created before this) shows each title's rank on the list
  page; `false` makes it a plain collection without numbers. The order is kept and editable either way, and `items` always come
  in list order with their `position`. Set it with `"numbered": false` on `POST /api/v1/lists` or `PATCH /api/v1/lists/{id}`
  (owner only, like the name). The web page has the same checkbox in the create / edit list forms.
- **Insert while adding:** an item with `position` (1-based) on `POST /api/v1/lists/{id}/items` or `POST /api/v1/lists` goes
  to that place and the rest shift down. Items are applied in the order sent, so `[{..., "position": 1}, {..., "position": 2}]`
  ends up first and second. A `position` past the end appends; `0` or less makes that item `invalid`. A title that is already
  in the list is reported in `alreadyInList` and **not moved** (use the endpoints below).
- **Move one title:** `PATCH /api/v1/lists/{id}/items/{type}/{tmdbId}` with `{"position": 3}` moves it to #3 (past the end =
  last). `position` is required and must be 1 or more (`400 invalid_parameter`); a title that isn't in the list is `404`.
- **Set the whole order:** `PUT /api/v1/lists/{id}/order` with `{"items": [{"tmdbId": 603, "type": "movie"}, ...]}`. Those
  titles come first, in that order; titles you leave out keep their order after them, so you can send just the new top 5.
  A title that isn't in the list is `400 not_in_list` (the message lists them), one sent twice is `400 invalid_parameter`,
  and in both cases nothing changes. Up to 1,000 titles per request (no TMDB lookups).
- Every change renumbers the list 1..n in one transaction, with the list locked, so two clients reordering at once don't lose
  moves. Only the owner reorders: an official list is readable by any token, but changes are `403 forbidden`; other people's
  lists are `404`. Both endpoints answer with the list detail (new `position`s included).
- The list page has the same tools for the owner ("Edit order": drag, type a number, or top / up / down / bottom) and a Sort
  menu for everyone, which only changes how that viewer sees the list (`/list/{id}?sort=title`, `reverse`, `newest`, `oldest`,
  `rating`, `added`); the API always returns list order.

```bash
# Restore Coco (354912) to #3, insert Up (14160) at #1, then make "Cars" (920) last.
curl -s -X PATCH -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json"   -d '{"position": 3}' "$RATELOG_URL/api/v1/lists/12/items/movie/354912"
curl -s -X POST -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json"   -d '{"items": [{"tmdbId": 14160, "type": "movie", "position": 1}]}' "$RATELOG_URL/api/v1/lists/12/items"
curl -s -X PATCH -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json"   -d '{"position": 1000}' "$RATELOG_URL/api/v1/lists/12/items/movie/920"

# A new top 3; everything else keeps its order after them.
curl -s -X PUT -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json"   -d '{"items": [{"tmdbId": 238, "type": "movie"}, {"tmdbId": 278, "type": "movie"}, {"tmdbId": 1396, "type": "tv"}]}'   "$RATELOG_URL/api/v1/lists/12/order"

# A plain (unnumbered) collection.
curl -s -X PATCH -H "Authorization: Bearer $RATELOG_TOKEN" -H "Content-Type: application/json"   -d '{"numbered": false}' "$RATELOG_URL/api/v1/lists/12"
```

### List suggestions

`GET /api/v1/lists/{id}/suggestions`: what "Suggested for this list" shows under your own list on the web page. Titles come from
the TMDB recommendations of up to 10 of the list's titles (all of them for lists of 10 or fewer; otherwise half the newest
additions and half spread over the list), scored by how many of them recommend a title and how high, times TMDB popularity and
rating. Missing entries of a movie series that two or more of the list's titles belong to come first, in release order. Titles
already in the list, posterless ones and released ones with fewer than 50 TMDB votes are left out. Movies and shows follow the
list's own mix. Seen titles are kept (`seen: true`). Added in this fork.

| Param | Values | Effect |
|---|---|---|
| `limit` | 1-50, default 20 | how many to return |
| `skip` | keys separated by commas: `movie:862,tv:1396` (or the web page's `m862.t1396`) | leave these out, to get the next ones; at most the last 300 count |

```json
{
  "listId": 12, "listSize": 25, "basedOn": 10, "remaining": 87,
  "suggestions": [
    {"tmdbId": 121, "type": "movie", "title": "The Lord of the Rings: The Two Towers", "year": 2002,
     "releaseDate": "2002-12-18", "posterUrl": "https://image.tmdb.org/t/p/w342/...", "url": "/movie/121",
     "tmdbVoteAverage": 8.4, "upcoming": false, "seen": false, "status": null, "userScore": null,
     "reason": "series", "recommendedBy": 2},
    {"tmdbId": 680, "type": "movie", "title": "Pulp Fiction", "year": 1994, "releaseDate": "1994-09-10",
     "posterUrl": "...", "url": "/movie/680", "tmdbVoteAverage": 8.5, "upcoming": false, "seen": true,
     "status": "watched", "userScore": 9.0, "reason": "recommended", "recommendedBy": 4}
  ]
}
```

- `reason` is `series` (an entry of a series the list has titles of) or `recommended`; `recommendedBy` counts the sampled list
  titles that recommend it. `remaining` is how many more there are after these (not in the list, not skipped).
- `seen`, `status` and `userScore` are yours (the poster eye's rule). Titles are in your metadata language. An empty list has no
  suggestions (`[]`).
- Adding one is the normal `POST /api/v1/lists/{id}/items` with `{"items": [{"tmdbId": 121, "type": "movie"}]}`.
- Nothing is saved. TMDB answers are cached (recommendations a day, collections a week) and the scored list for 6 hours, until
  the list changes.
- Errors: `invalid_parameter` (400: `limit`), `not_found` (404: no such list, or someone else's), `forbidden` (403: an official
  list you don't own), `tmdb_not_configured` (503), `tmdb_error` (502).

```bash
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/lists/12/suggestions?limit=10"
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/lists/12/suggestions?skip=movie:121,movie:680"
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
TV is rated per season, or (since 2026-11-23) as a whole with one score. A show's score is the average of its rated
seasons when at least one is rated, else its whole-show score. Scores go from 0 to 10.

| Method | Path | Body / params | Returns |
|---|---|---|---|
| GET | `/api/v1/ratings` | optional `type` (`movie` or `tv`) | `{movies: [MovieRating], tv: [TvRating]}`, highest score first. A list excluded by `type` is left out |
| GET | `/api/v1/ratings/movie/{tmdbId}` | | `MovieRating` |
| PUT | `/api/v1/ratings/movie/{tmdbId}` | `RatingBody` | `201` (new) or `200` (replaced) with `MovieRating` |
| DELETE | `/api/v1/ratings/movie/{tmdbId}` | | `204` |
| GET | `/api/v1/ratings/tv/{tmdbId}` | | `TvRating` (rated by season, as a whole, or both) |
| PUT | `/api/v1/ratings/tv/{tmdbId}` | `{"score": 8}` (0-10) | `201` (no whole-show score before) or `200` (changed) with `TvRating`. While seasons are rated it is stored but `score` stays their average |
| DELETE | `/api/v1/ratings/tv/{tmdbId}` | | `204`. Deletes only the whole-show score (season ratings stay); `404` when there is none |
| PUT | `/api/v1/ratings/tv/{tmdbId}/seasons/{season}` | `RatingBody` | `201` (new season) or `200` (replaced) with the whole `TvRating` |
| DELETE | `/api/v1/ratings/tv/{tmdbId}/seasons/{season}` | | `204`. Deleting the last rated season removes the show's rating, unless it has a whole-show score (that is its score again) |

**RatingBody**: quick `{"score": 8.5}`, or detailed `{"directing": 9, "cinematography": 8, "acting": 9, "soundtrack": 7, "screenplay": 8}`.
Optional `reviewTitle` (up to 100 characters) and `reviewText` (up to 5000).
- Send `score` alone or all five categories without `score`. Anything else (a score plus categories, or only some categories) is `400 invalid_rating`.
- PUT replaces the scores, so it also switches a rating between quick and detailed.
- On a PUT that replaces a rating, review fields you leave out keep their saved value. Send `""` to clear one.
- PUT saves the title from TMDB first if Ratelog doesn't have it yet (same as adding it to a list). `season` must be between 1 and the show's last season.

**MovieRating**: `{tmdbId, type: "movie", title, year, posterUrl, mode: "quick" | "detailed", score, categories, reviewTitle, reviewText, createdAt, url}`.
`categories` is `{directing, cinematography, acting, soundtrack, screenplay}`, or `null` for a quick rating.

**TvRating**: `{tmdbId, type: "tv", title, year, posterUrl, score, scoreSource, showScore, seasons: [{season, mode, score, categories, reviewTitle, reviewText, createdAt}], url}`.
`scoreSource` is `"seasons"` (`score` is the average of `seasons`) or `"show"` (`score` is the whole-show score, `seasons` is empty).
`showScore` is the whole-show score, or `null`; it is kept while seasons are rated (unused) and becomes `score` again when every
season rating is deleted. Rating a show as a whole marks it Watched, like rating a movie (a Dropped show stays Dropped).

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
- `externalScores` is `null` when no MDBList key is set (Admin → Configuration, or the `MDBLIST_API_KEY` variable), MDBList has nothing for the title, or it is
  rate limited; any single score inside it can be `null` too. Rotten Tomatoes and Metacritic are 0-100, IMDb 0-10, Letterboxd 0-5.
- `userRating` is `null` when you haven't rated the title. For shows, `score` is the average of your season ratings and
  `seasons` lists them: `[{season, score, reviewTitle, reviewText}]`; a show rated only as a whole has `seasons: []` and
  `score` = its whole-show score. Shows also get `showScore` (the whole-show score, or `null`; unused while seasons are rated).
- `inLists` names **your own** lists that contain the title, newest list first (`[]` when none); `rank` is its place in
  the list (1 = first, counted in list order like the list page, the same number as `position` in list responses), or `null` on a
  plain (unnumbered) list. Added in this fork.
- Extras are cached (TMDB data weekly; scores daily for titles out under 30 days, weekly under a year, monthly after),
  so values can lag the source a little. Errors: `invalid_type`, `invalid_parameter`, `not_found`, `tmdb_not_configured`, `tmdb_error`.

## Home

The home dashboard as JSON: the same rows as the web home page (`/`), in the same order: trending movies, trending TV, in theaters, coming soon, then your own rows (only when they have something in them).
Since 2026-11-23 there is no `popular_tv` (nor Classics) row: both moved to Discover. The page's "Recommended for you" row (after `up_next`)
is loaded by the page and isn't in this response: `GET /api/v1/recommendations` returns it (see [Recommended for you](#recommended-for-you)).

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
without an admin token, or changing someone else's public official list), `not_found` (404, also for other users' lists, except public official ones, and tags),
`invalid_json`, `invalid_parameter`, `invalid_type`, `invalid_name`, `invalid_color`, `list_limit`, `tag_limit`, `not_in_list` (400; `official_list_public` was removed 2026-11-23),
`tag_exists` (409), `too_many_items` (413), `rate_limited` (429),
`tmdb_not_configured` (503), `tmdb_error` (502), `internal_error` (500).

**Rate limit:** each token may send 600 reads (GET) and 60 writes (POST, PUT, PATCH, DELETE) a minute, plus a burst of 100
reads and 30 writes (the server's defaults, `RATELOG_RATE_LIMIT_*`). Over it, the answer is `429 rate_limited` with a
`Retry-After` header in seconds: wait that long and send the request again. Batching items (up to 100 per request) keeps a
big list import far below the limit.

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

## Recommended for you

`GET /api/v1/recommendations`: the "Recommended for you" row of Home (no `type`: movies and TV mixed) and Discover (`type`: that
mode only), from your ratings. Same list the pages show, in the same order.

| Param | Values | Effect |
|---|---|---|
| `type` | `movie`, `tv`, `all`; default `all` | only movies, only shows, or both (seeds and titles of both types) |
| `limit` | 1-60; default 20 | at most this many titles |

How the list is made:

1. **Seeds:** your titles rated **8 or more**, newest rating first (a show: its latest season rating, else its whole-show
   rating); with fewer than 3 of those, your best-rated titles instead. At most 5. With `all`, each type you rated gets a seed.
2. **Merge:** each seed's TMDB recommendations (page 1, the same ones as "More like this" on its page), taken round-robin
   (1st of every seed, then 2nd, ...); a title several seeds recommend appears once, `because` the first.
3. **Filter:** never anything you have rated or given any status (Watched, Watching, Dropped, or on your watchlist), no
   posterless titles, no released titles with fewer than 10 TMDB votes, never the seeds.

```json
{
  "type": "all",
  "seeds": [
    {"tmdbId": 603, "type": "movie", "title": "The Matrix", "score": 9.5},
    {"tmdbId": 1396, "type": "tv", "title": "Breaking Bad", "score": 9.0}
  ],
  "titles": [
    {"tmdbId": 604, "type": "movie", "title": "The Matrix Reloaded", "year": 2003, "releaseDate": "2003-05-15",
     "posterUrl": "https://image.tmdb.org/t/p/w342/...", "url": "/movie/604", "tmdbVoteAverage": 7.1, "upcoming": false,
     "because": {"tmdbId": 603, "type": "movie", "title": "The Matrix"}},
    {"tmdbId": 60059, "type": "tv", "title": "Better Call Saul", "year": 2015, "releaseDate": "2015-02-08",
     "posterUrl": "...", "url": "/tv/60059", "tmdbVoteAverage": 8.7, "upcoming": false,
     "because": {"tmdbId": 1396, "type": "tv", "title": "Breaking Bad"}}
  ]
}
```

- No ratings (of that type): `200` with empty `seeds` and `titles`.
- Titles are in your metadata language. Nothing is saved. TMDB answers are cached a day (shared with the title pages); the
  merged list is cached per user for 5 minutes, but your ratings and statuses are always read fresh, so a title you just rated
  or bookmarked is gone on the next call. A seed TMDB can't answer for is skipped.
- Errors: `invalid_type`, `invalid_parameter` (400), `tmdb_not_configured` (503), `tmdb_error` (502: TMDB failed for every seed).

```bash
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/recommendations"
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/recommendations?type=tv&limit=40"
```

## Stats

`GET /api/v1/stats?year=YYYY|all`: the numbers of the Stats page (`/stats`) for the token's own user, with the same rules.
No `year`: this year. Years are calendar years in the server's time zone (`timeZone`); a rating at 23:30 on Dec 31 counts
in that year.

What counts (a period is a year, or everything with `all`):

- **A movie** counts when you rated it in the period, or, unrated, when you marked it Watched in the period. It counts once,
  at its rating's time.
- **A show** counts when you rated seasons in the period: its score is the average of those seasons (the TV rule, limited to
  the period; with `all` it is the show's usual score) and it counts at its last rated season. A show with no rated season
  counts through its whole-show score, when you gave it in the period (a whole-show score is unused while any season is
  rated, so then it never counts). Unrated, a show counts when you marked it Watched in the period.
- `ratings` = movie ratings + season ratings + whole-show ratings (those in use) in the period. `averageScore` is over the
  rated titles (a show once, with its period score). `distribution` buckets those scores to the nearest point (7.5 counts as
  8; under 1.5 counts as 1).
- `hours` are movies only: TV episode counts aren't stored. Runtimes come from the cached title details; movies whose runtime
  isn't cached yet are looked up in the background (`pending`), so ask again a minute later for the full total.
- `genres` (most titles first): a title counts in each of its TMDB genres; movie and TV genres are separate ids (e.g. 878
  Science Fiction, 10765 Sci-Fi & Fantasy). `generousGenre` / `harshestGenre`: highest and lowest average among genres with
  at least 3 rated titles (both `null` unless two such genres differ). `decades` by release year, oldest first.
- `top`: your 10 highest rated titles of the period (score, then the most recent first).

```json
{
  "year": "2026",
  "years": [2026, 2025],
  "timeZone": "America/New_York",
  "totals": {"moviesSeen": 27, "moviesRated": 26, "showsSeen": 4, "showsRated": 3, "seasonsRated": 3, "ratings": 30, "averageScore": 8.39},
  "hours": {"hours": 61.5, "minutes": 3690, "moviesWithRuntime": 27, "pending": 0, "includesTv": false},
  "distribution": [{"score": 1, "count": 0}, "...", {"score": 10, "count": 4}],
  "timeline": [{"month": 1, "year": null, "movies": 2, "shows": 0, "total": 2}, "..."],
  "genres": [{"id": 18, "name": "Drama", "count": 14, "rated": 14, "averageScore": 8.65}, "..."],
  "decades": [{"decade": 1960, "count": 1, "rated": 1, "averageScore": 8.0}, "..."],
  "generousGenre": {"id": 80, "name": "Crime", "count": 9, "rated": 8, "averageScore": 9.06},
  "harshestGenre": {"id": 27, "name": "Horror", "count": 5, "rated": 5, "averageScore": 7.5},
  "top": [{"tmdbId": 278, "type": "movie", "title": "The Shawshank Redemption", "year": 1994,
           "posterUrl": "https://image.tmdb.org/t/p/w342/...", "url": "/movie/278", "score": 10.0,
           "ratedAt": "2026-04-23T00:00:00Z"}, "..."]
}
```

- `timeline` has the 12 months of the year (`month`), or with `all` every year from the first to the last with data
  (`year`, empty years included).
- `years` lists the years that have something, newest first (the page's year switcher adds this year and "All time").
- A year with nothing: `200` with zeros, `averageScore: null`, empty `genres` / `decades` / `top`.
- Only your own stats; there is no way to read another user's (a possible follow-up).
- Errors: `invalid_year` (400: not `all` or a year from 1900 to this year).

```bash
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/stats"
curl -s -H "Authorization: Bearer $RATELOG_TOKEN" "$RATELOG_URL/api/v1/stats?year=all"
```
