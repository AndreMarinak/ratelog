# Ratings

## Standing decisions

- **Quick rating:** category columns nullable; a rating is either all five categories or none (+ score), enforced in handlers and by DB CHECK constraints. Category rankings exclude quick ratings; overall rankings include them. Letterboxd imports create quick ratings.
- **Tags are per user**; the Top-page tag filter narrows the list but keeps overall ranks (same as the genre filter).
- **One Ratings tab for movies and TV** (user request, 2026-11-23): `/ratings` redirects to the movie ranking (`?type=tv` to TV); `/movies` and `/tvshows` keep their filters and are joined by a Movies | TV Shows switch (`fragments/ratings-switch.html`). Merging them freed a bottom-bar slot for Browse.
- **Tags are hidden in the web UI for now** (user request, 2026-11-23): `ratelog.features.tags` / env `RATELOG_FEATURES_TAGS` (default false) is exposed to templates as `tagsEnabled` by `system/FeatureFlagsAdvice.kt`; it hides the nav link, the title-page tags section, the Top-page tag filter and the profile button. Code, `/tags` pages, the API and list-import tags keep working.
- **Ratings filters apply at once, with an ✕ per filter** (user, 2026-11-23: "if I clicking things on dropdown should just auto
  do it not require me to click search, also once select something allow an easy x without having to click dropdown (x on each
  dropdown when something selected)"). On `/movies` and `/tvshows` (`static/ratings-filters.js`, loaded by the Movies | TV Shows
  switch so the upstream templates are untouched):
  - **In place, with the URL pushed**, not a page load: the page is fetched and the ranking under the form swapped, so the
    dropdown you just used keeps focus, the page doesn't flash or jump, and Back / Forward step through filters (`popstate`
    reloads the ranking and sets the dropdowns back). *(Superseded the same day by "One Back leaves the page": the URL is now
    replaced, so Back leaves Ratings in one step; the popstate handling and the per-burst entry are gone.)* Same pattern as the list page tabs (`list-filter.js`). The URL only carries
    filters that are set (`/movies?category=27`, not `?category=27&ratingCategory=&name=&limit=10`). Any failed fetch loads the URL.
  - **The name field applies after a 450 ms pause, or on Enter / leaving the field**, so typing "dune" doesn't reload four times;
    one typing burst is one history entry (later pauses replace it), so Back doesn't walk through "d", "du", "dun".
  - **✕ inside the field, in the chevron's place**, on every filter with a value (the count's ✕ only when it isn't the default 10):
    one tap clears just that filter. It replaces the chevron rather than sitting beside it because at 360px the genre field is
    ~160px wide and a second icon would cut "Science Fiction" short; tapping the rest of the field still opens the dropdown. A set
    filter also gets an accent border. The ✕ has an `aria-label` "Remove filter: Horror".
  - **No-JS path unchanged:** the controls are wrapped and the ✕s added by the script; the Search button is only hidden once the
    script runs (`form.is-live`). Phones keep two rows: genre | score type, then name | count; the count is a fixed 96px
    (the no-JS grid gives it a quarter of the row, ~76px at 360px, which cut "25" / "100" off once the ✕ was in it).
    *(The count select was removed the same day by "Ratings load as you scroll": the second row is the name alone.)*

## To rate: seen but not rated (2026-11-23)

Requested by the user on 2026-11-23: "Also under ratings there should be something to show movies watched but haven't rated".

- **`/ratings/unrated`, a third option in the Ratings switch: "★ To rate 12".** Titles with status Watched and no rating (a
  show: no rated season), newest Seen first, All · Movies · TV tabs with counts. The count in the switch is the hint on the
  ranking pages (one `COUNT` query per Ratings page view, `UnratedSeenCountAdvice`; hidden at 0). A new page and route rather
  than another filter in the ranking forms, which are a different thing (your ranked ratings) and were being edited elsewhere.
- **Rate in place:** every tile has the poster actions, so ★ rates a movie right there; the tile fades out and the tab and
  switch counts drop by one (`ratings-unrated.js`), and the toast says what was saved. Un-seeing a tile with its eye removes it
  too. A show's ★ opens its seasons. Up to 200 titles per tab.

## TV: rate a show as a whole, or by season; seasons save in place (2026-11-23)

Requested by the user on 2026-11-23: "on a tv show rating when im rating mult seasons it reloads the page after each rating"
and "for tv shows I should be able to rate show as a whole and then if u rate any season at all then the overall rating
becomes the average".

- **Season ratings save in place.** The season rate modal and the delete confirmation post through inplace.js
  (`data-inplace="#seasons, .title-panel"`): the modal closes, a toast confirms, the season cards and the action panel (your
  overall score, Seen) are swapped in from the page. Rating several seasons in a row never leaves or scrolls the page. Card
  clicks are delegated (`static/tv-seasons.js`, an open card stays open) and `breakdown.js` redraws category bars in
  swapped-in regions (`inplace:swapped`, fired by inplace.js after a swap). Without JS: a post and a redirect, as before.
- **One score for the whole show.** A show can get one 0-10 score (the quick-rating slider on the page, 1-10 on a poster's
  picker), without rating any season. Stored in `tv_ratings.show_score` (`V1140`, with `show_rated_at_epoch_ms` for the feed and
  "Recently rated"), not as a fake season 0: TMDB uses season 0 for Specials.
- **The rule: rated seasons win.** A show's score is the average of its rated seasons (quick and detailed alike, as before)
  when at least one is rated, else its whole-show score. `tv_ratings.score` keeps holding the show's score under this rule
  (`TvRating.updateScore`), so every reader that already used it (Ratings TV ranking and its filters, home rows, poster chips,
  watchlist, tags, list progress, the API) gets the rule without a second code path. Category rankings only use detailed
  seasons, so a whole-show-only show is left out of them, like a show rated only quickly.
- **The whole-show score is kept, not dropped, once a season is rated** (unused while any season is rated). Deleting every
  season rating brings it back as the show's score; the page says so ("Your whole-show score (8.00) is replaced by the season
  average. It comes back if you delete every season rating.") and offers "Delete whole-show score" there. Dropping it silently
  would lose a score the user gave on purpose; a rating row is only deleted when it has neither seasons nor a whole-show score.
- **TV page:** the panel's fourth button is "Rate" / "Edit rating" with your whole-show score while no season is rated (it
  opens the Rate show modal, like a movie), and "Rate seasons" with the season average once any is rated (it jumps to them).
  The season list starts with a "Whole show" card: Rate show / Edit and a hint that rating a season replaces it, or
  "Average of N seasons" with the average and the explanation above. Edit mode of the modal has "Delete rating" (asks first).
  `?rate=1` opens the modal (or scrolls to the seasons of a show rated by season): that is where a poster's ★ goes without JS.
- **Posters:** a show's ★ (and the "⋯" chooser's Rate row) opens the same picker as movies: one tap sets the whole-show score,
  "Delete rating" removes it (`POST /movie/rate/tile` with `type=tv`, `TvTileRateHandler`). A show rated by season shows its
  average in the picker ("Average of your seasons") with "Rate seasons" to its page instead of score buttons, like a movie's
  detailed rating; the server refuses a whole-show score there too (`409`, `seasons: true`). `TileMarks.ratedBySeason` (one
  query per page) tells the tile which shows those are. The "Rate it?" prompt after Seen now covers shows too.
- **Rated means seen, like movies:** a whole-show score marks the show Watched (from no status, Want to watch or Watching;
  Dropped and Watched are left alone, `MarkWatchedOnRating.wholeShow`). Rating a season still only makes it Watching until
  every aired season is rated. "Rated" everywhere (Seen state, To rate / "Seen, not rated", list progress, Discover's
  hide-rated) means a rated season or a whole-show score. Deleting never touches the status.
- **Feed / profile:** a whole-show rating appears as one entry without a season label, only while no season is rated (once one
  is, the season entries stand for the show).
- **API:** `PUT` / `DELETE /api/v1/ratings/tv/{tmdbId}` set and delete the whole-show score; `TvRating` gains `scoreSource`
  (`seasons` / `show`) and `showScore` ([api.md](../api.md#ratings)).
*(This replaces "Shows: ★ goes to the show's seasons" in "Rate and add to list from any poster" above.)*

## Ratings load as you scroll (2026-11-23)

The user: *"the ratings page only natively loads 10 the default but can we just have it load more as u scroll?"*, then, offered
infinite scroll with a fallback button: *"Infinite scroll sounds good"*. Code: `ratings/scroll/RankingScroll.kt` (new),
`static/ranking-scroll.js` (new), `templates/fragments/ranking-scroll.html` (new); small edits to the two ranking controllers
and templates, `RatingDAO` / `TvRatingDAO`, `ratings-filters.js` and `ratings.css`.

- **30 first, then 30 more as you near the end.** `/movies` and `/tvshows` show 30 (was 10). A footer under the ranking is the
  sentinel: when it comes within ~900px of the screen (IntersectionObserver) the next 30 are fetched and appended before it, so
  on a phone the next page is usually there before you reach the bottom (360-420 ms from scrolling to the 45th card locally).
  Ranks come from the query, so appended cards continue 31, 32... (with a filter, the overall ranks as before). A card already
  shown is skipped.
- **The next page is the same URL's ranking fragment:** `?offset=30&limit=30&partial=1` renders only `movies :: ranking`
  (cards + footer; no filters, tag list or premieres). No new endpoint, and the cards can't drift from the page's markup. The
  query fetches one row more than it shows, so the footer knows whether there is more without a count query.
- **Paging in SQL:** `LIMIT :limit OFFSET :offset` after the ranking, and ties now rank by rating id (`ORDER BY score DESC
  NULLS LAST, r.id`). Without the tiebreaker two titles with the same score could swap between two queries, and one would show
  twice and the other never. OFFSET over keyset: the ranking is a window function over the user's whole list anyway (filters
  apply after ranking), so a rank cursor saves nothing, and personal lists are hundreds of rows, not millions.
- **"Show more" stays visible as the fallback** (it reads "Loading…" while a page comes in). Clicking it loads in place too,
  and focus moves to the first new card. Without JS it is a plain link to the same view with one more page, opened at the first
  new card (`?limit=60#rank-pos-31`). After the last title: a quiet "That's all 45". If a page fails, the footer says so, the
  link reads "Try again" and nothing loads by itself until it is used (no retry loop); a 429 shows the server's message as a
  toast; a login page (session expired) loads the link's URL.
- **No history entry per page.** The URL is replaced (`?limit=45`, keeping other scripts' `history.state`), so one Back leaves
  Ratings, and a reload or coming back shows everything you had loaded at once (the server renders `limit` titles; the browser
  or back-nav.js puts the scroll back). Cheaper than Discover's re-fetch of each page (browse.js): one query, no
  sessionStorage. A fresh visit (`/movies`, the nav tab) starts at 30.
- **A filter shows the new ranking from its first page, in place:** ratings-filters.js builds its URL from the filters only (so
  `limit` drops out), swaps the ranking as before and fires `ratings:swapped`, and ranking-scroll.js watches the new footer.
- **The count select (5 / 10 / 25 / 50 / 100) is gone**: with the list loading as you scroll it only decided how soon the
  first fetch happens. `?limit=` still works in URLs (it is what the reload uses). Phones: the filters are two rows, genre |
  score type, then the name.
- **Poster quick actions on appended cards** need nothing new: tile-actions.js and tile-rate.js are delegated from `document`,
  and `RankingTileActionsInterceptor` now also fills `rankingActions` for the `:: ranking` fragment view.
- **The To rate view's All / Movies / TV tabs switch in place** (same day, left over from the phone-Back work): the section is a
  view-swap.js region (`data-view`, `data-view-links`), so the tabs replace the URL instead of adding a Back step, and
  `ratings-unrated.js` looks the section up on each event instead of keeping the first one.

## Movie ratings save in place (2026-11-24)

Asked by the user on 2026-11-24: "also does editing the rating when clicked on movie cause a refresh?" It did: the movie page's Rate modal (quick slider
or the five categories, create and edit) and the delete confirmation were plain form posts that redirected back to
`/movie/{id}`, so every save reloaded the whole page (and called TMDB again), unlike the TV seasons since 2026-11-23.

- **Same mechanism as the TV seasons:** both forms carry `data-inplace="#your-rating, .title-panel"` (`static/inplace.js`).
  The modal closes, the server's flash shows as a toast ("Rating saved / updated / deleted successfully."), and two regions
  are swapped in from a fresh copy of the page: **"Your rating"** (the quick score or the category bars, which `breakdown.js`
  redraws on `inplace:swapped`, the review, the card's edit / delete buttons with the new values) and the **action panel**
  (Rate becomes "Edit rating 8.00" and back; Seen turns on and locks with a rating, which marks the movie Watched, and stays
  on but unlocked after a delete, the existing rule; a visible "Rate it?" prompt goes away). The series row's "You're here" eye follows Seen on its own
  (`title-related.js` watches the panel). No new endpoint and no JSON: the controllers already redirect to the title page with
  a flash message, which is also the no-JS path, and 429 answers show their message as a toast (inplace.js).
- **"Your rating" is always one element,** so the swap can't miss: when unrated the page renders an empty, `hidden`
  `<section id="your-rating">` stand-in (inplace.js reloads the page when a region's count differs between the page and the
  fresh copy). It has no classes, so `.title-rating` still means "the card is there" (the `seen` suite checks exactly that);
  the card that replaces it brings its own classes and the phone / tablet `order` rules place it as before. A wrapper `<div>`
  was not used: the layout's `display: contents` columns style the card as a direct child.
- **Edit triggers keep working after a swap:** the modal reads its prefill from the button that opened it (`modal:open`), and
  the swapped-in Edit rating / pencil buttons carry the new `data-*` values, so a second edit starts from what was just saved.
  The modal's form switches to `/movie/rate/update` the same way.
- **`?rate=edit` / `?rate=detailed`** (a poster's "Edit rating" / "Detailed rating…") still open the editor; `tile-rate.js`
  already drops the parameter with `history.replaceState` as it opens, so after an in-place save the address has no `rate=`
  and Back still leaves the page in one step (no entry is added).
- **Not changed here:** the "Rate it?" prompt after Seen still saves with a page load (it lives in
  `fragments/title-actions.html`, which another branch was editing; see bugs-and-issues Open).
