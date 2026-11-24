# Watchlist and Seen

## Standing decisions

- **Statuses are separate from upstream's "follow"** (follow = release dates in Premieres). Rating a title auto-sets Watched (from none / Want to watch / Watching) via a repository wrapper, so upstream rating handlers stay untouched. *(Shows: see the TV rule below.)*
- **Quick watchlist toggle by TMDB id** (`POST /watchlist/toggle`): poster tiles only know TMDB ids, so the endpoint saves the title locally if needed. The bookmark only toggles *Want to watch*; Watching/Watched/Dropped are left alone (changed on the title page).
- **Seen / Watchlist instead of four statuses** (user, 2026-11-23: "what's the point of want to watch / watching / watched / dropped when I have lists and ratings?"). The UI is Letterboxd-simple; the data model and `/api/v1/statuses` are unchanged (one status per title, plus the rating). **Seen** = status Watched, or rated; **Watchlist** = Want to watch. *(Superseded 2026-11-24 for the watchlist: it is its own table, independent of Seen, see "Rewatch: the watchlist is independent of Seen".)* Watching / Dropped only show for TV (a small control), plus a "Marked Dropped · Clear" chip on a movie that still has one from before.
- **A rated movie stays Seen; tapping Seen explains instead of un-seeing.** Un-seeing it would mean deleting the rating (destructive), and keeping the rating while clearing Watched would change nothing visible (rated still counts as seen everywhere). So the toggle is locked on with a "Rated" note, and a tap says "You rated it, so it counts as seen. Delete the rating to un-see it." Letterboxd behaves the same way. Seen from Want to watch / Watching / Dropped sets Watched; un-seeing clears the status to none (it never restores the old one).
- **Shows: the status decides, not "has a rated season".** With the new TV rule (below) a show with one rated season is Watching, so showing it as Seen would contradict that. For shows, Seen = Watched, or rated with no status at all (ratings from before statuses existed); Watching / Want to watch / Dropped win over rated seasons. Shows are never locked: un-seeing a show with rated seasons moves it back to Watching. List progress (`/list/{id}`, the `/lists` card counts and the API's `seen`) uses the same rule, so the tick-off counts and the tiles agree. Movies keep "rated or Watched".
- **TV auto-status:** rating a season makes the show **Watching**; it becomes **Watched** once every aired season is rated. Watched is never downgraded, Dropped never touched, deleting a season rating changes nothing; movies are unchanged (Watched unless Dropped). "Aired" comes from the stored show, the same season list the title page shows (1 to the last season, never season 0 / Specials): the stored show only has the last season's air date and the next future season date, so the last season counts as unaired when its date is unknown or after today, and one more when a different future date exists (`AiredSeasons`). TMDB only appends unaired seasons at the end; three or more unaired seasons would be under-counted by one. No TMDB call happens inside the rating save.
- **Watchlist on an unrated Seen asks first.** One status can't be both Watched and Want to watch, so adding a seen-but-unrated title to the watchlist (a rewatch) would stop it counting as seen: the button confirms. A rated title stays seen through its rating, so it doesn't ask. *(Superseded 2026-11-24: the watchlist no longer replaces Seen, so nothing asks; see "Rewatch: the watchlist is independent of Seen".)*
- **Upcoming:** a title is upcoming when its release date (a show's first air date) is after today; released today is out. Tiles only know the date, so no date = not upcoming there; title pages also treat a dateless movie that TMDB lists as Rumored / Planned / In Production / Post Production (a show: Planned / In Production) as upcoming, shown as just "Upcoming". "Today" comes from an injected `Clock` (system zone). The badge date is short ("Dec 18") while it's under ten months away, so the year is never ambiguous, else "Sep 2027"; title pages show the full date. Upcoming titles get no Seen toggle anywhere (`POST /seen/toggle` refuses them too); the Watchlist toggle stays. Dates come from the TMDB payloads (home, Discover, search) or the stored title (lists, watchlist, personal rows, title pages; unreleased movies are refreshed by the daily sync).
- **The follow bell only where it does something** (user, 2026-11-23: it "wouldn't do anything for most" titles). Following only adds release dates to the Premieres box on the Ratings page (no notifications), so the bell shows for movies whose release date is after today or unknown, and for shows that are Returning Series / In Production / Planned. A title already followed always keeps it (on) so a follow can't be stranded. Labelled "Notify me · Adds release dates to Premieres"; small and secondary, not a heart (a heart may later mean "like").
- **"Rate it?" after Seen, never forced** (user decision 2026-11-23). After tapping Seen on a movie page with no rating, a small inline prompt offers 1-10 (one tap saves a quick rating through the Rate modal's own `POST /movie/rate`) or Skip. Not on tiles, the watchlist page or tick-off (bulk marking stays fast), not for shows (rated per season), and not without JS (Seen just saves). Saving reloads the page, because the rating card, the Rate button and Seen all change. *(The Rate modal itself saves in place since 2026-11-24, see "Movie ratings save in place"; this prompt still reloads.)* *(Tiles and tick-off got their own optional prompt on 2026-11-23, see "Rate it? after Seen on tiles" below.)*
- **Watchlist page:** tabs read **To watch · Watching · Seen · Dropped** (named after the toggles), with a one-line hint under them. Every tile has visible buttons: To watch: Seen, Watching (shows), Remove; Watching: Seen, Drop (shows), Remove; Dropped: Watching (shows), Seen, Remove; Seen: Remove, except a rated title (it would still count as seen through its rating). *(Layout and the Dropped order changed 2026-11-23, see "Watchlist buttons line up on every tile" below.)* Upcoming titles get no Seen. **Remove is one tap with Undo**, not a confirm: the toast (or, without JS, the notice after the redirect) says "Removed "Dune" from your watchlist." with an Undo that restores the previous status. Plain POST forms to `/watchlist/item` with an explicit `back` (same tab and type filter). *(Since 2026-11-23 the page opens on Movies and the filter is Movies | TV, no All: see "Watchlist opens on Movies".)*

## Watchlist opens on Movies; Discover's Official lists follow Movies / TV (2026-11-23)

- **The watchlist opens on Movies** (user, 2026-11-23: *"watchlist tab should default to movies like everything else"*).
  `/watchlist` without a `type` shows Movies, like Ratings (`/ratings` opens `/movies`) and Discover (`type` defaults to movie);
  the header and bottom-bar links land there. The type switch is now **Movies | TV**, a two-way switch like the other pages'
  (the "All" option is gone; an unknown `type`, e.g. `all`, also shows Movies). Every status tab link carries the type, so To
  watch · Watching · Seen · Dropped keep it, and both switches still apply in place (`view-swap.js`).
- **Watching stays a TV thing.** A bare `/watchlist?status=watching` (Home's "Continue watching → See all") opens on **TV**,
  since only shows can be Watching; landing on an empty Movies tab would look broken. The Watching tab stays on Movies too (the
  tabs don't jump when switching type): empty, it says "Only TV shows can be Watching. Switch to TV for the shows you're in the
  middle of."; a movie that still has Watching from before (or set through the API) is listed with Seen and Remove, under a hint
  that says so. `WatchlistController.defaultType`.
- **Discover's Official lists row follows Movies / TV** (user chose "Only lists with TV" for TV mode, 2026-11-23). Movies mode
  shows official lists with at least one movie, TV mode those with at least one show; a mixed list shows in both, an empty one
  in neither, and with none to show there is no row (TV mode on the live site: just "Best TV Shows of All Time"). The filter is
  an `EXISTS (... media_type = :mediaType)` in the same lists query (`ListSummaryDAO.findOfficialContaining`), so the row is
  still three queries, and still lazy (the grid and `/discover/row` fetches run none). Its "See all N" opens
  `/lists/official?type=movie|tv`, the same subset, so the count matches; `/lists/official` alone, the Lists tab's row and
  `GET /api/v1/lists/official` still list every official list. `type` is parsed like Discover's (`OfficialListsType`, aliases
  such as `shows`).

## Rewatch: the watchlist is independent of Seen (2026-11-24)

User, 2026-11-24: *"on movies I've already seen I should still be able to add it to the general watchlist"*. Until now
"on the watchlist" was the status Want to watch, the same one status that says Watched, so a seen title couldn't be on the
watchlist without stopping counting as seen (the title page asked first; tiles hid the bookmark). Now the two are
independent. Code: `watchlist/WatchlistEntry.kt` (+ DAO, repository methods on `MediaStatusRepository`),
`watchlist/entry/SetWatchlistHandler` + `SetWatchlistController` (`POST /watchlist/set`), `api/watchlist/ApiWatchlistController`.

- **Data: a separate table, one source of truth each.** `watchlist_entries` (V1141: `user_id, media_type, media_id,
  added_at_epoch_ms, rewatch`, unique per user + title) is the only place that says "on the watchlist";
  `media_statuses` keeps progress only (Watching / Watched / Dropped) and its CHECK constraint no longer allows
  `WANT_TO_WATCH`. **Seen** stays computed from the status and the ratings (`SeenState`), never from the watchlist. The
  migration moves every Want to watch row (keeping its date) and deletes it from `media_statuses`. Why a table and not a
  column on `media_statuses`: the status would have to become nullable (a title only on the watchlist has none), every
  "clear the status" would have to keep the row alive for the flag, and the watchlist is its own list in Letterboxd too.
  Both tables sit behind the one `MediaStatusRepository`, so every handler that changes one keeps the other consistent.
- **`WatchStatus.WANT_TO_WATCH` stays** as the To watch tab and as the *effective* status of a title that is only on the
  watchlist (`WatchStatus.effective`), so the API and older readers keep working: an unseen title on the watchlist still
  reads `want_to_watch`; a Watched one reads `watched` with `onWatchlist: true`.
- **The bookmark is on every title but a Watching / Dropped one**: posters (hover on desktop, the "⋯" chooser on phones), the
  title page's Watchlist toggle (no confirm any more), seen and rated titles included. Adding never changes the status or the
  rating, so the eye and the ★ score stay. Watching / Dropped shows keep their badge on posters; on the title page the
  Watchlist toggle still moves such a show to To watch (replacing the status), as before. The list's "Add N unseen to
  watchlist" stays unseen-only (no status, not rated, not already on the watchlist).
- **What takes a title off the watchlist** (rule 4): marking it Seen (the eye, tick-off, "Mark all seen", the watchlist's
  Seen) when it wasn't seen, as before. Rating it, or setting Watched through the status / API, takes it off **only if it was
  unseen when it was added**: `rewatch` (set when added: was it seen then?) keeps a rewatch on the watchlist when you rate it
  again or mark it Watched. Setting Watching or Dropped takes it off (it moves to that tab, as before). Removing it from the
  watchlist never un-sees it. Un-seeing a rewatch (the eye) leaves it on the watchlist as an unseen title (`rewatch` false),
  so seeing it later takes it off. Clearing the status entirely (`DELETE /api/v1/statuses/...`, an empty status) clears both.
- **Watchlist page:** To watch lists every title on the watchlist, newest added first; a seen one is a **rewatch**: its card
  gets a green "Rewatch" tag (eye icon) where Seen / Watching would be and only Remove, and its poster keeps the eye and ★.
  It still counts on the Seen tab. To watch's Remove is a new move, `unlist` (off the watchlist, the status stays), with Undo
  `relist`; Undo after Seen / Watching from To watch is `want_to_watch` (exactly "on the watchlist, no status"). Other tabs
  are unchanged (Remove on the Seen tab un-sees, and a rewatch stays on To watch). Tab counts move by the tab a title left
  and the one it joined, so removing a rewatch from To watch leaves the Seen count alone.
- **Everywhere else** a seen title on the watchlist stays seen: list progress and "seen N of M", Stats, To rate,
  Recommended for you (which already left out anything on the watchlist), Discover's "hide titles I've seen" and "hide my
  watchlist", the poster marks. Home "Up next" is the watchlist (rewatches included, newest added first); the poster's eye
  and score show which ones are seen.
- **Known edge:** a show with rated seasons that is Watching, moved to To watch from its title page, has no status any more
  and so counts as seen by the old "rated with no status" rule (shows rated before statuses existed). Rare; left as is.
- Supersedes "Watchlist on an unrated Seen asks first" and the "Watchlist = Want to watch" part of "Seen / Watchlist instead
  of four statuses".
