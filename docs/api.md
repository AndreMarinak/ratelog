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

## Errors

Non-2xx responses are `{"error": "<code>", "message": "..."}`. Codes: `unauthorized` (401), `not_found` (404, also for other users' lists and tags),
`invalid_json`, `invalid_parameter`, `invalid_type`, `invalid_name`, `invalid_color`, `list_limit`, `tag_limit` (400),
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
