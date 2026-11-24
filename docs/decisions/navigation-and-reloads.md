# Navigation, UI shell and page reloads

## Standing decisions

- **Confirmation before destructive one-click actions** (remove from list, remove tag, revoke token) uses a `data-confirm` attribute + native `confirm()` (`static/confirm.js`): works inside modals and without extra UI code.
- **Explicit `back` on form posts.** The new endpoints (`/seen/toggle`, `/watchlist/item`) take a `back` field and fall back to the Referer, then `/watchlist`; only a local path (+ query, + #fragment for `back`) is ever used, so a crafted value can't redirect off-site (`BackPath`). Since 2026-11-23 the older Referer redirects (follow buttons, deleting a rating, `/status`) use it too.
- **Modals are capped to the viewport** (`max-height: 100dvh - 32px`, internal scroll) and become bottom sheets on phones.
- **Navigation:** desktop shows logo + "Ratelog" wordmark (both link home), Ratings · Discover · Watchlist · Lists · Community (Tags when enabled), and an always-visible search field (`/` focuses it). At 900px and below: logo + search + menu sheet on top (Community, Profile, Configuration, Logout; Tags when enabled), and a 5-tab bottom bar with Home leftmost (user preference), then Ratings, Discover, Watchlist, Lists.
- **Phone bottom sheets hug their content** (`height: auto`, still capped at 92dvh): upstream made phone modals full height, which left the add-to-list sheet mostly empty.
- **No full-screen loader** (user, 2026-11-23: "for the watchlist why does there need to be a loading animation that covers whole
  screen"). Upstream covered the page with a blurred overlay and a pulsing logo on every navigation (`beforeunload`), so even fast
  page loads flashed it. `fragments/loader.html` is now a 3px accent bar along the top edge (`.nav-progress`, `pointer-events:
  none`), shown only when a navigation takes longer than 400 ms (most local page loads finish well before, so they show
  nothing) and hidden on `pageshow` (bfcache) or after 15 s (a download link never unloads the page). The in-place Ratings
  filters use the same bar (`Loader.start()` / `Loader.stop()`). Every template keeps its one include line, so upstream merges stay
  small; the thin bar was chosen over removing the indicator entirely so a slow TMDB-backed page (title pages, Discover) still
  shows that the tap registered. `prefers-reduced-motion` gets a static bar.

## One Back leaves the page: filters replace, navigation pushes (2026-11-23)

The user: "also the back button thing is REAL annoying on the phone, as well as refreshes for filtered list when it shouldn't
need a refresh". On a phone, Back (the edge swipe) is the main way out of a page, and every filter, tab and sort had pushed a
history entry (Ratings filters, list tabs) or loaded a new page (Discover, list Sort, watchlist tabs), so leaving Ratings after
three filters took four swipes. Code: `static/view-swap.js` (new, generic), `list-filter.js`, `ratings-filters.js`, `browse.js`,
`search.js`, `discover.js`, `back-nav.js`; templates `discover.html`, `watchlist.html`, `fragments/browse-bar.html`,
`fragments/ratings-switch.html`.

- **The rule: filters replace, navigation pushes.** Anything that changes how the same page is shown (a filter, a tab, a sort,
  the hide-seen switch, Movies / TV) replaces the current history entry's URL (`history.replaceState`). The URL still describes
  the view, so reload, sharing and the title pages' "← Discover" link show exactly that view, but Back leaves the page in one
  step. Going somewhere else (a poster, a row's "More", a nav tab, a list card) pushes as before. Replacing keeps the entry's
  other `history.state` keys (back-nav.js stores the entry's id there), so the back links still find their origin.
- **Filters apply in place everywhere they are links.** A click fetches the new URL and swaps the page's view region from it:
  Discover's whole view (`#discoverView`: the switch, the bar, the chips, and the rows or the grid; one region, so the bar's
  links, which each encode the full state, always match the results), the list page's region (tabs, Sort, grid; `list-filter.js`
  already did this for tabs) and the watchlist's tabs and Movies / TV filter (`#watchlistView`). Declarative, like `inplace.js`:
  `data-view` on the region (with an id), `data-view-links="<name>"` on each group of links. Focus goes back to the same control
  (the menu's trigger, the switch, the active tab), the results fade after 150 ms and loader.js's thin bar shows after 400 ms,
  and a newer click cancels an older one. The full Discover page is what's fetched (no new fragment endpoint): it costs the same
  TMDB call the grid makes anyway, and swapping the whole view is simpler than keeping the bar and a partial grid in step.
  Measured locally: swaps took 107-472 ms; the same view as a full page was 110-434 ms and 132 KB against 129-342 ms and 73 KB
  for the grid-only `partial=1` fragment, so the time is TMDB's, not the page size.
- **Without JS, or on any error, the links load their page** exactly as before (progressive enhancement); a modified click (new
  tab) is left to the browser.
- **Modes and page switches still load a page, but replace the entry:** the list's tick-off mode ("Mark what you've seen" /
  Done) and the owner's "Edit order" / Done (`location.replace`), and the Ratings tab's Movies / TV shows / To rate switch
  (`data-view-replace`). They are different layouts (and To rate is a different page), so a page load is the robust choice, but
  none is a place you'd want Back to return to. Not done in place: the list suggestions row outside the list region depends on
  the mode, and the reorder editor sets itself up on load.
- **Coming back to Discover's grid or search results keeps your place.** `browse.js` / `search.js` remember per tab
  (sessionStorage) the grid's URL, the pages "Load more" (or infinite scroll) added and the scroll position. Returning to that
  same URL by Back / Forward, a reload or a title page's "← Discover" / "← Search results" link (back-nav.js sets
  `window.RatelogBackNav.returned`) fetches those pages again, appends them in order and scrolls back, unless you scrolled first.
  Re-fetching was chosen over caching the HTML: pages are `no-store`, and a cached copy would still show the title you just
  marked seen (with "Hide titles I've seen" on) or an old bookmark. At most 10 pages are restored; a page that fails shows its
  "Try again". A fresh visit to the same URL (a row's "More", typing it) starts at page 1.
- **Checked for other needless entries:** modals and sheets never touch history, the title page's `?rate=` already used
  `replaceState`, and the one in-page entry is the TV page's "Seasons" jump (`#seasons`), a real anchor kept as is. The pages
  that still load are explicit saves (Rate modal, bulk "add unseen"; both save in place now: bulk since the compact list header, the movie Rate modal since 2026-11-24) and real navigation. The "To rate" view's All / Movies / TV
  tabs were still plain page loads (pushed) here; they switch in place since the same day's "Ratings load as you scroll".

## When the page reloads (audit 2026-11-23)

Rule: **small actions that change one thing on the page save in place** (no reload, no scroll jump, a toast confirms);
**navigation and changes to the whole result set load a page** (they get their own URL, and Back works).
*(Since 2026-11-23 filters, tabs and sorts also apply in place, and **filters replace, navigation pushes**: they replace the
entry's URL instead of adding one, so one Back leaves the page. See "One Back leaves the page" above.)*
Everything in place is progressive enhancement: without JS, or on any error, the plain form post / link still works.
Generic hooks live in `static/inplace.js` (`data-inplace="<regions to refresh>"` on a form, `data-append` on a
"Load more" link); a few pages have their own handler.
The Seen batch's toggles answer `fetch` with JSON instead (the same URL with the header `X-Requested-With: fetch`
picks a second handler): one tile toggle shouldn't re-render a whole page, and re-rendering a title page calls TMDB.
Plain form posts still redirect back with a flash message, so both paths share one endpoint and one handler.

| Action | Before | Now |
|---|---|---|
| Home poster bookmark | reload + scroll restore | in place, all copies of the title flip, toast (`home.js`; since the Seen batch, `tile-actions.js` with the same `type:tmdbId` key) |
| Poster Seen eye and bookmark (home, Discover rows and grid, search, lists) | — | in place (`tile-actions.js`, JSON answer), every copy of the title flips, toast |
| Title page series / "More like this" tiles (bookmark, Seen) | — | in place (`tile-actions.js`); the series progress line and "You're here" eye follow (`title-related.js`) |
| Discover row / grid bookmark | in place | in place (`discover.js`, `browse.js`) |
| Discover "Load more", lazy rows | in place | in place; coming back (Back, reload, "← Discover") fetches the loaded pages again and scrolls back (`browse.js`, 2026-11-23) |
| Search results infinite scroll | in place | in place (`search.js`); coming back loads the same pages again and scrolls back (2026-11-23) |
| List page All / Not seen / Seen tabs | reload | in place, **URL replaced** (`list-filter.js`; pushed an entry per tab until 2026-11-23) |
| List page remove item (after confirm) | reload | in place: progress, tabs and grid refresh (`#listRegion`) |
| Add to list (modal), create list + add | **navigated to the list page** | stays on the title, modal closes, toast "Added to X" *(adding: see the next row)* |
| Add-to-list sheet: tick / untick a list (untick after confirm) | — | in place, **sheet stays open** (`list-membership.js`, JSON answer): the tick, "In 2 of your lists" and the page's "In 2 lists" button update, toast |
| Add-to-list sheet: New list (at the top) | — | in place: sheet closes, sheet and button refresh (`data-inplace="#addToListModal, [data-list-button]"`), toast |
| List reorder mode: drag, "move to #", top / up / down / bottom | — | in place (`list-order.js`): the row moves at once, the server's order wins, toast; no JS = form post back to the row |
| List page Sort menu | — | in place, **URL replaced** (`list-filter.js`, 2026-11-23; was a page load): the tabs keep it, focus returns to Sort |
| List "Edit order" / "Done" | — | **page load** that **replaces the entry** (`location.replace`, 2026-11-23): a mode with its own URL, like tick-off |
| List "Mark what you've seen" (tick-off) / "Done" | page load | **page load** that **replaces the entry** (2026-11-23) |
| List page "Suggested for this list": + Add | — | in place (`list-suggest.js`, JSON answer): the tile turns "Added", the list region refreshes with the new title at the end and its rank, the poster flashes, toast; no JS = form post back to the list |
| List page "Suggested for this list": More suggestions, Start over, Not interested | — | in place: the row is fetched again (or the tile fades out); the list itself isn't touched |
| Lists tab "Load more" | reload | appends in place |
| Save / Saved on an official list (list page, official cards, saved cards under My lists) | — | in place (`saved-lists.js`, JSON answer, 2026-11-24): every toggle of the list flips, the card moves to the front of its Official section (or back to its place), My lists refreshes, toast; no JS = form post back to the page |
| Profile follow / unfollow user | reload | in place |
| Community People / profile Follow, Following (unfollow asks first on touch screens) | — | in place (`data-inplace`): the button, "Following 2 of 5" and the Activity feed refresh, toast |
| Title page Seen, Watchlist, Watching / Dropped | reload | in place (`title-actions.js`, JSON answer): every toggle updates from the server's state (Watchlist posts `/watchlist/set` since 2026-11-24, no confirm) |
| Title page follow bell | reload | in place (`data-inplace=".title-panel"`) |
| "Rate it?" score after Seen | — | **reload**: an explicit save that changes the rating card, Rate button and Seen |
| Watchlist page Remove / Seen / Watching / Drop | — | in place (`watchlist.js`): the tile leaves the tab, counts move, toast with Undo |
| List tick-off, poster Seen on list pages | — | in place: the check, "You've seen 9 of 25", the bar and the tab counts update (`list-tick.js`) |
| List tick-off "All seen" (after confirm) | — | in place: the whole list region refreshes, toast with the count |
| Movie page: rate / edit / delete rating (modals, quick or detailed) | reload | in place (2026-11-24, user: "also does editing the rating when clicked on movie cause a refresh?"; was "reload kept"): `data-inplace="#your-rating, .title-panel"`, the modal closes, toast, "Your rating" (bars redrawn) and the panel (Rate / Edit rating + score, Seen, "Rate it?") are swapped in; also from `?rate=edit` / `?rate=detailed` (the parameter is replaced away). Since 2026-11-23 "Delete rating" is also inside the editor (edit mode), opening the same confirmation |
| TV page: rate / edit / delete a season (modals) | reload | in place (2026-11-23, user: "it reloads the page after each rating"): `data-inplace="#seasons, .title-panel"`, the modal closes, toast, the season cards and the panel's overall score and Seen are swapped in; rating several seasons in a row never leaves the page |
| TV page: Rate show (whole-show score), edit, delete, "Delete whole-show score" | — | in place (2026-11-23): same regions as the seasons, toast |
| Poster ★ / "⋯" Rate on a show: whole-show score, change, delete | — (★ went to the seasons) | in place, like movies (`tile-rate.js`, `type=tv`); a show rated by season shows its average and "Rate seasons", a **page load** (its seasons) |
| Poster ★: rate, change or delete a quick rating (every poster) | — (title page only) | in place (`tile-rate.js`, `POST /movie/rate/tile`, JSON): every copy's chip and eye update, list progress counts, toast |
| Poster ★ on a detailed rating, "Detailed rating…" | — | **page load**: the title page opens its rate modal (`?rate=edit` / `?rate=detailed`); five categories need the full form |
| Poster Add to list (every poster) | — (title page only) | in place: the title page's sheet loads on the page (`GET /tile/lists`), ticks save in place, the poster's button ticks; New list stays on the page |
| "Rate it?" after Seen on a poster / in tick-off | — | in place, optional prompt on that tile; Skip or the next tap closes it |
| Poster "⋯" chooser on touch screens: Watchlist, Seen, Rate, Add to list | — | in place: opening it is local (no request); each row saves exactly like the poster button it stands for (toast, every copy updates); Rate and Add to list open the same picker / sheet (since 2026-11-23 shows too; only a show rated by season links to its seasons, a **page load**) |
| To rate view: rate a title | — | in place: the tile leaves, counts drop |
| Watchlist poster ★ | — | in place: the tile leaves To watch (rating marks it seen), tab counts move |
| Bulk "add unseen to watchlist" on a list | reload | in place since the compact header (2026-11-23, `data-inplace="#listRegion"`): the whole list region refreshes like "All seen", toast with the count; was "reload kept" |
| List "⋯" menu: Edit list / Delete list / Make or Remove official | — | Edit / Delete open their modals (Save and Delete load a page, as before); the official switch saves in place (it already did) |
| Discover bar filters, chips ✕, Clear all, hide-seen switch, sort, Movies / TV | page load | in place, **URL replaced** (`view-swap.js`, 2026-11-23; was a page load): the whole view is swapped from the new URL |
| Watchlist tabs and Movies / TV filter | page load | in place, **URL replaced** (`view-swap.js`, 2026-11-23) |
| Ratings Movies / TV / To rate switch | page load | **page load kept** (a different page), but it **replaces the entry** (`data-view-replace`, 2026-11-23) |
| Stats year switcher (this year, earlier years, All time) | — | in place, **URL replaced** (`view-swap.js`, 2026-11-23): the whole stats region is swapped from the new URL |
| Ratings filters (genre, score type, name; a count select until the ranking scroll) | page load on Search | in place, **URL replaced** (`ratings-filters.js`, 2026-11-23): a dropdown applies on change, the name after a 450 ms pause or Enter; ✕ clears one filter; the new ranking starts at its first page. Pushed an entry per filter until the same day's "one Back leaves the page" change |
| Ratings ranking: the next 30 as you scroll, "Show more" | — (a count select, page load) | appends in place (`ranking-scroll.js`, 2026-11-23), **URL replaced** with the loaded count (`?limit=60`); no JS = the link loads the same view with one more page |
| To rate view All / Movies / TV tabs | page load (pushed) | in place, **URL replaced** (`view-swap.js`, 2026-11-23) |
| Create / edit / delete list, settings, tokens, admin, language, logout | page load | **page load kept**: navigation or whole-page changes. Deleting a list lands on the Lists tab with "List deleted." (2026-11-24; it went to your profile); a list name that isn't allowed goes back to the page you were on |
| Person page department / All, Movies, TV / Newest, Most popular / Show all | — | in place, **URL replaced** (`view-swap.js`, 2026-11-24) |
| Search Year box | — | **page load** like the Any / Movies / TV select (a new result set). Replaced by the next row (2026-11-24) |
| Search Year menu (options, Type a year) and Any / Movies / TV select | page load | in place, **URL replaced** (`view-swap.js` swaps `#searchView`, 2026-11-24, user asked for Discover's Year menu on search): a decade keeps the menu open on its years, like Discover; the select and "Type a year" are GET forms that `view-swap.js` now applies too |
| Title page back link ("← Discover", "› Toy Story 3") | — | **navigation**: `history.back()` when that page is the previous entry, else a page load of its URL with the scroll put back (`back-nav.js`) |

### Where actions land (audit 2026-11-24)

The user: "deleting a list takes me to the community page? check what actions take you to other pages that shouldn't". Every
`redirect:` in the controllers and every form in the templates was checked:

- **Delete list** went to `/profile/{you}`. Your profile is your activity feed, which looks like Community. It now goes to
  `/lists`, where your lists are, with "List deleted." (`DeleteListController`).
- **New list errors** (an empty name, or the list limit) went to your profile from the Lists tab and from a title page's "Create
  & add". They now go back to the page the form was on (the Referer through `BackPath`, else `/lists`).
- **Everything else lands where it should:** quick actions (watchlist, seen, ratings, follow, save, membership, suggestions)
  save in place or come back to their page through `back` / the Referer; edit list, remove from list, official, mark all seen,
  reorder go back to the list; settings, language, tokens and admin forms stay on their page; the Letterboxd import (a profile
  modal) returns to the profile; creating a list opens the new list (from a title page's sheet it stays in place with JS).
