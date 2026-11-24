# Bugs and issues

Problems found while building and running the fork: what happened, the cause, and the fix (or why it's still open). Newest at the top of each section. Related: [design-decisions.md](design-decisions.md).

## Open

- **"Rate it?" after Seen on a movie page still reloads the page** (left from the movie in-place rating fix, 2026-11-24): the
  prompt's form in `fragments/title-actions.html` has no `data-inplace` (that file was being edited on another branch). Fix:
  add `data-inplace="#your-rating, .title-panel"` to `.title-nudge-form` and change the `seen` suite's nudge step from a
  navigation to the in-place wait.
- **The "Add N unseen to watchlist" count in a list's "⋯" menu can lag behind a poster tap** (compact header, 2026-11-23):
  ticking a poster Seen or Watchlist in place updates the progress line and the tab counts (`list-tick.js`) but not N, until
  the next tab / Sort switch or reload. Pre-existing (the old orange button had the same count); harmless because the server
  recounts when the form is posted, and the confirmation shows the stale N only.
- **On touch screens the poster's "⋯" needs JS** (poster chooser, 2026-11-23): without JS it is a plain button that does
  nothing, and the four controls it stands for stay hidden; the title page still has every action. Showing the four until the
  script runs would flash them on every page load. A Watching / Dropped status and "on your watchlist" are no longer visible on
  posters at rest (by design: only Seen and your score are), only on hover or in the chooser's header and rows.
- **Without JS, the phone title page's "Play trailer" pill overlaps a "Watch the trailer on YouTube" link** (seen in the
  back-link no-JS check at 360px, 2026-11-23): the no-JS fallback link of the trailer is drawn over the pill. Cosmetic and
  only without JS; pre-existing (`fragments/title-info.html` / `title-info.css`).
- **"Not interested" on a suggestion only lasts for the browser session** (`sessionStorage`), so a dismissed title can come back
  the next day. Chosen as the cheap version (2026-11-23); a lasting "never suggest this for this list" needs a small table.
- **List suggestions only find a series through the sampled titles** (lists of more than 10 use 10 of them): the 25-film "Best
  of the 2000s" test list has The Fellowship of the Ring and The Return of the King but no The Two Towers, and when neither is
  sampled the series isn't read, so The Two Towers isn't pinned first. Ratelog doesn't store a movie's TMDB collection; keeping
  it at import (a column or a small table) would let every list title count without extra TMDB calls.
- **"+ Add" on a suggestion reloads the whole list region from the server** (the same view, fetched again, `#listRegion`
  swapped), so on a very long list each add re-renders every item (upstream's list handler reads titles one by one). Fine at
  the sizes in use (25-100 titles); a per-item fragment would be cheaper for lists of several hundred.
- **The first suggestions request after a restart is slow** (3.6 s and once 9 s on the busy dev machine on 2026-11-23, against
  0.8-1.2 s for a list nobody had opened since): the JVM's first TMDB calls pay for TLS setup and class loading. The row is lazy and shows
  a skeleton meanwhile, so the page itself isn't held up.
- **TMDB's recommendations are noisy for some titles** (Inception's include Paycheck, Push and The Dark Tower; The Office's
  include 25-vote sitcoms), so one-title lists can get odd suggestions. The vote floor (50) and the quality factor keep most of
  it out; nothing else to fix on our side without another data source.

- **Series headings mix languages for non-English metadata:** "Part of the {name}" is an English UI string, while the collection name
  comes from TMDB in the metadata language, so a Spanish metadata setting reads "Part of the Toy Story - Colección". The UI strings
  are English-only anyway; fix together with real translations (e.g. `recs.series.title` per language).
- **The old score badges are still in the tile markup, hidden by CSS** (`.home-score`, `.browse-user-score`, the list badge,
  hidden with `:has(.tile-rate)`), so tick-off mode and rated upcoming titles keep showing a score. Harmless; drop them from the
  templates once nothing needs them.
- **The tile's rating picker only gives whole scores (1-10).** A quick rating of 7.75 (from the title page slider) is shown as
  "7.8" on the chip and in the picker, with no score highlighted; finer scores stay on the title page ("Detailed rating…").
- **Opening a tile's add-to-list sheet loads every list with its items** (`CustomListRepository.findByUserId`, one query per
  list, as on title pages). Fine at the admin's ~30 lists; a lighter "my lists" query would help if lists grow a lot.
- **Rating (or listing) a title from a tile is slow the first time it is used** (seen 2026-11-23 in the browser check: one
  rating took more than 8 s): a title from a TMDB row that Ratelog hasn't stored yet is imported first (TMDB details and
  translations), the same as the bookmark and the eye. The tapped score stays dimmed (busy) until it is saved; later taps are
  fast. Idea: import in the background and answer from the stored title once it exists.
- **The To rate view stops at 200 titles per tab** (newest Seen first); rating some makes room. No paging yet.

- **Discover's "Hide titles I've seen" still uses the old TV rule:** it hides a show with any rated season (`LibraryMark.seen` = rated or Watched), while tiles, title pages and list progress now treat a show that is Watching / Want to watch / Dropped as not seen even with rated seasons. Left alone because Discover was being changed on master at the same time; fix by passing the media type into `LibraryMark.seen`.
- **Old bookmark handlers are unused after the Seen merge:** the `.home-quick` fetch handler in `home.js` and the bookmark code in `discover.js` (`form.home-quick`) and `browse.js` (`form.browse-bookmark`), plus their CSS. Home, Discover rows and the grid now render the shared quick-action stack (`fragments/tile-actions.html`), saved in place by `tile-actions.js` with the same `type:tmdbId` key. Safe to delete; `home.js`'s scroll restore is still used by `tile-actions.js`'s fallback. Left in place because Discover was being edited on master.
- **Aired seasons are approximate for shows with three or more unaired seasons** (`AiredSeasons` only sees the last season's date and the next future date). Needs per-season air dates stored, or a TMDB call, to be exact.
- **Owner-only modals on the profile page use `th:if` + `th:replace` on one element** (upstream, `profile.html`: Letterboxd import and create-list modals). `th:replace` runs before `th:if`, so they render on other people's profiles too. Harmless (hidden, and the server checks ownership), but the same pattern made the list page 500 (see Fixed). Fix: wrap in `<th:block th:if>`.
- **The API can't read progress on other people's public lists:** `GET /api/v1/lists/{id}` only serves your own lists and public official lists (other lists are 404 by design), while the web page shows your progress on any public list.
- **The web switch can't mark another user's private list**: upstream's list page refuses to open it, even for admins. Use `PATCH /api/v1/lists/{id}` with `{"isOfficial": true}` (it stays private, i.e. hidden, until its owner makes it public).
- **Title matcher is strict with same-name knockoffs.** Aladdin (1992), Mulan (1998), Hercules (1997), Pocahontas (1995), The Hunchback of Notre Dame (1996) and Strange World (2022) came back `ambiguous` because TMDB has budget animated films with the same title and year. TMDB also dates some films differently (Hamilton 2025, Snow White 1938). Workaround: send `tmdbId`. Idea: tie-break by TMDB vote count when one candidate dwarfs the others.
- **TMDB release-date changes break year matching.** Hamilton (2020) is dated 2025 on TMDB after its theatrical re-release, outside the ±1 year slack. Workaround: `tmdbId`.
- **Old Letterboxd imports look "detailed":** before quick rating existed, imports stored the same score in all five categories. They can't be told apart from a real detailed rating with equal scores, so they were not converted.
- **Float noise in category scores from the API:** categories sent through the API that aren't multiples of 0.25 (e.g. 8.3) display as `8.300000190734863` on the web page because category columns have lower precision. The API itself rounds to 2 decimals; the sliders only produce 0.25 steps.

- **Rating breakdown uses a decimal comma** ("9,00") on title pages in every language (upstream `breakdown.js` replaces `.` with `,`), while the rest of the UI shows "9.05". Cosmetic; left as upstream.
- **"Rate seasons" wraps onto two lines at 360px** in the TV page's phone action row (found in the In-your-lists check,
  2026-11-23). It still fits (no overflow; the row grows a little); the other three tiles stay on one line. Pre-existing.
- **A console 404 on title pages whose trailer has no 1280px YouTube thumbnail** (seen on Finding Nemo and Toy Story,
  2026-11-23): `title-page.js` probes `maxresdefault.jpg`, which YouTube sometimes answers with a 404 instead of its
  120x90 placeholder. The page keeps the 480px thumbnail, so only the console shows it. Pre-existing.
- **`delete-tvshow-rating-modal.html` declares a top-level `const modal`** in a plain `<script>`. Any other top-level `const modal` on the TV page would throw a redeclaration error; wrap it in an IIFE if that page gets more inline scripts.

- **Sorting a list then using "Add the N unseen to my watchlist" drops the sort** after the redirect (the bulk form only sends
  `show` back). Harmless (the list shows in list order again); the bulk form belongs to list progress.
- **Two tabs reordering the same list:** each move is applied to the order saved at that moment (the list is locked per move), so
  nothing is lost or duplicated, but a tab that was open during another tab's moves shows its old order until the next move
  answers with the saved order (then it catches up) or the page reloads.

## Fixed

- **Typing in the header search scrolled the page up** (user, laptop, 2026-11-24: "as I type in search bar it scrolls me up,
  check all the search bars"): every keystroke moved the page up ~64px on every page, laptop and phone. Cause:
  `html { scroll-padding-top: 88px }` (meant to keep anchor jumps clear of the sticky header) made Chrome treat the search box
  inside that sticky header as hidden under the padding, so it scrolled to reveal it on each keystroke; the box moves with the
  page, so it never got there. Fix: the 88px offset is now a zero-specificity `scroll-margin-top` on jump/focus targets
  (`:target`, `[id]`, links, buttons, inputs…) with the header's own controls at 0 (`nav.css`). Checked: no jump on Home,
  Discover, Ratings, Lists, Community and search results at 1440 and 390; `#seasons` and Tab focus still land below the header.
  The Ratings name filter and Community user search only scroll to bring an off-screen box into view (normal browser behaviour).

- **Saving or deleting a movie rating reloaded the whole title page** (user, 2026-11-24: "also does editing the rating when clicked on movie cause a refresh?"): the
  Rate modal (quick and detailed, create and edit) and the delete confirmation were plain posts that redirected back to
  `/movie/{id}`. Both now save in place like the TV seasons (`data-inplace="#your-rating, .title-panel"`): toast, "Your
  rating" and the action panel are swapped in. The unrated page renders an empty `hidden` `#your-rating` stand-in so the card
  has a region to land in (inplace.js reloads when a region's count differs). Without JS still a post and redirect. Covered by
  `MovieRatingTemplatesTest` and the `title` browser suite (create, edit quick, edit detailed, delete, `?rate=edit`, each
  with the page marker surviving).
- **"Recommended for you" never loaded after switching Discover between Movies and TV in place** (found in the browser check
  while building the row, 2026-11-23, never shipped): the row's placeholder carried `<noscript><style>...is-pending { display:
  none }</style></noscript>`. `view-swap.js` parses the new view with `DOMParser`, which runs without scripting, so the
  `<noscript>` content became a real `<style>` once imported: the placeholder was hidden, never intersected the viewport and
  was never fetched. The `<noscript>` now sits outside the row, on Home only (Home has no in-place swap); Discover's
  placeholder has none, like its other pending rows. `ForYouTemplatesTest` checks it.

- **The To rate view's All / Movies / TV tabs added a Back step each** (found in the phone-Back work, 2026-11-23): they were
  plain links, and `ratings-unrated.js` kept the section it found on load, so swapping the section in place would have left
  its "rated, leave the view" handler working on a detached copy. The section is now a view-swap.js region (the tabs replace
  the URL) and the script looks it up on each event. See design-decisions ("Ratings load as you scroll").
- **Paged rankings could repeat or skip a title** (ranking scroll, 2026-11-23, caught before shipping):
  `ROW_NUMBER() OVER (ORDER BY score DESC)` had no tiebreaker, so titles with the same score could come back in a different
  order in the next page's query. Ties now rank by rating id in both ranking queries (and in the in-memory test fakes).
- **Discover's TV mode showed movie lists in its Official lists row** (user, 2026-11-23): `DiscoverOfficialListsAdvice` loaded
  every official list whatever the mode, so `/discover?type=tv` showed "AFI 100" and the other film lists. It now passes Discover's
  `type` to the lists query, which keeps only lists with at least one title of that type (`findOfficialContaining`); none = no
  row. See design-decisions ("Watchlist opens on Movies; Discover's Official lists follow Movies / TV").

- **User search was case-sensitive, and `_` was a wildcard** (upstream, found 2026-11-23 building Community's People list):
  `UserDAO` searched `username LIKE CONCAT('%', :username, '%')`, so "Mari" missed "marisol", and since usernames may contain
  `_`, "a_b" also matched "axb". Now `LOWER(username) LIKE LOWER(:pattern) ESCAPE '!'` with `_`, `%` and `!` escaped in the
  pattern (`UsernameSearch.likePattern`), results A-Z. Still: the box needs 3+ characters (usernames' minimum length), so "an"
  finds nobody; the People list shows everyone anyway.
- **The profile follow button was an unlabeled icon** (user, 2026-11-23: "its not very clear"): a user-round-check icon to
  follow and user-round-x to unfollow, with no text and only a tooltip. Replaced by the labelled Follow / Following button; see
  design-decisions ("Community: People and Follow buttons").
- **Rating several seasons of a show reloaded the TV page after each one** (user, 2026-11-23: "on a tv show rating when im
  rating mult seasons it reloads the page after each rating"): the season rate modal and the delete confirmation were plain
  form posts that redirected back to `/tv/{id}`, so every season cost a full page load (and a TMDB call) and the scroll
  jumped. Both forms now save in place (`data-inplace="#seasons, .title-panel"`, `static/inplace.js`): the modal closes, a
  toast confirms, the season cards and the action panel (your overall score, Seen) are swapped in from the page. The cards'
  expand handler moved from an inline script to a delegated `static/tv-seasons.js` (an open card stays open after the swap),
  and `breakdown.js` redraws category bars in swapped-in regions (`inplace:swapped` event). Without JS it is still a post and
  a redirect.
- **Two search boxes on the search results page** (user, phone, 2026-11-23): the earlier fix removed the Browse bar's field on
  Home, but `/?q=…` still had its own "Search movies & TV shows" box under the header search. It's gone; see design-decisions.
- **Going back to Discover's grid or search results only showed the first page again** (found building the back links,
  fixed 2026-11-23 in "One Back leaves the page"): every page is sent with `Cache-Control: no-store` (Spring Security's
  default), so Chrome keeps no page in its back/forward cache and Back (or the title page's back link) reloads the page, losing
  the pages "Load more" / infinite scroll had added. `browse.js` and `search.js` now remember the loaded pages and the scroll
  position per tab and, on Back / Forward, reload or the "← Discover" / "← Search results" link, fetch those pages again and
  scroll back. Allowing the back/forward cache was the other idea; it would also keep stale Seen / bookmark states.
- **Every Ratings filter and list tab added a history entry** (2026-11-23, user: "the back button thing is REAL annoying on the
  phone"): `ratings-filters.js` and `list-filter.js` used `history.pushState`, so the phone's Back stepped back through each filter
  before leaving the page. They now replace the URL (keeping back-nav.js's state); Discover, the list Sort menu and the watchlist
  tabs switch in place too instead of loading a page. Housekeeping in the same change: `browse.js` still had a grid bookmark
  handler for `form.browse-bookmark`, which no template renders since the poster quick actions (`tile-actions.js`); removed.
- **Poster buttons kept their hover look after a tap on phones** (found building the poster chooser, 2026-11-23): touch
  browsers keep `:hover` on the last element tapped, so a tapped bookmark / eye / ★ stayed enlarged and recoloured, and the
  new "⋯" turned orange-on-orange (dots invisible) while its chooser was open. Hover styles on poster buttons now only apply
  under `@media (hover: hover)`.
- **Tapping the dimmed page around the phone rating picker opened the poster under it** (tile rating, found in the poster
  chooser's browser check at 360px, 2026-11-23): `tile-rate.js` closed the picker on `pointerdown`, which hid the backdrop
  before the tap's `click`, so the click landed on whatever poster was underneath and opened that title. The backdrop now
  closes the picker on its own click (the chooser's backdrop works the same way).
- **The rating picker and the tile list sheet gave the focus back to a hidden button** (2026-11-23): on touch screens the ★ and
  list buttons are now hidden (the chooser opens them), and `focus()` on a hidden element does nothing, so the focus was lost.
  On touch screens `tile-rate.js` now returns it to the poster's "⋯" (the button the chooser was opened from), and points
  the picker at "⋯" while the ★ is hidden. A score tapped on a phone doesn't always take the focus either, so a focus left on
  nothing is also given back.

- **The login page loaded no fonts** (upstream, found 2026-11-23 in a browser check): `/fonts/**` wasn't public, so a signed-out
  visitor's font requests were redirected to the login page and the browser got HTML ("OTS parsing error: invalid sfntVersion").
  `/fonts/**` is now public like the stylesheets.
- **Suggestion tiles and the tile sheet's "New list" after merging** (2026-11-23): suggestion tiles now pass your score to the poster
  ★ (the rule for every tile source), and "New list" in a tile's sheet shows the rate-limit message instead of re-posting the form.
- **Series / "More like this" tiles showed an empty ★ on rated titles** (found merging tile rating with the title-page rows,
  2026-11-23): `TileActionsView.of(...)` needs `score` since tile rating; `RelatedRowViews` now passes it (test added). Rule for
  any new tile source: pass `score` (and `detailed` when known).
- **Letterboxd import messages never showed** (upstream, found 2026-11-23): the import redirected to `/profile`, which redirects
  again to `/profile/{id}`, and flash messages only survive one redirect, so "Could not parse the CSV file" was never seen. The
  import now redirects straight to `/profile/{id}`.
- **Kotlin default values on a `@Configuration` constructor broke startup** (found 2026-11-23 by the new security-chain test):
  `SecurityConfig(..., @Value(...) val x: Boolean = false)` makes Kotlin add a second constructor; Spring then could not pick
  the primary constructor of the CGLIB subclass ("No default constructor found"). The defaults live in the `@Value`
  expressions instead.
- **Cross-origin requests were allowed with credentials** (upstream `WebConfig`: any origin plus `allowCredentials(true)`).
  The mapping no longer allows credentials, and the session and remember-me cookies are `SameSite=Lax` (2026-11-23).
- **Upstream follow buttons, rating deletes and `/status` redirected to the raw `Referer`.** They now use `BackPath`, so only a
  same-site path is used (security hardening, 2026-11-23).
- **`limit` query parameters reached the database unchecked** (`/community`, `/profile/{id}`, `/movies`, `/tvshows`): a
  negative value returned a 500. Clamped to 1-1000 (2026-11-23).
- **A superadmin could change their own password without the current one**, and the profile form hid the field for them.
  Changing your own account now always asks for it (2026-11-23).
- **Errors in `GlobalExceptionHandler` left no trace** (upstream): it turned every exception into a 500 without logging.
  It now logs the method, path and a redacted stack trace (2026-11-23).

- **Two search boxes on Home** (user, 2026-11-23): the header search and the Browse bar's "Find a title" both searched titles. The bar's field is gone; the header search is on every page.
- **"← Best Disney Movies" opened the list at the top** (found in the back-link browser check, 2026-11-23): the label and
  URL were right, but the list page never named itself, so `back-nav.js` didn't treat it as a page to come back to and the
  title kept the server's link (from the Referer), a plain link that loads the list fresh. Cause: `BackNavInterceptor` read the
  model's `list` as upstream's `CustomList`, while the list controller puts a `ListDetail` there (the unit test had used the
  wrong type too). It now reads `ListDetail`, and the test uses what the controller really adds. While checking, `back-nav.js`
  also learned to put the scroll position (and a row's sideways scroll, which no browser restores) back on Back / Forward to
  the page a title was opened from, unless the browser already did or you have scrolled yourself.
- **The back link first pushed the phone title down 28px instead of 16px** (same check): the header's bottom margin and the
  link's negative margin collapse into one, so the first margins left a bigger gap than planned. Margins now account for the
  collapse (`back-nav.css`); the title moves 16px on phones and desktop.
- **A Seen toggle on any poster outside the list changed the list's "You've seen 9 of 25"** (found 2026-11-23 while building list
  suggestions): `list-tick.js` updates the progress from every `tile:changed` event on the page, and until now every poster on a
  list page was a list title. The suggestions row's posters aren't, so `list-suggest.js` stops their event (capture phase on
  `document`) unless the tile has just been added to the list. Checked in the browser: toggling a suggestion's eye leaves
  "You've seen 1 of 10" alone.

- **No way to delete a rating from the rating editor** (user, 2026-11-23: "im not able to delete a rating when i go to edit"): the
  panel's "Edit rating" opens the rate modal, which only had Quick / Detailed / Save; the delete was a 28px trash icon at the bottom
  of the "Your rating" card. Both rate modals (movie, TV season) now show "Delete rating" under Save in edit mode; it closes the
  editor and opens the existing confirmation (`title-page.js`, the TV season travels on `data-season`).
- **Ranking cards' quick actions sat at the bottom of the page** (found 2026-11-23 in the browser check of the tile rating
  work): the new `.tile-actions` overlay is positioned against its wrapper, and the Ratings tab's new `.rank-item` wrapper had no
  `position: relative`, so every card's actions stacked in the page's bottom-right corner. Fixed in `tile-actions.css`; the same
  check added `position: relative` to `.watchlist-item`.
- **A tile's add-to-list sheet would have relabelled the title page's "In N lists" button** (found 2026-11-23 while wiring the
  tile sheet): `list-membership.js` updated every `[data-list-button]` on the page after any toggle. On a title page with
  recommendation tiles, ticking a list for a recommended title would have shown its count on the page's own button. It now
  leaves the page's button alone when the sheet belongs to a tile (`[data-tile-sheet]`) and tells the tile with a
  `list:membership` event.

- **The add-to-list toast was hidden behind the sheet** (found in the In-your-lists browser check, 2026-11-23): once the
  sheet stayed open after a tap, the "Added to X" toast (z-index 90) sat under the modal backdrop (z-index 1000). It now
  shows above the backdrop while the sheet is open, and on phones at the top of the screen so it doesn't cover the lists.
- **A just-unticked list still looked selected on phones** (same check): touch screens keep `:hover` on the last tapped
  row, and the hover style (accent border and name) read like a tick. Hover styles are now neutral on `(hover: none)`.
- **Lazily loaded title-page rows came in unstyled** (found in the browser check of "More like this", 2026-11-23): the skeleton
  section carried the `<link>`s for `home.css` / `title-related.css`, and `title-related.js` replaces that section with the fetched
  rows, so the styles were removed with it and the posters stacked as a plain list. The links now sit beside the section, and a
  template test checks they come before it. Rule for any swapped-in fragment: keep its styles outside the element that gets replaced.
- **Unchecking "Public" on an official list did nothing, silently** (open since official lists; fixed 2026-11-23): the
  `OfficialListsStayPublicRepository` decorator turned every save of an official list back to public. With official lists allowed
  to be private (hidden), the decorator is gone and the edit modal's checkbox works; the list then reads "Official · Hidden".
- **An admin marking someone else's private list official made it public** (audit-style side effect of "official implies
  public", via `PATCH /api/v1/lists/{id}`): marking and un-marking no longer change visibility, so the owner's choice stands.
- **Full-screen loader on every navigation** (user, 2026-11-23, on the watchlist): upstream's `beforeunload` overlay covered the
  page for every link, even fast ones. Replaced by a thin top bar that only appears after 400 ms.
- **Ratings count dropdown cut its number off on phones** (found in the 360px screenshots, 2026-11-23): the phone filter grid gives
  the count a quarter of the row (~76px at 360px), which leaves ~16px of text once the chevron or ✕ is in it ("25" showed as
  "2", and "100" never fit, also before). With JS the count is now a fixed 96px; the no-JS grid is unchanged.
- **Headless Chrome hangs on a screenshot taken while a navigation is pending** (browser checks, not the app): a
  `page.screenshot` between a click and the next page's commit timed out after the protocol timeout. Check the progress bar by
  sampling it in the old page (`requestAnimationFrame` into `sessionStorage`, read on the new page) or on in-place loads instead.
- **Unknown URLs answered with a JSON 500** (upstream, found 2026-11-23 while checking the trailer change): logged in, `/no-such-page`,
  `/movie/abc` or a missing `.css` returned `{"message":"Internal server error"}`. Upstream's `GlobalExceptionHandler` catches every
  `Exception`, including Spring's `NoResourceFoundException`. `system/NotFoundAdvice.kt` (ordered before it) now answers a "Page not
  found" page with a 404 (405 for a wrong method), a JSON 404 under `/api/`, and an empty 404 for missing files.
- **Trailer thumbnail only 300px wide on desktop**, leaving half the middle column empty (user screenshot, 2026-11-23). It now fills
  the column (up to 720px) with the 1280px YouTube thumbnail where one exists.
- **List items could only be appended** (2026-11-22, fixed 2026-11-23): there was no reorder or insert-at-position, so restoring a
  removed title to its rank (Coco at #3 in the Disney list) meant deleting and recreating the list. Now: reorder mode on the list
  page, `position` on API items, `PATCH .../items/{type}/{tmdbId}` and `PUT .../order` (roadmap 19).
- **Reorder mode's number field showed no digits on phones** (found in the list-order walkthrough, 2026-11-23): form.css's
  `input:not([type="checkbox"])` (18px side padding, 40px height) outweighed `.list-order-input`, leaving 4px for the number in a
  40px field. Fixed with a more specific selector (`.list-order-place input.list-order-input`); keep that in mind for any small input.
- **Drag auto-scroll crawled upwards on long lists** (found in the 40-title walkthrough, 2026-11-23): `generic.css` sets
  `scroll-behavior: smooth` on the page, so each per-frame `scrollBy` restarted a smooth scroll instead of moving. The page
  now scrolls instantly while a drag is in progress (the inline style is removed when the drag ends).
- **A moved row could end up under the fixed bottom bar**: after an arrow move the row is scrolled into view with
  `scrollIntoView({block: 'nearest'})`, which put it at the very bottom of the screen, behind the "Editing the order" bar (and the
  phone tab bar). Rows now carry `scroll-margin` for the sticky header and the bar.
- **API `position` had gaps after removals** and ties after old appends, so it didn't match the rank on the page. It is now the
  1-based place in list order (position, then id), the same number the page shows; every reorder also rewrites the stored
  positions as 1..n.

- **Official list pages said "← admin's profile"** (found in the official-lists walkthrough, 2026-11-23): the back link pointed at the owner's profile, exposing an owner the cards deliberately hide. Official lists now go back to Lists.
- **"Remove from official" wrapped onto two lines on phones**: the list title column is narrow next to the owner's Public tag and buttons. Shorter labels ("Make official" / "Remove official") that never wrap; checked at 390 and 360px.
- **api.md's Tags example swallowed the Ratings heading:** its `curl` code block was never closed.
- **TV auto-Watched was premature** (open since the statuses feature; user asked 2026-11-23): rating any season marked the whole show Watched. Now rating a season makes it Watching, and Watched only once every aired season is rated (see design decisions).
- **"Hard to change status or remove" on /watchlist** (user, 2026-11-23): tiles were plain links with no actions. Every tile now has Seen / Watching / Remove buttons, in place, with Undo.
- **Watchlist toasts wrapped onto four lines on phones** (found in the 360px screenshots): a toast fixed at `left: 50%` shrink-wraps to half the screen. `width: max-content` (capped at the screen width) fixes it; the same applies to any centered fixed toast.
- **"Watching" was cut to "Watc..." on watchlist tiles** at desktop and phone widths: three labelled buttons didn't fit a card. Narrow cards now give the first action its own row and make Remove a square ✕ (container query on the card).
- **The Upcoming badge looked indented when it wrapped inside the title** (`h1`): it now sits on its own line right under the title.
- **`hidden` loses to `display` rules again** (see the button note below): the tile stack's forms and the watchlist tiles set `display`, so `[hidden]` needed explicit `display: none` rules (`tile-actions.css`, `watchlist.css`).
- **Kotlin test names can't contain `;`** (or `:`, `.`, `/`, `<`, `>`, `[`): `Name contains illegal characters` at test-compile. Use commas or "and".

- **Ratings page posters stretched on phones.** Upstream `.card img:first-child` stretched to the card height, which grows when genre chips wrap. ratings.css keeps a 2:3 box on phones and crops with object-fit: cover elsewhere.
- **Discover genre rows were nearly all current releases** (TMDB popularity sort). Now all-time most-voted, rated 7+.

- **Flaky Discover timing test.** `GetDiscoverRowsHandlerTest` gave the instant rows only 300 ms; under parallel builds one missed it and showed as pending. The test now waits up to 1.5 s (the slow row stays blocked on a latch, so the assertion still proves the timeout path).

- **Stale classes after merging branches (local builds only).** After merging `feat/listprogress`, `HomeDashboardViewTest` failed with `NoSuchMethodError: toCard(ListSummary, boolean)`: the branch added a defaulted parameter, and Maven's incremental Kotlin compile didn't recompile `HomeDashboardView.kt`, which calls it. `mvn clean package` fixes it. Run `clean` after merges that change signatures. Railway's Docker build always starts clean, so production is not affected.

- **List page 500 for a missing or private list** (upstream, found 2026-11-23 while testing list progress): `list-detail.html` had `th:if="${isOwner}"` and `th:replace` (edit/delete modals) on the same element. Thymeleaf runs `th:replace` first, so the modals rendered with a null list ("Property 'id' cannot be found on null") instead of the "not authorized" message, and were also rendered (hidden) for non-owners. Fixed with a `<th:block th:if="${isOwner}">` wrapper.
- **No way back from a list page** (2026-11-23): you had to tap the Lists tab again. Added a "← Lists" / "← {owner}'s profile" link above the title.
- **Home "Coming soon" More link opened the unfiltered grid** (found 2026-11-23 while renaming Browse). It used `decade=upcoming`, which the parser rejects (a decade looks like `2010s`) and the page silently ignores. Now `year=upcoming`; "In theaters" also moved from the alias `sort=release_desc` to the canonical `sort=newest`. A test parses every home row's More link and fails on any ignored parameter.
- **api.md's Browse section rendered as one code block:** the Home section's `curl` example was never closed.
- **"Find a title" wrapped onto its own line in the bar at desktop widths** once a menu showed its value ("Genre · Horror"), leaving a mostly empty second row. Flex lines break on an item's basis (260px), not its 180px minimum, so the field wrapped although it fit. It now starts at 180px and grows to 260px (`browse-bar.css`); found in the Discover screenshots at 1440px.

- **White flashes when scrolling on phones.** The dark background was a gradient on `body` only (no background color), so the canvas behind it showed white during overscroll / browser-toolbar resizes. `html` now has a solid `var(--bg)` and `color-scheme: dark` (generic.css).

- **Mobile bottom nav had 6 items** after Watchlist was added. Now 5: Home (leftmost), Movies, TV, Watchlist, Lists; Browse, Tags, Community, Profile and Configuration moved into the menu sheet. (Later the same day Movies + TV became one Ratings tab, which freed the slot for Discover.)
- **Home page posters were blurry** (upstream `w185` stretched). The new home rows load `w342` (+ `w500` for 2x screens); search results and list covers too.

- **Title detail page 500 when TMDB can't load the title** (upstream, 2026-11-23): the templates read `movie.posterPath` / `show.posterPath` with no movie or show in the model. The layout and modals now render only when the title loaded; otherwise the error message and "Movie not found" / "TV show not found" show.
- **Infinite-scroll search results were blurry and built differently** (upstream `search.js`): later pages loaded `w185` posters without `srcset`, in a card structure that differed from the server-rendered one. They now build the same tile as `search.html` (`w342` + `w500`).
- **Buttons ignore the `hidden` attribute** (found 2026-11-23): the base `button { display: inline-flex }` in `form.css` beats the browser's `[hidden] { display: none }`, so the overview "More" toggle showed on desktop. Fixed with `&[hidden] { display: none }` on that button; remember it for any button toggled with `hidden`.
- **Blurry posters** (2026-11-22): tiles loaded TMDB `w185` images stretched to ~230px (worse on 2x screens). Fixed with size-appropriate images + `srcset` (see design decisions).
- **Rating modal taller than the screen** (2026-11-22): modals had no max height, so the rate modal (5 sliders + mode toggle + review) overflowed with no way to scroll. Fixed: viewport cap + internal scroll; bottom sheet on phones.
- **Title page wasted space** (2026-11-22): only the overview/genres sat beside the poster; status, providers, tags, scores and rating were stacked under it. Fixed with the two-column layout.
- **No confirmation before removing a list item** (2026-11-22): one click removed Coco from the Disney list. Fixed with `data-confirm` on remove/untag/revoke forms; Coco restored.
- **No obvious way to add to the watchlist** (2026-11-22): only the status buttons on title pages did it, unlabeled. Fixed: labeled status control ("Your watchlist") and bookmark buttons on poster tiles (`POST /watchlist/toggle`).
- **Home page too short / trending thin** (upstream): only TMDB's top 5 movies + top 5 shows, so e.g. the new Hunger Games film was missing. Replaced by the home dashboard (roadmap #10, done).
- **Secrets could reach logs** (security review): logging `RestClientException` messages would include TMDB URLs with `api_key`. Now only the exception class and status code are logged, in both the HTML and API error handlers.
- **Redirects used `http://` behind Railway's TLS proxy.** Fixed with `server.forward-headers-strategy: framework`.
- **Home/search returned `{"message":"Internal server error"}`** when TMDB rejected the key (upstream: RestClient exceptions weren't handled). Now a readable "TMDB unavailable" page (`TmdbErrorAdvice`).
- **Locked out of admin + home page 500 on first Railway deploy** (2026-11-22). Cause: the Railway variables were pasted with the setup guide's placeholders still in them, so `ADMIN_PASSWORD` became the placeholder text and `TMDB_API_KEY` stored `<your TMDB "API Key">`; upstream only reads `TMDB_API_KEY` on first start, so fixing the variable later did nothing. Fixes: admin password reset via SQL (procedure in [deploy-railway.md](deploy-railway.md#reset-the-admin-password)), env-var TMDB key now always wins and placeholders are refused, setup guide uses obvious `REPLACE_ME_...` values.
- **Railway 502 / crash loop on first deploy:** the app started without `POSTGRES_*` variables and kept trying `localhost:5432`. Fixed by adding the Postgres service and variable references; documented in [deploy-railway.md](deploy-railway.md).
- **`/**` inside a KDoc comment broke compilation:** Kotlin treats `/*` inside a comment as a nested comment opener. Don't write `/api/v1/**` in comments.
