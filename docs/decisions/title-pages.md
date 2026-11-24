# Title pages

## Standing decisions

- **Title pages use a two-column layout:** poster (+ where to watch) in a sticky left column; overview, genres, actions, scores/trailer, watchlist status, tags, your rating and seasons in the right column. Single column on phones. *(Superseded by the three-column layout below.)*
- **Title pages: three columns on desktop, an action bar on phones** (2026-11-23, replaces the two-column layout; the user found it "ehhhhhhh" at 1475px and said the phone page "wastes space"). Desktop (1100px+): poster + Where to watch | title, runtime/genres, score badges, overview, trailer thumbnail, Your rating | a sticky action panel with labeled controls (watchlist status tiles, Rate or "Edit rating" + your score, Follow, Add to list, tags when enabled; the controls are now the Seen / Watchlist / Rate / Add to list toggles and the follow bell, see "Seen / Watchlist instead of four statuses"). *(Panel contents superseded by the Seen / Watchlist toggles, see Features.)* *(Since 2026-11-24 the right column scrolls with the page when it holds the Details / Cast cards; see "Credits on title pages".)* Tablet (700-1099px): two columns, the panel moves right under the title. Phone (<700px): a 108px poster beside the title, facts, TMDB/IMDb badges and a "Play trailer" button; then a one-row action bar (Rate, Follow, Watchlist, Add to list; 60px tiles), the overview clamped to 3 lines with "More", Where to watch, Your rating, seasons. Seasons stay full width below on TV. Layout in `static/title-page.css`; one DOM, rearranged per breakpoint with grid + `display: contents`.
- **No sticky action bar on phones.** It would sit on top of the fixed 5-tab bottom nav (two bars, ~130px of an 844px screen). The action bar is already above the fold at 390x844 and 360x740, so it stays in the page flow.
- **Phone "Watchlist" opens a status sheet** (`watchStatusModal`, same `/status` form as the desktop tiles) instead of toggling, so Watching / Watched / Dropped stay reachable; the button shows the current status. Desktop and tablet keep the four status tiles inline in the panel. *(Superseded the same day by "Seen / Watchlist instead of four statuses" above: the four status tiles and the sheet were replaced by Seen · Watchlist · Rate · Add to list toggles, with Watching / Dropped only on shows.)*
- **Trailer plays in a modal.** Desktop shows a thumbnail that fills the middle column (up to 720px; it was 300px until the user asked
  "why doesn't the trailer take up this whole part", 2026-11-23), phones a small pill button. At that size the thumbnail upgrades from
  YouTube's 480px `hqdefault` to the 1280px `maxresdefault` once the latter loads at full size (`title-page.js`; YouTube serves a
  120x90 placeholder when a video has no maxres image, so the swap checks the loaded width); the youtube-nocookie iframe is created when the modal opens and removed when it closes (stops playback). Replaces the inline 640px embed.
- **Where to watch shows logo + provider name** (a lone 28px logo read as decoration) and the **JustWatch credit** ("Streaming data by JustWatch", linked) that TMDB requires wherever its watch-provider data appears.

## Series and "More like this" on title pages (2026-11-23)

The user asked: "Also check if its possible when i click on movie if it can have recommedaitons related to it? For ones that have
series maybe those as well but also other ones?" Code: `org.ratelog.recommendations`, `fragments/title-related.html`,
`static/title-related.js` / `.css`.

- **Two home-style rows under the title.** Movies in a TMDB collection get **"Part of the Toy Story Collection"**: every entry in
  release order (undated ones last), this title outlined with "You're here", and your Seen eye, score and Upcoming badge on each, so
  "seen 1 and 2, 3 is next" is visible at a glance. Then **"More like this"** (movies and shows). TV has no collections on TMDB, so
  shows only get "More like this". Rows, tiles and quick actions are the home / Discover ones (`home.css`, `tile-actions`), posters
  `w342` + `w500` 2x, swipe on phones, arrows with a mouse.
- **TMDB `/recommendations`, not `/similar`.** Checked on Toy Story 2, The Wolf of Wall Street, Avengers: Doomsday, Dune: Part Three,
  Another Round, Breaking Bad and Borgen. Recommendations gave the sequels plus Monsters, Inc., Lightyear...; Wall Street, Casino, The
  Big Short...; Better Call Saul, Narcos.... `/similar` (genre / keyword overlap) gave Non Non Biyori: Vacation, Aadu 2 and Jimmy Timmy
  Power Hour for Toy Story 2, Ressha Sentai ToQger for Avengers, and many titles with 0-10 votes. So recommendations only, with no
  `/similar` fallback.
- **Seen titles are left out of "More like this"**, following Discover's "Hide titles I've seen" default (a user request): the row is
  for picking something new. The series row shows every entry, seen or not, because there seen is the point. "Seen" is the tiles'
  rule (a show you're Watching stays, a Watched one goes); watchlist and Dropped titles stay. Tapping Seen on a tile keeps it in place
  (eye on); it's gone on the next visit. When the series and seen titles leave fewer than 12, TMDB's page 2 tops the row up (20 max).
- **Junk filter:** no poster, out; released titles with fewer than 10 TMDB votes, out (upcoming ones have no votes yet and stay).
  TMDB's recommendations rarely go below about 20 votes, so this only guards odd cases. The title itself and its series are left out.
- **Placement: last on the page.** Movies: under the three-column layout, the series first. Shows: after Seasons. Seasons are the
  show's own content and where it is rated ("Rate seasons" jumps there), like the series row for a movie; recommendations lead away
  from the page, so they close it (Letterboxd's "Similar films" sit at the end too).
- **"Part of the {name}"** drops a leading "The " from TMDB's English name ("The Avengers Collection" would read "Part of the The
  Avengers Collection"). Other metadata languages show TMDB's translated name in the English sentence (see bugs-and-issues).
- **The series progress line** counts entries that are out: "5 films · Start with Toy Story", "You've seen 2 of 5 · Next: Toy Story
  3" (the first unseen one in release order), "You've seen all 4 · Next: Avengers: Doomsday, Dec 16" (the next upcoming one). Upcoming
  and undated entries can't be seen yet, so they don't count. This covers roadmap #11 on title pages.
- **This title's own tile is not a link and has no quick actions**: the page's panel already has Seen / Watchlist, and two controls
  for the same title would drift apart. It shows its Seen as a read-only eye. After the page's Seen button or a series tile changes,
  `title-related.js` fetches the rows again (TMDB is cached by then) and updates only the progress line and that eye, so nothing jumps.
  Long series (Bond, Fast & Furious) start scrolled to "You're here".
- **Lazy, so title pages are no slower.** The page renders a poster skeleton (Discover's); `title-related.js` fetches
  `/movie/{id}/related` (or `/tv/{id}/related`) once it is within 800px of the viewport (right away on desktop, on scroll on phones)
  and swaps it in, or removes the skeleton when there is nothing or anything fails. The page itself makes no extra TMDB call or query.
  TMDB calls on a cold cache: a movie's page 1 is `/movie/{id}?append_to_response=recommendations`, which also carries
  `belongs_to_collection` (one call), then `/collection/{id}`, and page 2 only for a top-up; shows use `/tv/{id}/recommendations`.
  Warm: no TMDB call, one marks query. In-memory cache (`TmdbRecommendationsClient`): recommendations 24 h, collections 7 days
  (`ratelog.recommendations.cache-hours`, `collection-cache-days`; both change rarely, and a date change still shows within a week),
  failures retried after 2 minutes. Titles use the metadata language, like the other TMDB rows. Without JS the skeleton is hidden.
- **API:** `GET /api/v1/titles/{type}/{tmdbId}/recommendations` returns both rows with your marks. Like `/api/v1/browse`, seen titles
  are only hidden with `hideSeen=true`, so Claude can see what you've seen (`seen: true`) when building lists.

- **List order** (user request, 2026-11-23: *"also are listes able to have some more order? Maybe allow them to just be added or
  some way to be able to number and order them"*). Roadmap item 19. Three parts: a per-list "Numbered list" setting, an owner-only
  reorder mode, and a read-only Sort menu for everyone.
- **Numbered is the default, and every existing list stays numbered.** Most lists are ranked imports (Top 75, IMDb Top 250, watch
  orders), where the number is the point. A plain list is a collection: no rank badges on the list page (hidden with the
  `is-plain-list` class on `#listRegion`), but it still has an order, which the API still returns and the owner can still edit.
  Stored in its own table `custom_list_options(list_id PK -> custom_lists ON DELETE CASCADE, numbered)` (`V1121`), not a column on
  upstream's `custom_lists`; **no row = numbered**, so the migration touches no data. The checkbox is in the create and edit list
  modals. The forms send a `_numbered` marker so an unchecked box (which sends nothing) reads as "plain", while a form without
  the field changes nothing. *(Default for new lists changed the same day, below.)*
- **New lists made in the app start plain; imports stay numbered** (user, 2026-11-23: "I didn't want all lists to have to be
  ordered w numbers, should be optional"). The create-list modal's "Numbered list" box starts unticked, and a list made from the
  add-to-list sheet's "New list" is saved plain. The API keeps `numbered: true` as its default, since Claude's imports are rankings
  and watch orders. Existing lists keep their setting (no row = numbered); the owner switches any list in Edit list.
- **Reorder mode is a mode of the list page** (`/list/{id}?reorder=1`, "Edit order" in the "⋯" menu since 2026-11-23, owner only, lists of 2+ titles),
  like tick-off mode's `?mark=1`: it gets its own URL and a fixed bottom bar with "Done". It swaps the poster grid for the **whole
  list in list order as thin rows**, per the user's follow-up the same day (*"make sure is able to be edited to drag thin titles up
  or down with their icons as well, something easy to reorder"*): grip, the rank (an editable number field: "move to #"), a
  36px poster (w92 / w185 for 2x), title and year, then the arrows. Rows are about 60px tall, one line, so about 10 fit between
  the header and the bar on a 390x844 phone. 1-D rows make drag targets and "move to #3" unambiguous; Letterboxd's list editor
  is a row list for the same reason. On wide screens each row has top / up / down / bottom; on phones (640px and below) up and
  down stack in one column and top / bottom move into a "⋮" menu on the row, so the title keeps ~130px at 360px.
- **Reorder mode hides the All / Not seen / Seen tabs, tick-off, the bulk watchlist button and Sort**, and ignores `show=` and
  `sort=`. Moving a title in a filtered or re-sorted view is ambiguous (what is "#3" when only the unseen titles show?), and the
  numbers in the fields must be the ranks you see. The tabs come back with "Done". `mark=1` and `reorder=1` together: reorder wins.
- **Three ways to move, all saving in place** (toast "Moved "Heat" to #3."): drag (pointer events, no library; with a mouse the
  whole row drags, with touch / pen only the grip does, so the rest of the row still scrolls the page; the lifted row follows the
  pointer, a dashed placeholder shows where it will drop and the numbers preview the new order; the page auto-scrolls when the
  pointer nears the sticky header or the bottom bar, faster the closer it gets, so a 75+ title list can be dragged end to end;
  Escape cancels), the number field (Enter, or leaving the field: phone number pads have no Enter),
  and the arrow buttons. The row moves at once; the server answers with the saved order, which wins. Moves are sent one at a time
  in the order they were made, and "Done" waits for any still saving. Without JS each row is a plain CSRF form: the number posts
  `position` (a "Move" button shows next to it), the arrows post `to=top|up|down|bottom` (they show as text arrows, since the
  Lucide icons need JS) (relative moves are worked out on the server from the saved order), and the
  page comes back in reorder mode at the row (`#order-item-{id}`) with the message. If an in-place save fails, that move is posted
  the normal way, so it's never lost silently.
- **One server rule for every move** (`MoveListItemHandler`): lock the list row (`SELECT ... FOR UPDATE`), read the items in list
  order (position, then id: the order ranks were always counted in), move the item to the clamped 1-based place, and rewrite the
  positions as 1..n in one statement (a JSON array of item ids, `jsonb_array_elements_text ... WITH ORDINALITY`, only rows whose
  position changes). The lock makes two tabs, or a script and the page, apply their moves one after the other instead of both
  renumbering the same old order. Owner only: admins don't reorder other people's lists, official lists included (the owner edits,
  the same rule as an official list's name and titles).
- **Sort is the viewer's, read-only and a plain link** (`?sort=`; unknown = list order), combined with the tabs both ways: sort
  links keep `show=` / `mark=1`, and the tabs, tick-off links and poster forms keep `sort=` (rewritten by `ListOrderInterceptor`,
  so the list-progress code is untouched). Options are only what the page already has locally, with no TMDB calls: **List order**
  (default), **Reverse order** (count a top 100 up from #100), **Title** (A-Z, ignoring case, accents and a leading The/A/An),
  **Newest / Oldest release** (year; unknown years last), **Your rating** (yours, unrated last; one marks query per media type,
  only for this sort) and **Recently added** (newest item id first, which is insertion order and survives reordering). Left out:
  TMDB score (stored at import time, so stale, and not shown on the list page: sorting by an invisible number reads as random) and
  runtime / popularity (not stored for list items). **Ties keep list order.** On a numbered list the rank badges keep the list's
  own numbers when sorted, the same rule as filtered views. Changing the sort loads a page (a new arrangement of the whole result,
  like Discover's sort); the tabs stay in place.
- **API:** `numbered` on list responses and on `POST` / `PATCH /api/v1/lists` (owner only, like name and visibility); `position`
  in responses is now the 1-based place (it used to be the stored key, with gaps after removals); an item's optional `position`
  on `POST .../items` (and on create) inserts it there, items applied in the order sent, a title already in the list is not moved;
  `PATCH /api/v1/lists/{id}/items/{type}/{tmdbId}` `{"position": n}` moves one title (`/items/{type}/{tmdbId}` like the existing
  `DELETE`, since the API has never exposed item ids); `PUT /api/v1/lists/{id}/order` sets the whole order from `{tmdbId, type}`
  pairs, titles left out following in their current order, anything unknown or repeated a `400` that changes nothing. Up to 1,000
  titles per `PUT` (no TMDB lookups). Same visibility rules as the other list endpoints: an official list is readable by any
  token, changes are `403` for anyone but the owner, other people's lists are `404`.

- **Official lists can be hidden** (user, 2026-11-23: "admin account should be able to make official lists not public"; replaces
  "Official implies public"). The official mark and the list's visibility are independent: a **private official list is hidden**, a
  draft only its owner can open, listed nowhere but the owner's own "My lists" / home row, where it reads **"Official · Hidden"**
  (muted, eye-off icon; the list page says "Official list · Hidden" with a one-line hint, since phones have no tooltip). Only
  **public** official lists are shown to everyone: the Lists tab row, `/lists/official`, the Discover row,
  `GET /api/v1/lists/official`, and the "any token can read it" rule (the SQL already filtered `is_public`, now the API's read and
  403-vs-404 checks do too). The owner hides / shows it with upstream's edit modal (Public checkbox) or `isPublic` in the API, so
  no new control was needed and the open "unchecking Public does nothing" bug is gone with the decorator that caused it.
  - **Marking and un-marking never touch visibility.** Marking a private list keeps it private (hidden) instead of publishing it;
    the confirmation and the flash say so ("It stays hidden until you make it public"). This also fixes the audit-style issue that
    an admin marking someone else's private list (API) made it public as a side effect. Why not "mark = publish" with an opt-out:
    one rule (visibility is the owner's checkbox, the mark is the admin's) is easier to predict, and a hidden official list is
    harmless: nothing changes until the owner publishes it.
  - **API:** `isPublic: false` is accepted on official lists; `400 official_list_public` no longer exists. `POST /api/v1/lists`
    with `isOfficial: true` and no `isPublic` makes a **private** (hidden) official list, the same default as every list, so a
    script that wants it shown sends `isPublic: true` too. Another user's hidden official list is `404` for reads and changes (not
    `403`): its id is nobody else's business. Admins can still un-mark any official list, hidden ones included.
  - The live site's 14 official lists are all public, so nothing changes for them.

## Back links on title pages (2026-11-23)

The user asked: "also should be more back buttons I feel like I click a movie and then cant go back to like the discover page I
was just on or something? Obviously this shouldn't go to infinity especially if we eventually get when u click on a movie it shows
recommendations but". Code: `org.ratelog.backnav`, `fragments/back-nav.html`, `static/back-nav.js` / `.css`.

- **Movie and TV pages get a back link above the title**, in the list page's style: "← Discover", "← Search results", "← Best
  Disney Movies", "← Watchlist", "← Ratings", "← Lists", "← Official lists", "← marisol's profile", "← Your profile", "← Community",
  "← Home". It returns to that page **as you left it**: the same URL (Discover filters, the search, a list's tab even when it was
  switched in place) and the same scroll position, plus the row you had scrolled sideways if you opened the title from a row.
- **It goes back to the origin, not one step.** The origin is the last page in this browser tab that isn't a title. A title opened
  from another title (series row, "More like this", cast later) keeps that title's origin, so after Discover → Toy Story 2 → Toy
  Story 3 → Monsters, Inc. the link still says "← Discover" and goes there in one tap. No trail grows.
- **Plus one step: the title you came from**, as a second part of the same line: "← Discover › Toy Story 3". Only the one title
  right before, never more (Toy Story 2 is not listed above). The browser's Back does the same step, but the installed app
  (`display: standalone`) has no Back button, and after a few "More like this" taps "the one I was just looking at" is the natural
  next question. The chevron reads as the path you took (Discover, then Toy Story 3, then here); both parts shorten with "…" so the
  line never wraps. The step only shows after a title-to-title tap; a title opened from a page shows the origin alone.
- **Tracked per tab in `sessionStorage`** (`back-nav.js`, loaded on every page from `fragments/nav.html`), with each history entry's
  own origin in `history.state`, so Back / Forward / reload show the right link for that entry (Discover → Toy Story 2 → Lists → a
  title, then Back twice: Toy Story 2 still says "← Discover"). Named pages are the ones people open titles from (the server names
  each page on the script tag); settings, admin and error pages are not origins, and a title opened from one of them gets the default.
  Storage access is wrapped in try/catch: without it the server's link stays.
- **How it goes back:** when the origin is the previous entry in this tab's history (you opened the title straight from it),
  `history.back()`, so the browser restores the page itself, all of it (loaded "Load more" pages, rows) when it kept it in its
  back/forward cache (when it didn't, `browse.js` / `search.js` load those pages again since 2026-11-23). Otherwise (after title-to-title taps, a rating save that reloaded the page) the link opens the origin's URL and
  the script scrolls it back to where you were, retrying for ~2 s while rows and posters load and stopping as soon as you scroll
  yourself. History then grows by one entry, like any link. Not `history.go(-n)`: counting entries breaks on in-page entries
  (`#seasons`), and overshooting would leave the app. A safety net opens the URL if `history.back()` doesn't leave the page.
- **The server renders the link first, from the Referer**, so it's right before JS runs, in a new tab (cmd-click from Discover:
  sessionStorage isn't shared with the new tab) and without JS: a page of this app (same host, path + query only, `BackPath`)
  that has a name gives "← Discover" etc. with its exact URL; a list is named only when the viewer can open it, so a crafted Referer
  can't reveal a private list's name. Anything else (opened directly, another site, a settings page) gives the default.
- **Default when opened directly: "← Home".** Home is the app's start page (logo, `start_url`, the leftmost tab), and a title
  opened directly usually comes from outside (a shared link, a bookmark, the app reopened). Discover was the other candidate, but
  "back" to a page you never visited reads oddly; Home is where an app's deep link returns.
- **Opened from another title, the server can't know the origin**, so it marks the link pending: invisible (its space kept, so
  nothing moves) until the script fills it in, or after 1.2 s if the script never runs (CSS `@media (scripting: enabled)`, so
  without JS it is visible at once). This avoids a flash of "← Home" turning into "← Discover".
- **No back links on the tab pages** (Home, Ratings, Discover, Watchlist, Lists): the top / bottom nav covers them. The list page
  and `/lists/official` keep their "← Lists" / "← {owner}'s profile" links (a fixed "up" link, see UI below). Other users'
  profiles were considered (Community sits in the phone menu) but left out: they're reached from Community or a list's owner link,
  and a second level of origins would make the rule harder to predict. There are no season pages yet; person pages (2026-11-24) take part like a title (see "People: person pages and People in search").
- **Phones:** the link is 44px tall to tap, one line, and tucked into the gap under the header (negative margins, layered above the
  title it overlaps by a few pixels), so the title moves down 16px. The action row stays above the fold at 390x844 and 360x740
  (checked on Toy Story 3, Breaking Bad and titles opened from rows).

## Seen's menu and the watch count in the action panel (2026-11-24)

From the rewatch log ([watchlist-and-seen.md](watchlist-and-seen.md#rewatch-log-watched-again-2026-11-24)); the user: *"maybe
be able to click the same button instead of another one?"* (a first build had a separate "Watched again" chip under the
toggles).

- **No fifth control.** The four equal toggles stay (Seen · Watchlist · Rate · Add to list; 2x2 on desktop, one row on tablets
  and phones). On a seen title, Seen opens a menu: a 272px popover under the button on desktop and tablets, a bottom sheet over
  a dimmed page at 600px and below (the poster chooser's `.tile-menu` styles, `tile-actions.css`; the menu is appended to the
  body because the panel clips its children). Items: Watched again, then Mark as not seen or the rated note.
- **The watch count** is one quiet row right under the toggles, "↻ Seen 2× · last Nov 24, 2026" (0.85rem, soft text, the seen
  green icon), only once there is a rewatch, so most panels look exactly as before. It adds about 36px on desktop and 32px on
  phones, below the action bar, so the bar stays above the fold at 390x844 and 360x740.
- **The Seen button's tooltip** says "Seen. Tap to log a rewatch or unmark it." on a seen title.
