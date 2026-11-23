# Design decisions

Why this fork is built the way it is. Newest decisions go at the bottom of each section. Related: [roadmap.md](roadmap.md), [bugs-and-issues.md](bugs-and-issues.md), [api.md](api.md).

## Product direction

- **Fork Ratelog instead of building from scratch or adopting Floppy/Scrob/Yamtrack.** The user preferred Ratelog's Letterboxd-like look and its five-category rating. Missing features (API, watchlist, tags...) are added in the fork; upstream updates are merged from `upstream/master`.
- **Movies and TV, streaming-first.** No Plex/Jellyfin integration and no media-server features; logging is manual, bulk lists come from Claude via the API.
- **No MCP connector.** The user only wants a JSON API that Claude can call; a connector was explicitly declined.
- **Upstream branding removed, credit kept.** The Buy Me a Coffee button and its third-party script were removed. AGPL-3.0 requires keeping the license and a link to the running code's source, so the profile footer links to this fork and the README credits golmenero/ratelog.

## Codebase conventions

- **Fork migrations are numbered `V1000+`** with `spring.flyway.out-of-order: true`. Upstream uses `V1..V21` and will keep adding `V22, V23...`; separate ranges mean no collisions, and out-of-order lets lower numbers apply after higher ones (verified when `V1010` applied after `V1030` on a copy of production).
- **Reserved migration ranges per feature** (see roadmap) so parallel work never collides.
- **Prefer new files over editing upstream files.** Model data for upstream pages is added with `@ControllerAdvice(assignableTypes = ...)` or interceptors instead of editing upstream controllers; templates get one `th:replace` include line per feature. Keeps upstream merges small.
- **Parallel development in git worktrees** (`../ratelog-worktrees/<feature>`), one agent per feature, append-only `messages.properties` sections per feature, merged and tested on `master` one at a time.

## API (`/api/v1`)

- **Separate stateless security chain** (`ApiSecurityConfig`, `@Order(1)`, matcher `/api/v1/**`): token auth only, no sessions, no CSRF. The browser chain is untouched; a logged-in browser session does *not* authenticate API calls.
- **Tokens:** `rl_` + 32 random bytes (base64url), stored as SHA-256 hashes, shown once, revocable at `/settings/api`. Accepted as `Authorization: Bearer` or `X-API-Key`. Last-used time is written at most once a minute.
- **Other users' lists return 404, not 403**, so ids can't be probed.
- **Batch item import never fails as a whole:** each item is `added`, `alreadyInList` or `unresolved` (with a reason and candidates). Max 100 items per request.
- **Strict title matching** (`TitleMatcher`): exact normalized title (case, accents, punctuation, leading article, `&`/`and`) against the English or original title; with a year, exact year wins, else ±1 year if only one fits; otherwise `ambiguous`/`not_found` with candidates. Rationale: a wrong title silently added is worse than asking. Real cases: Aladdin (1992) and Mulan (1998) have same-name/same-year knockoffs on TMDB; Hamilton's TMDB date moved to its 2025 re-release.
- **`type` is required with `tmdbId`** because TMDB movie and TV ids are separate namespaces.
- **API never logs TMDB exception messages** (they contain the `api_key` URL); TMDB failures map to `tmdb_error` / `tmdb_not_configured`.

## Features

- **Quick rating:** category columns nullable; a rating is either all five categories or none (+ score), enforced in handlers and by DB CHECK constraints. Category rankings exclude quick ratings; overall rankings include them. Letterboxd imports create quick ratings.
- **Statuses are separate from upstream's "follow"** (follow = release dates in Premieres). Rating a title auto-sets Watched (from none / Want to watch / Watching) via a repository wrapper, so upstream rating handlers stay untouched. *(Shows: see the TV rule below.)*
- **Tags are per user**; the Top-page tag filter narrows the list but keeps overall ranks (same as the genre filter).
- **External scores via MDBList.** There's no free official source: Rotten Tomatoes licenses only to partners and bans scraping; IMDb's API costs ~$400k/yr; OMDb lacks RT audience scores and TV. MDBList returns IMDb, RT critics/audience, Metacritic and Letterboxd in one call (free 1,000/day). Cached per title (daily for new releases, weekly within a year, monthly older, 7 days for "no data"), process-wide backoff on 429. RT is labeled "Rotten Tomatoes®" and linked; the app never scrapes RT itself. Fine for a private, login-only household instance; not for a public site.
- **Quick watchlist toggle by TMDB id** (`POST /watchlist/toggle`): poster tiles only know TMDB ids, so the endpoint saves the title locally if needed. The bookmark only toggles *Want to watch*; Watching/Watched/Dropped are left alone (changed on the title page).
- **"New list" inside the add-to-list modal** creates the list and adds the title in one step, then opens the list (same destination as adding to an existing list). *(With JS it saves in place since the reload audit; moved to the top of the sheet, see "In your lists" below.)*
- **Confirmation before destructive one-click actions** (remove from list, remove tag, revoke token) uses a `data-confirm` attribute + native `confirm()` (`static/confirm.js`): works inside modals and without extra UI code.
- **List progress is always the viewer's own**, on their lists and on other people's public lists. "Seen" = rated (a show: at least one rated season, the same rule as Browse's hide-rated filter) or status Watched; Watching, Want to watch and Dropped are not seen. Marks are read with one query per media type in the list (`ListProgressDAO`), and the `/lists` cards' "9/25 seen" with one query for the whole page. Added to the upstream list page with a `@ControllerAdvice` (`ListProgressModelAdvice`) and template include lines; the upstream list controller and handler are untouched.
- **List filters are plain links** (`/list/{id}?show=unseen` / `?show=seen`, unknown values = All) so they work without JS and can be shared. Ranks are places in the whole list (by position, then id), computed before filtering, so a filtered view never renumbers and removed titles leave no gaps.
- **"Add the N unseen to my watchlist" never touches an existing status.** N counts unseen titles with **no status** (so the button disappears once there's nothing left to add, and Watching/Dropped titles aren't "added"). The write is a single `INSERT ... SELECT ... ON CONFLICT DO NOTHING`, so even a status set a moment earlier wins; rated titles without a status are skipped. Earlier ranks get later timestamps, so the watchlist (newest first) lists them in the list's order. Asks first (`data-confirm`), flashes the count, and returns to the same tab.

- **Official lists live in their own table** (`official_lists(list_id PK -> custom_lists ON DELETE CASCADE, created_at_epoch_ms)`, `V1120`)
  instead of a column on upstream's `custom_lists`, so upstream's entity, DAO and migrations stay untouched. Deleting a list deletes its mark.
- **Official implies public.** Marking sets `is_public`, so every existing visibility check (list page, progress, profile) just works.
  While a list is official, every save keeps it public: a `@Primary` decorator on `CustomListRepository`
  (`OfficialListsStayPublicRepository`, same pattern as the auto-Watched rating repositories) covers upstream's edit form, the API
  and any future path. The API answers `isPublic: false` on an official list with `400 official_list_public` instead of ignoring it.
  Un-marking leaves the list public; the owner can make it private again.
- **Only ADMIN / SUPERADMIN mark lists official, on any list.** The handler reads the role from the database, not the session, so a
  demoted admin's open session can't keep doing it. The web switch shows on lists the admin can open; the API reaches any list id
  (including someone else's private list, which marking makes public). Changing an official list's name, visibility or titles stays
  the owner's: an admin can un-mark it but not edit it.
- **API visibility:** official lists are readable by any token (`GET /api/v1/lists/{id}`), with the token user's own progress; other
  users' ordinary lists stay `404`. Changes to someone else's official list are `403 forbidden` rather than `404`, since the id is no
  secret. Changing `isOfficial` needs an admin token, but sending the value it already has is a no-op for anyone, so a script can
  send a `GET` response back without tripping over the field.
- **The Lists tab shows official lists as a row, not a grid** (home "Your lists" collage cards, up to 20, "See all N" to
  `/lists/official`). The admin has 26 official lists: as a grid they would push "My lists" about two screens down on desktop and
  five on a phone. Official lists are excluded from "From people you follow" and "More public lists" in the SQL itself, so the
  "Load more" page size and flag aren't thrown off. They are **kept under the owner's "My lists"** (the owner's view of everything
  they own), with the badge in place of the Public pill.
- **Official cards show no owner**: the badge takes the owner / visibility slot, so the list reads as the site's (Letterboxd shows
  its official lists the same way), with the viewer's "9/25 seen". For the same reason the list page's back link reads "← Lists"
  on an official list instead of "← admin's profile" (`OfficialListBackLinkInterceptor`, set after the list advices ran).
- **The add-to-list sheet shows a title's rank only on numbered lists** (2026-11-23, after list order merged): on a plain list
  the row has the tick but no "#3", the remove question uses the no-rank wording, and the API's `inLists[].rank` is `null`.
- **Order: most recently active first** (created, or a title added), the rule every other list section uses. Empty official lists
  are shown, unlike empty public lists, because an admin picked them.
- **Discover: the Official lists row comes right after "Popular now"**, not at the end. At 1440x900 it is fully above the fold;
  after the 13 genre rows (most of them loaded on scroll) hardly anyone would reach it. "Popular now" stays first as the "what's new"
  row. The same row shows on the Movies and TV landings (lists mix both) and never on the filtered grid. It needs no TMDB, so it
  still shows when every TMDB row failed. The model value is a Thymeleaf `LazyContextVariable`, loaded only when the landing renders
  the row, so the grid, "Load more" fragments and the many `/discover/row` fetches run no extra queries.
- **One include per template**: `lists.html` (the row), `list-detail.html` (badge + admin switch under the title) and
  `fragments/discover-rows.html` (after the first row). The switch posts a CSRF form, asks first (`data-confirm`: marking says the
  list becomes public) and redirects back with a flash message.
- **Seen / Watchlist instead of four statuses** (user, 2026-11-23: "what's the point of want to watch / watching / watched / dropped when I have lists and ratings?"). The UI is Letterboxd-simple; the data model and `/api/v1/statuses` are unchanged (one status per title, plus the rating). **Seen** = status Watched, or rated; **Watchlist** = Want to watch. Watching / Dropped only show for TV (a small control), plus a "Marked Dropped · Clear" chip on a movie that still has one from before.
- **A rated movie stays Seen; tapping Seen explains instead of un-seeing.** Un-seeing it would mean deleting the rating (destructive), and keeping the rating while clearing Watched would change nothing visible (rated still counts as seen everywhere). So the toggle is locked on with a "Rated" note, and a tap says "You rated it, so it counts as seen. Delete the rating to un-see it." Letterboxd behaves the same way. Seen from Want to watch / Watching / Dropped sets Watched; un-seeing clears the status to none (it never restores the old one).
- **Shows: the status decides, not "has a rated season".** With the new TV rule (below) a show with one rated season is Watching, so showing it as Seen would contradict that. For shows, Seen = Watched, or rated with no status at all (ratings from before statuses existed); Watching / Want to watch / Dropped win over rated seasons. Shows are never locked: un-seeing a show with rated seasons moves it back to Watching. List progress (`/list/{id}`, the `/lists` card counts and the API's `seen`) uses the same rule, so the tick-off counts and the tiles agree. Movies keep "rated or Watched".
- **TV auto-status:** rating a season makes the show **Watching**; it becomes **Watched** once every aired season is rated. Watched is never downgraded, Dropped never touched, deleting a season rating changes nothing; movies are unchanged (Watched unless Dropped). "Aired" comes from the stored show, the same season list the title page shows (1 to the last season, never season 0 / Specials): the stored show only has the last season's air date and the next future season date, so the last season counts as unaired when its date is unknown or after today, and one more when a different future date exists (`AiredSeasons`). TMDB only appends unaired seasons at the end; three or more unaired seasons would be under-counted by one. No TMDB call happens inside the rating save.
- **Watchlist on an unrated Seen asks first.** One status can't be both Watched and Want to watch, so adding a seen-but-unrated title to the watchlist (a rewatch) would stop it counting as seen: the button confirms. A rated title stays seen through its rating, so it doesn't ask.
- **Upcoming:** a title is upcoming when its release date (a show's first air date) is after today; released today is out. Tiles only know the date, so no date = not upcoming there; title pages also treat a dateless movie that TMDB lists as Rumored / Planned / In Production / Post Production (a show: Planned / In Production) as upcoming, shown as just "Upcoming". "Today" comes from an injected `Clock` (system zone). The badge date is short ("Dec 18") while it's under ten months away, so the year is never ambiguous, else "Sep 2027"; title pages show the full date. Upcoming titles get no Seen toggle anywhere (`POST /seen/toggle` refuses them too); the Watchlist toggle stays. Dates come from the TMDB payloads (home, Discover, search) or the stored title (lists, watchlist, personal rows, title pages; unreleased movies are refreshed by the daily sync).
- **The follow bell only where it does something** (user, 2026-11-23: it "wouldn't do anything for most" titles). Following only adds release dates to the Premieres box on the Ratings page (no notifications), so the bell shows for movies whose release date is after today or unknown, and for shows that are Returning Series / In Production / Planned. A title already followed always keeps it (on) so a follow can't be stranded. Labelled "Notify me · Adds release dates to Premieres"; small and secondary, not a heart (a heart may later mean "like").
- **"Rate it?" after Seen, never forced** (user decision 2026-11-23). After tapping Seen on a movie page with no rating, a small inline prompt offers 1-10 (one tap saves a quick rating through the Rate modal's own `POST /movie/rate`) or Skip. Not on tiles, the watchlist page or tick-off (bulk marking stays fast), not for shows (rated per season), and not without JS (Seen just saves). Saving reloads the page, like the Rate modal, because the rating card, the Rate button and Seen all change.
- **Poster quick actions:** a stack in the poster's top-right corner: the watchlist bookmark (or a Watching / Dropped badge where the bookmark can't toggle), then the Seen eye. The Watched badge became the eye. The bookmark is rendered hidden rather than left out when it can't toggle, so un-seeing can bring it back in place. With a mouse, controls that are off appear on hover / focus; on touch screens and at 900px and below they're always visible; anything on is always visible. Status icons changed so the eye means Seen everywhere: Watched = `eye`, Watching = `circle-play`.
- **Watchlist page:** tabs read **To watch · Watching · Seen · Dropped** (named after the toggles), with a one-line hint under them. Every tile has visible buttons: To watch: Seen, Watching (shows), Remove; Watching: Seen, Drop (shows), Remove; Dropped: Watching (shows), Seen, Remove; Seen: Remove, except a rated title (it would still count as seen through its rating). Upcoming titles get no Seen. **Remove is one tap with Undo**, not a confirm: the toast (or, without JS, the notice after the redirect) says "Removed "Dune" from your watchlist." with an Undo that restores the previous status. Plain POST forms to `/watchlist/item` with an explicit `back` (same tab and type filter).
- **List tick-off mode** ("Mark what you've seen", `?mark=1`): tapping a poster toggles Seen instead of opening the title; seen posters get a green check and outline, unseen an empty ring; a bar fixed above the phone tab bar shows the live count and Done (the same view without `mark`). The mode lives in the URL and every tab link keeps it, so master's in-place tab switch (`list-filter.js`) re-renders the grid in the same mode; all handlers are delegated from `document`. Without JS the posters stay links and each poster's eye is the per-poster form. Rated movies stay checked (a tap explains), upcoming titles have no toggle.
- **Explicit `back` on form posts.** The new endpoints (`/seen/toggle`, `/watchlist/item`) take a `back` field and fall back to the Referer, then `/watchlist`; only a local path (+ query, + #fragment for `back`) is ever used, so a crafted value can't redirect off-site (`BackPath`).
- **Search infinite scroll loads the server-rendered page** (`/?q=...&page=N`) and appends its tiles, instead of building tiles in JS from `/api/search`, so later pages carry the same quick actions and badges with no duplicated markup. The marks come from `SearchTileActionsInterceptor` (one query per media type), so the upstream search controller stays untouched.
- **In your lists: the add-to-list sheet shows and toggles membership** (user, 2026-11-23: "Also when I click add to list it should alr show if its added to any, any should show the add to list butotn should be diff when added to atleast one list" / "and should be able to unseclt"). Every one of your lists has a tick box: filled with the accent and a check, plus the title's place ("#3"), when the title (same type + TMDB id, i.e. the same local title) is in it. A line under the heading says "In 2 of your lists" / "In one of your lists" / "Not in any of your lists yet". Tapping an unticked list adds the title (appended, through upstream's `AddToListHandler`, as before); tapping a ticked one takes it out (through `RemoveFromListHandler`, after the same owner check). Only your own lists are shown and counted.
- **Lists that have the title come first** in the sheet, each group in the usual order (newest list first). With many lists (the admin has 26+ official lists) a ticked list could otherwise sit below the fold, and the question was "is it in any?". The order never changes while the sheet is open, so nothing moves under your finger: it is re-sorted when the sheet opens again (`list-membership.js`) and on the next page load.
- **The sheet stays open after a tap**, so several lists can be ticked in a row; it now shows state, so closing after each tap would only hide the result. The row, the summary line and the title page button update in place from the server's JSON answer (`POST /list/{id}/membership` with `X-Requested-With: fetch`), and a toast says "Added to X" / "Removed from X" (above the modal backdrop; on phones at the top of the screen, so it doesn't cover the lists). Taps are sent one at a time, so two quick taps can't leave the count out of date. Without JS, or on any error, it is a plain POST that redirects back to the title page with "Added to the list." / "Removed from the list." (`BackPath`, so only a local path).
- **The request carries the wanted state, not "toggle"** (`inList=true|false`): a double tap or a stale second tab can't do the opposite of what the sheet showed, and asking for the state it already has changes nothing.
- **Removing asks first, adding never does**: `data-confirm` (the app's pattern, works inside modals) asks "Remove from "Best Disney Movies"? It's #3 there.", because a mis-tap would lose the title's place in a ranked list (Coco and the Disney list, 2026-11-22); adding it back appends it at the end.
- **The title page button**: "Add to list" with `list-plus` until the title is in one of your lists, then "In 1 list" / "In 2 lists" with `list-checks` and the accent "on" style the Watchlist toggle uses. Tapping it still opens the sheet. It fits the phone's four-tile action row at 360px on one line (checked with "In 12 lists" too).
- **"New list" at the top of the sheet** (user, 2026-11-23: "Also put the create new list when I click add to list at the top"): one compact row (name field + "Create & add", label for screen readers only) under the heading and summary, above your lists, so at 390x844 and 360x740 the lists still start in the upper half of the sheet. Creating a list still closes the sheet (it is a finishing action) and refreshes the sheet and the button in place (`data-inplace="#addToListModal, [data-list-button]"`); without JS it opens the new list as before.
- **Built as a fork slice** (`customlist/membership`): `ListMembershipInterceptor` adds `listMembership` to the movie and TV pages from the page's own `userLists` plus one query (`ListMembershipDAO`: your lists that have the title, with its rank), so upstream's detail controllers and handlers stay untouched. The template edits are the sheet fragment, one include line for the button and one stylesheet + one script line per title page. The API's `GET /api/v1/titles/{type}/{tmdbId}` returns the same lists as `inLists`.

### Series and "More like this" on title pages (2026-11-23)

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
  modals (default on). The add-to-list sheet's "New list" form doesn't get it (another agent is reworking that sheet); lists made
  there are numbered. The forms send a `_numbered` marker so an unchecked box (which sends nothing) reads as "plain", while a form
  without the field changes nothing.
- **Reorder mode is a mode of the list page** (`/list/{id}?reorder=1`, the "Edit order" button, owner only, lists of 2+ titles),
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

## When the page reloads (audit 2026-11-23)

Rule: **small actions that change one thing on the page save in place** (no reload, no scroll jump, a toast confirms);
**navigation and changes to the whole result set load a page** (they get their own URL, and Back works).
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
| Discover "Load more", lazy rows | in place | in place |
| Search results infinite scroll | in place | in place (`search.js`) |
| List page All / Not seen / Seen tabs | reload | in place with URL + Back (`list-filter.js`) |
| List page remove item (after confirm) | reload | in place: progress, tabs and grid refresh (`#listRegion`) |
| Add to list (modal), create list + add | **navigated to the list page** | stays on the title, modal closes, toast "Added to X" *(adding: see the next row)* |
| Add-to-list sheet: tick / untick a list (untick after confirm) | — | in place, **sheet stays open** (`list-membership.js`, JSON answer): the tick, "In 2 of your lists" and the page's "In 2 lists" button update, toast |
| Add-to-list sheet: New list (at the top) | — | in place: sheet closes, sheet and button refresh (`data-inplace="#addToListModal, [data-list-button]"`), toast |
| List reorder mode: drag, "move to #", top / up / down / bottom | — | in place (`list-order.js`): the row moves at once, the server's order wins, toast; no JS = form post back to the row |
| List page Sort menu | — | **page load**: a new arrangement of the whole list with its own URL (like Discover's sort); the tabs keep it |
| List "Edit order" / "Done" | — | **page load**: a mode with its own URL, like tick-off |
| Lists tab "Load more" | reload | appends in place |
| Profile follow / unfollow user | reload | in place |
| Title page Seen, Watchlist, Watching / Dropped | reload | in place (`title-actions.js`, JSON answer): every toggle updates from the server's state |
| Title page follow bell | reload | in place (`data-inplace=".title-panel"`) |
| "Rate it?" score after Seen | — | **reload**: an explicit save that changes the rating card, Rate button and Seen |
| Watchlist page Remove / Seen / Watching / Drop | — | in place (`watchlist.js`): the tile leaves the tab, counts move, toast with Undo |
| List tick-off, poster Seen on list pages | — | in place: the check, "You've seen 9 of 25", the bar and the tab counts update (`list-tick.js`) |
| Rate / edit / delete rating (modals) | reload | **reload kept**: the rating card, panel and scores all change; an explicit Save. Since 2026-11-23 "Delete rating" is also inside the editor (edit mode), opening the same confirmation |
| Bulk "add unseen to watchlist" on a list | reload | **reload kept**: every badge changes; keeps the current tab |
| Discover bar filters, hide-seen switch, sort | page load | **page load kept**: new result set with its own URL (Letterboxd does the same) |
| Ratings Movies / TV switch and filter form | page load | **page load kept**: different page / explicit Search |
| Create / edit / delete list, settings, tokens, admin, language, logout | page load | **page load kept**: navigation or whole-page changes |

## Deployment (Railway)

- **Dockerfile build, `/api/health` healthcheck, `PORT=8080` pinned** to match the domain's target port; `server.forward-headers-strategy=framework` so redirects use https behind the proxy.
- **`ADMIN_PASSWORD`** replaces the seeded `admin`/`admin` password at startup, but only while the default is still active and only if 12+ characters; it never overwrites a password changed in the UI.
- **`TMDB_API_KEY` env var always wins** over the value stored in Admin > Configuration (upstream only read it on first start). Obvious placeholders (`<...>`, `REPLACE_ME`, whitespace) are refused with a warning. To manage the key in the UI instead, remove the variable.
- **Readable TMDB error page** instead of a raw JSON 500 when TMDB rejects the key or is down.
- **Runtime image pinned to an exact tag** (`eclipse-temurin:21.0.13.1_2-jre-alpine`, 2026-11-23) instead of the floating `21-jre-alpine`: a rebuild could reuse an old cached copy of a floating tag and miss Java security fixes. Bump the tag deliberately.

## UI

- **Title pages use a two-column layout:** poster (+ where to watch) in a sticky left column; overview, genres, actions, scores/trailer, watchlist status, tags, your rating and seasons in the right column. Single column on phones. *(Superseded by the three-column layout below.)*
- **Modals are capped to the viewport** (`max-height: 100dvh - 32px`, internal scroll) and become bottom sheets on phones.
- **Poster sizes match display size:** tiles `w342` (+ `w500` for 2x screens), title poster `w500` (+ `w780`), ranked-list thumbnails `w185` (+ `w342`), provider logos `w92`. Upstream used `w185` tiles stretched to ~230px, which looked blurry.
- **Home posters at near-full brightness** with a bottom gradient behind titles (upstream dimmed them to 60%).
- **Home is a dashboard of horizontal rows** (Letterboxd-style): trending movies, trending TV, in theaters and coming soon first (user preference), then personal rows (only when non-empty), then Classics and popular TV; TMDB rows are cached (20 each) with "See all" links into `/discover`. A failing TMDB feed drops its row instead of erroring the page.
- **Navigation:** desktop shows logo + "Ratelog" wordmark (both link home), Ratings · Discover · Watchlist · Lists · Community (Tags when enabled), and an always-visible search field (`/` focuses it). At 900px and below: logo + search + menu sheet on top (Community, Profile, Configuration, Logout; Tags when enabled), and a 5-tab bottom bar with Home leftmost (user preference), then Ratings, Discover, Watchlist, Lists.
- **One Ratings tab for movies and TV** (user request, 2026-11-23): `/ratings` redirects to the movie ranking (`?type=tv` to TV); `/movies` and `/tvshows` keep their filters and are joined by a Movies | TV Shows switch (`fragments/ratings-switch.html`). Merging them freed a bottom-bar slot for Browse.
- **Discover genre rows are all-time, not "popular now"** (user feedback 2026-11-23: "almost strictly 2026 movies"): each genre row is TMDB's most-voted titles rated 7+, so it spans decades; "Popular now" stays the row for current releases. The "More" grid uses the same filters.
- **Home Classics row**: acclaimed films from before 2000 (TMDB `vote_average.desc`, 4,000+ votes, no documentaries), placed after your own rows; it walks pages 1-3 by ISO week so it changes weekly.
- **Ranking posters keep their 2:3 shape** on phones (object-fit: cover, fixed width) instead of stretching to the card height.
- **Lists get their own tab** (`/lists`), separate from the watchlist: the watchlist is a status ("want to watch") on titles; lists are curated, ordered collections (often imported by Claude).
- **Browse uses plain links for every filter** so the URL describes the view and works without JS; the same "Browse by" bar sits at the top of the home page. The bar no longer has its own "Find a title" field (2026-11-23): with the header search it put two search boxes on Home (user: "I end up seeing two search boxes").
- **One-click bookmark on posters** only toggles Want to watch; titles already Watching/Watched/Dropped show no bookmark so a click can't silently downgrade them.
- **Tags are hidden in the web UI for now** (user request, 2026-11-23): `ratelog.features.tags` / env `RATELOG_FEATURES_TAGS` (default false) is exposed to templates as `tagsEnabled` by `system/FeatureFlagsAdvice.kt`; it hides the nav link, the title-page tags section, the Top-page tag filter and the profile button. Code, `/tags` pages, the API and list-import tags keep working.
- **List posters carry three badges** in the home tiles' style (`home.css` `.home-status` / `.home-score`): rank bottom-left, your status top-right (Want to watch included, since list pages have no bookmark button; Dropped is muted), your score bottom-right. Status badges line up with the type tag.
- **Back link above list titles** (requested 2026-11-23): "← Lists" on your own lists, "← {owner}'s profile" on someone else's. A plain link, not `history.back()`, so it works when the list was opened directly.
- **`/lists` cards show "9/25 seen"** in place of "25 titles" (your progress, also on other people's lists). The home "Your lists" row keeps the title count.
- **Browse renamed Discover** (user request, 2026-11-23). `/discover` is the canonical URL; `/browse?...` answers `301` to `/discover?...` with the query string unchanged, so old bookmarks keep working. `/api/v1/browse` is unchanged and `/api/v1/discover` is an alias of it. Code names (package `browse`, `BrowseFilters`, `BrowseHandler`) and the bar's "Browse by" label stay: the label reads as an action, as on Letterboxd.
- **Discover's landing is category rows; any filter is the grid.** With nothing chosen but Movies/TV (and the seen setting), Discover shows home-style rows: Popular now, then genres (movies: Horror, Comedy, Action, Animation, Drama, Science Fiction, Thriller, Romance, Crime, Fantasy, Mystery, Family, Documentary; TV uses TMDB's TV genres: Drama, Comedy, Crime, Sci-Fi & Fantasy, Action & Adventure, Mystery, Animation, Documentary, Reality, Family, Kids, War & Politics, Western; News/Talk/Soap are left out because their posters are hosts and logos). Any filter, sort or page opens the poster grid. A row is exactly page 1 of its "More" grid, so both share the TMDB cache. Rows reuse home.css; the landing has no Sort menu (rows have fixed orderings).
- **"Hide titles I've seen" is on by default** (user request). Seen = rated (any season, for TV) or status Watched; Watching, Dropped and Want to watch stay visible. It is a view setting, not a filter: a switch above the bar, no chip, "Clear all" keeps it, and it doesn't turn the landing into the grid. URLs only carry it when off (`hideSeen=0`), so every bar link, row "More", Load more and pending-row fetch keeps the choice. The API keeps its old behavior (seen titles only hidden with `hideSeen=true`) so existing scripts don't change. While seen titles are hidden, the More menu's "Hide titles I've rated" is redundant, so it only appears to switch an old link's setting off.
- **Top up only when hiding removed titles:** fewer than 12 visible after hiding pulls the next TMDB page (cached), up to 3 pages per row or grid page. A page that is simply short, with nothing hidden, costs no extra calls.
- **Discover landing performance:** the first 4 rows are fetched by the server in parallel on a 4-thread pool with a short queue (so one page never runs more than 4 TMDB calls at once), waiting at most 2.5 s. Rows that are slower and rows 5+ render as poster-shaped skeletons that `discover.js` fetches from `/discover/row` as they come within 800px of the viewport, at most 3 at a time. A failing row is dropped (server or browser); only when every server row fails does the page show the TMDB message. Cold cache: 4 TMDB calls before first paint; warm: none. Tunable with `ratelog.discover.eager-rows`, `parallel-rows`, `wait-ms`.
- **The seen switch is a link** (`role="switch"`, `aria-checked`; Space toggles it via JS) to keep the "plain links, URL describes the view" rule. Row bookmarks post in place (fetch) instead of reloading, so rows loaded on scroll aren't lost.
- **Title pages: three columns on desktop, an action bar on phones** (2026-11-23, replaces the two-column layout; the user found it "ehhhhhhh" at 1475px and said the phone page "wastes space"). Desktop (1100px+): poster + Where to watch | title, runtime/genres, score badges, overview, trailer thumbnail, Your rating | a sticky action panel with labeled controls (watchlist status tiles, Rate or "Edit rating" + your score, Follow, Add to list, tags when enabled; the controls are now the Seen / Watchlist / Rate / Add to list toggles and the follow bell, see "Seen / Watchlist instead of four statuses"). *(Panel contents superseded by the Seen / Watchlist toggles, see Features.)* Tablet (700-1099px): two columns, the panel moves right under the title. Phone (<700px): a 108px poster beside the title, facts, TMDB/IMDb badges and a "Play trailer" button; then a one-row action bar (Rate, Follow, Watchlist, Add to list; 60px tiles), the overview clamped to 3 lines with "More", Where to watch, Your rating, seasons. Seasons stay full width below on TV. Layout in `static/title-page.css`; one DOM, rearranged per breakpoint with grid + `display: contents`.
- **No sticky action bar on phones.** It would sit on top of the fixed 5-tab bottom nav (two bars, ~130px of an 844px screen). The action bar is already above the fold at 390x844 and 360x740, so it stays in the page flow.
- **Phone "Watchlist" opens a status sheet** (`watchStatusModal`, same `/status` form as the desktop tiles) instead of toggling, so Watching / Watched / Dropped stay reachable; the button shows the current status. Desktop and tablet keep the four status tiles inline in the panel. *(Superseded the same day by "Seen / Watchlist instead of four statuses" above: the four status tiles and the sheet were replaced by Seen · Watchlist · Rate · Add to list toggles, with Watching / Dropped only on shows.)*
- **Trailer plays in a modal.** Desktop shows a thumbnail that fills the middle column (up to 720px; it was 300px until the user asked
  "why doesn't the trailer take up this whole part", 2026-11-23), phones a small pill button. At that size the thumbnail upgrades from
  YouTube's 480px `hqdefault` to the 1280px `maxresdefault` once the latter loads at full size (`title-page.js`; YouTube serves a
  120x90 placeholder when a video has no maxres image, so the swap checks the loaded width); the youtube-nocookie iframe is created when the modal opens and removed when it closes (stops playback). Replaces the inline 640px embed.
- **Where to watch shows logo + provider name** (a lone 28px logo read as decoration) and the **JustWatch credit** ("Streaming data by JustWatch", linked) that TMDB requires wherever its watch-provider data appears.
- **Search results are a poster grid** reusing the home tiles (`static/search-results.css`, tiles from `home.css`): ~155px tiles at desktop width (`auto-fill, minmax(148px, 1fr)`), 3 per row on phones; title + year under the poster, the overview is no longer shown on tiles. Infinite-scroll pages build the same markup.
- **Phone bottom sheets hug their content** (`height: auto`, still capped at 92dvh): upstream made phone modals full height, which left the add-to-list sheet mostly empty.
