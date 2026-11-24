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
  Un-marking leaves the list public; the owner can make it private again. *(Superseded 2026-11-23 by "Official lists can be
  hidden" below: the decorator and `official_list_public` are gone.)*
- **Only ADMIN / SUPERADMIN mark lists official, on any list.** The handler reads the role from the database, not the session, so a
  demoted admin's open session can't keep doing it. The web switch shows on lists the admin can open; the API reaches any list id
  (including someone else's private list, which stays private: see "Official lists can be hidden"). Changing an official list's name,
  visibility or titles stays the owner's: an admin can un-mark it but not edit it.
- **API visibility:** official lists are readable by any token (`GET /api/v1/lists/{id}`), with the token user's own progress; other
  users' ordinary lists stay `404`. *(Since 2026-11-23 only public official lists; a hidden one is `404` like any private list.)* Changes to someone else's official list are `403 forbidden` rather than `404`, since the id is no
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
  `fragments/discover-rows.html` (after the first row). The switch posts a CSRF form, asks first (`data-confirm`: since 2026-11-23
  it says whether every account will see the list or it stays hidden) and redirects back with a flash message.
- **Seen / Watchlist instead of four statuses** (user, 2026-11-23: "what's the point of want to watch / watching / watched / dropped when I have lists and ratings?"). The UI is Letterboxd-simple; the data model and `/api/v1/statuses` are unchanged (one status per title, plus the rating). **Seen** = status Watched, or rated; **Watchlist** = Want to watch. Watching / Dropped only show for TV (a small control), plus a "Marked Dropped · Clear" chip on a movie that still has one from before.
- **A rated movie stays Seen; tapping Seen explains instead of un-seeing.** Un-seeing it would mean deleting the rating (destructive), and keeping the rating while clearing Watched would change nothing visible (rated still counts as seen everywhere). So the toggle is locked on with a "Rated" note, and a tap says "You rated it, so it counts as seen. Delete the rating to un-see it." Letterboxd behaves the same way. Seen from Want to watch / Watching / Dropped sets Watched; un-seeing clears the status to none (it never restores the old one).
- **Shows: the status decides, not "has a rated season".** With the new TV rule (below) a show with one rated season is Watching, so showing it as Seen would contradict that. For shows, Seen = Watched, or rated with no status at all (ratings from before statuses existed); Watching / Want to watch / Dropped win over rated seasons. Shows are never locked: un-seeing a show with rated seasons moves it back to Watching. List progress (`/list/{id}`, the `/lists` card counts and the API's `seen`) uses the same rule, so the tick-off counts and the tiles agree. Movies keep "rated or Watched".
- **TV auto-status:** rating a season makes the show **Watching**; it becomes **Watched** once every aired season is rated. Watched is never downgraded, Dropped never touched, deleting a season rating changes nothing; movies are unchanged (Watched unless Dropped). "Aired" comes from the stored show, the same season list the title page shows (1 to the last season, never season 0 / Specials): the stored show only has the last season's air date and the next future season date, so the last season counts as unaired when its date is unknown or after today, and one more when a different future date exists (`AiredSeasons`). TMDB only appends unaired seasons at the end; three or more unaired seasons would be under-counted by one. No TMDB call happens inside the rating save.
- **Watchlist on an unrated Seen asks first.** One status can't be both Watched and Want to watch, so adding a seen-but-unrated title to the watchlist (a rewatch) would stop it counting as seen: the button confirms. A rated title stays seen through its rating, so it doesn't ask.
- **Upcoming:** a title is upcoming when its release date (a show's first air date) is after today; released today is out. Tiles only know the date, so no date = not upcoming there; title pages also treat a dateless movie that TMDB lists as Rumored / Planned / In Production / Post Production (a show: Planned / In Production) as upcoming, shown as just "Upcoming". "Today" comes from an injected `Clock` (system zone). The badge date is short ("Dec 18") while it's under ten months away, so the year is never ambiguous, else "Sep 2027"; title pages show the full date. Upcoming titles get no Seen toggle anywhere (`POST /seen/toggle` refuses them too); the Watchlist toggle stays. Dates come from the TMDB payloads (home, Discover, search) or the stored title (lists, watchlist, personal rows, title pages; unreleased movies are refreshed by the daily sync).
- **The follow bell only where it does something** (user, 2026-11-23: it "wouldn't do anything for most" titles). Following only adds release dates to the Premieres box on the Ratings page (no notifications), so the bell shows for movies whose release date is after today or unknown, and for shows that are Returning Series / In Production / Planned. A title already followed always keeps it (on) so a follow can't be stranded. Labelled "Notify me · Adds release dates to Premieres"; small and secondary, not a heart (a heart may later mean "like").
- **"Rate it?" after Seen, never forced** (user decision 2026-11-23). After tapping Seen on a movie page with no rating, a small inline prompt offers 1-10 (one tap saves a quick rating through the Rate modal's own `POST /movie/rate`) or Skip. Not on tiles, the watchlist page or tick-off (bulk marking stays fast), not for shows (rated per season), and not without JS (Seen just saves). Saving reloads the page, like the Rate modal, because the rating card, the Rate button and Seen all change. *(Tiles and tick-off got their own optional prompt on 2026-11-23, see "Rate it? after Seen on tiles" below.)*
- **Poster quick actions:** a stack in the poster's top-right corner: the watchlist bookmark (or a Watching / Dropped badge where the bookmark can't toggle), then the Seen eye. The Watched badge became the eye. The bookmark is rendered hidden rather than left out when it can't toggle, so un-seeing can bring it back in place. With a mouse, controls that are off appear on hover / focus; on touch screens and at 900px and below they're always visible; anything on is always visible. Status icons changed so the eye means Seen everywhere: Watched = `eye`, Watching = `circle-play`. *(Since 2026-11-23 there is a second group bottom right: Add to list and ★, see "Rate and add to list from any poster" below.)*
- **Watchlist page:** tabs read **To watch · Watching · Seen · Dropped** (named after the toggles), with a one-line hint under them. Every tile has visible buttons: To watch: Seen, Watching (shows), Remove; Watching: Seen, Drop (shows), Remove; Dropped: Watching (shows), Seen, Remove; Seen: Remove, except a rated title (it would still count as seen through its rating). *(Layout and the Dropped order changed 2026-11-23, see "Watchlist buttons line up on every tile" below.)* Upcoming titles get no Seen. **Remove is one tap with Undo**, not a confirm: the toast (or, without JS, the notice after the redirect) says "Removed "Dune" from your watchlist." with an Undo that restores the previous status. Plain POST forms to `/watchlist/item` with an explicit `back` (same tab and type filter).
- **List tick-off mode** ("Mark what you've seen", `?mark=1`): tapping a poster toggles Seen instead of opening the title; seen posters get a green check and outline, unseen an empty ring; a bar fixed above the phone tab bar shows the live count and Done (the same view without `mark`). The mode lives in the URL and every tab link keeps it, so master's in-place tab switch (`list-filter.js`) re-renders the grid in the same mode; all handlers are delegated from `document`. Without JS the posters stay links and each poster's eye is the per-poster form. Rated movies stay checked (a tap explains), upcoming titles have no toggle.
- **Explicit `back` on form posts.** The new endpoints (`/seen/toggle`, `/watchlist/item`) take a `back` field and fall back to the Referer, then `/watchlist`; only a local path (+ query, + #fragment for `back`) is ever used, so a crafted value can't redirect off-site (`BackPath`). Since 2026-11-23 the older Referer redirects (follow buttons, deleting a rating, `/status`) use it too.
- **Manager role** (user request, 2026-11-23: "there's two types of accounts, admins and users, I want a third called managers, whose only diff between a normal user is that they can add accounts"). `Role.MANAGER` (stored as text in `users.role`, no migration needed) has the USER and MANAGER authorities, never ADMIN. Managers get an "Add account" entry (header icon and the phone menu sheet) leading to `/accounts/new`, a plain form (username, email, password) that always creates a USER account: `CreateUserHandler` refuses any other role from a manager, whatever the request says. Nothing else changes for them: no user list, no editing or deleting other accounts, no configuration, no official lists (those checks use `isAdminLike`). Admins and superadmins give or take the role in the existing admin create and edit forms (labelled "Manager"); an ADMIN may switch accounts only between USER and MANAGER, and may edit or delete managers like users. Role changes apply to open sessions at once (session revalidation). `GET /api/v1/me` reports `MANAGER`.
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
  modals. The forms send a `_numbered` marker so an unchecked box (which sends nothing) reads as "plain", while a form without
  the field changes nothing. *(Default for new lists changed the same day, below.)*
- **New lists made in the app start plain; imports stay numbered** (user, 2026-11-23: "I didn't want all lists to have to be
  ordered w numbers, should be optional"). The create-list modal's "Numbered list" box starts unticked, and a list made from the
  add-to-list sheet's "New list" is saved plain. The API keeps `numbered: true` as its default, since Claude's imports are rankings
  and watch orders. Existing lists keep their setting (no row = numbered); the owner switches any list in Edit list.
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


### Rate and add to list from any poster (2026-11-23)

Requested by the user on 2026-11-23: "Also I can click the save button to watchlist but I lowkey want a rating button that's
over it too so I don't have to click it and click back" / "If alr rated should take u to edit and display ur rating always" /
"also maybe with that rating button to anywhere add one to be able to add to list?" / "ALso under ratings should have these
buttons as well, aka anywhere I see a movie tile".

- **Four actions on every poster, in two corners.** Top right stays the status stack (bookmark or Watching / Dropped badge,
  then the Seen eye); **bottom right is a new row: Add to list, then ★**. Three or four 30px buttons stacked in one corner would
  cover 108px of a 144px-tall poster at 360px (home rows are 96px wide there, the Discover and search grids about 97px), and
  bottom right is where your score badge always sat on home, Discover and list posters, so the score stays where you look for
  it. The bottom row needs at most 81px of the 84px a 96px tile has inside its padding (a perfect score reads "10", not "10.0",
  to fit). The `.tile-actions` stack became an overlay the size of the poster (`aspect-ratio: 2/3`; 221px on list and watchlist
  cards, whose poster row is a fixed 220px) so one fragment places both corners. Off controls keep the old rule: hover / focus
  with a mouse, always visible on touch and at 900px and below.
- **Rated = "★ 8.5", always visible** (one decimal, like the score badges it replaces; the old `.home-score`,
  `.browse-user-score` and list score badges are hidden with `:has()` wherever the ★ is shown, and stay where it isn't, e.g. a
  rated upcoming title or list tick-off mode). Unrated = an outline ★ button. Colours: the light clay score text with a warm
  star, so it reads as "yours" next to the green eye and the clay bookmark.
- **Movies: ★ opens a quick-rating picker**, a popover by the ★ on desktop (flips above when there's no room below, closes when
  its row scrolls sideways) and a bottom sheet on phones (600px and below, like the app's modals). One tap on **1-10** saves a
  quick rating: the same scale as the title page's "Rate it?" nudge, and as fine as Letterboxd's half stars (ten steps). Finer
  scores stay on the title page's slider ("Detailed rating…" opens the rate modal with `?rate=detailed`). Saving is in place:
  a toast ("Rated "Heat" 8/10 and marked it seen."), every copy of the title on the page (same `data-key`) gets the chip, the
  eye turns on and locks (rating marks a movie Watched, the existing rule), list pages update "You've seen 9 of 25".
- **Already rated opens your rating for editing.** A quick rating is shown in the picker ("Your rating ★ 8.5 · Quick rating",
  the matching score highlighted) and can be changed with one tap, or deleted: "Delete rating" asks inline ("Delete your rating?
  Delete / Cancel"), no native dialog. Deleting leaves the title seen (Watched stays, like every other delete) but unlocked.
- **A detailed (five-category) rating is never flattened from a tile.** The picker shows your score with "Detailed rating" and
  an **Edit rating** link to `/movie/{id}?rate=edit`, which opens the title page's rate modal in edit mode with the categories
  filled in; no score buttons and no Delete (a detailed rating can carry a review: delete it where you can see it). The server
  refuses too (`409`), so a stale page can't do it. Tiles learn which ratings are detailed from `TileMarks` (below).
- **Its own endpoint, upstream's handlers:** `POST /movie/rate/tile` (`tmdbId`, `score`) and `/movie/rate/tile/delete`. The
  modal's `/movie/rate` can't be reused as is: it needs the local movie id (tiles only know the TMDB id, and the movie may not be
  saved yet, so the title is saved first like `/watchlist/toggle` does), only creates, and redirects; `/movie/rate/update`
  replaces the review with what is posted. `TileRateHandler` calls upstream's `AddRatingHandler` / `UpdateRatingHandler`
  (passing the stored review back, so changing the score keeps it) and `DeleteRatingHandler`, so validation, the DB rules and the
  auto-Watched repository decorator apply unchanged. Upcoming movies are refused, like Seen. `fetch` gets JSON, a plain post
  (the picker's fallback) redirects back with a flash message.
- **Shows: ★ goes to the show's seasons** (`/tv/{id}#seasons`, the same as the title page's "Rate seasons") and the chip shows
  the show's score, the average of your rated seasons. Shows are rated per season, so the tile can't rate "the show"; a season
  picker on the tile was left out because TMDB rows don't carry the season list and the title page already shows it with each
  season's rating.
- **Upcoming titles get no ★** (you can't rate what isn't out, like the missing eye); their list button moves up into the eye's
  empty slot so the bottom corner stays clear for the "Upcoming · Dec 18" badge.
- **Without JS** ★ is a plain link: a movie's page with `?rate=1` (opens Rate / Edit rating there when JS runs, e.g. after a
  middle-click), a show's seasons. The list button goes to `?lists=1` (opens the add-to-list sheet). The title page removes
  the parameter after opening (`history.replaceState`), so a reload or a shared link doesn't open it again.
- **Add to list from any poster** opens the title page's own add-to-list sheet (ticks, "#3", remove with confirm, New list on
  top) on the current page: `GET /tile/lists?tmdbId=&type=` renders it (saving the title locally if needed) and `tile-rate.js`
  shows it; its toggles are `list-membership.js`'s own, which now also tells the tile (a `list:membership` event) and leaves the
  title page's button alone when the sheet belongs to a tile. "New list" from a tile's sheet creates and adds in place and stays
  on the page. On a title page (recommendation rows) the page's own sheet is set aside while a tile's sheet is open. The list
  button shows `list-checks` once the title is in any of your lists, with a quiet style (accent icon on the dark chip, not a
  filled circle): on your own list pages every poster is in a list, and a row of orange circles was noise.
- **`TileMarks`: two per-page lookups instead of changing every tile query.** "Is it in one of my lists" and "is my rating
  detailed" come from a lazy model attribute (`tileMarks`, added to every page by `TileMarksAdvice`): each is one query for the
  whole account, run the first time a tile asks and kept for the request, so a page with 200 tiles costs at most two queries and
  a page without tiles none. The alternative was a new column in the marks queries of home, Discover, lists and search plus
  every view model in between; this keeps those slices (and other branches working on them) untouched.
- **Everywhere a poster appears:** home rows, Discover rows and grid, search, list pages, the Ratings tab, the new To rate view,
  the watchlist page and title pages (for recommendation rows). On the Ratings tab the actions sit in a row at the bottom right
  of each ranking card (the big number stays the ranked score, which can be one category's; the chip is your overall score). On
  the watchlist page the poster only gets Add to list and ★: the buttons under each card are the status moves. Tag pages (tags
  are hidden) and profiles (no posters) are left as they are.

### "Rate it?" after Seen on tiles (2026-11-23)

Requested by the user on 2026-11-23: "there's a mass mark what you've seen but it should go to rating them immediately after
(can be optional) for each. But should be on each individual tile".

- **After Seen from a tile's eye** (any poster, and list tick-off mode), an unrated released movie gets the same picker as a
  prompt on that tile: "Marked seen. Rate it?" with the ten scores, Skip and "Detailed rating…". Tick-off taps the poster, so the
  prompt points at the tile's check when the ★ is hidden there.
- **It never blocks:** no backdrop and no focus grab, so the next poster can be ticked straight away; tapping anywhere else
  closes the prompt (the title stays seen and unrated) and ticking the next poster opens it there. One tile at a time, no chain
  of modals. On phones it is a floating card above the tab bar, and above the tick-off bar in tick-off mode.
- **Optional, remembered per browser:** an "Ask to rate after Seen" switch in the prompt (on by default, `localStorage`, read
  in a try/catch so blocked storage just means "on"). While it's off the switch also shows in the ★ picker, so it can be turned
  back on.
- **Movies only:** shows are rated per season, and a "Rate seasons" link after every ticked show would only slow tick-off down.
  Not after the watchlist page's Seen button either: that tile leaves the tab at once.

### To rate: seen but not rated (2026-11-23)

Requested by the user on 2026-11-23: "Also under ratings there should be something to show movies watched but haven't rated".

- **`/ratings/unrated`, a third option in the Ratings switch: "★ To rate 12".** Titles with status Watched and no rating (a
  show: no rated season), newest Seen first, All · Movies · TV tabs with counts. The count in the switch is the hint on the
  ranking pages (one `COUNT` query per Ratings page view, `UnratedSeenCountAdvice`; hidden at 0). A new page and route rather
  than another filter in the ranking forms, which are a different thing (your ranked ratings) and were being edited elsewhere.
- **Rate in place:** every tile has the poster actions, so ★ rates a movie right there; the tile fades out and the tab and
  switch counts drop by one (`ratings-unrated.js`), and the toast says what was saved. Un-seeing a tile with its eye removes it
  too. A show's ★ opens its seasons. Up to 200 titles per tab.

### Watchlist buttons line up on every tile (2026-11-23)

Requested by the user on 2026-11-23 (screenshot: movie tiles "Seen · Remove", TV tiles "Seen · Watc… · Remove"): "also some
have buttons and others don't for moving it?", then, about the extra button: "oh and the watching thing is bc its a series
which makes sense".

- **Watching / Drop stay for shows only** (a series is something you're in the middle of; the user confirmed it makes sense).
  What changed is the layout, so movie and show tiles look intentional side by side.
- **Fixed slots, same order, equal widths:** every tile of a tab has the same number of slots (3, or 1 on the Seen tab), always
  Seen first, Watching / Drop in the middle (shows), Remove last. A movie's Seen widens into the middle slot, so Remove sits in
  the same place on every tile and all buttons share one height. Dropped now reads Seen · Watching · Remove, the same order as
  To watch (it was Watching · Seen · Remove).
- **A note fills a slot no button can**, instead of a gap: "Out Dec 18" where Seen / Watching would be on an upcoming title,
  "Rated" where Remove would be on a rated Seen title (its tooltip explains: delete the rating to un-see it).
- **Labels never cut:** on cards 210px and wider each button is the icon over a short label; on narrower cards (phones, two
  cards per row) icons only, named by `aria-label` / `title`. The lone Remove on the Seen tab keeps its label. Moves, Undo and
  the in-place behaviour are unchanged. Add to list and ★ sit on the poster of every watchlist tile (see above).

### Back links on title pages (2026-11-23)

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
  back/forward cache. Otherwise (after title-to-title taps, a rating save that reloaded the page) the link opens the origin's URL and
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
  and a second level of origins would make the rule harder to predict. There are no season or person pages yet.
- **Phones:** the link is 44px tall to tap, one line, and tucked into the gap under the header (negative margins, layered above the
  title it overlaps by a few pixels), so the title moves down 16px. The action row stays above the fold at 390x844 and 360x740
  (checked on Toy Story 3, Breaking Bad and titles opened from rows).

### Suggested for this list (2026-11-23)

The user asked: "also on suggestions maybe under personal lists have some suggestions popup that you can add/scroll through below
it?", and when asked what kind, chose recommended movies and shows, like Spotify's "Recommended songs" under a playlist. Roadmap
item 20. Code: `org.ratelog.customlist.suggest`, `fragments/list-suggest.html`, `static/list-suggest.js` / `.css`; API
`GET /api/v1/lists/{id}/suggestions`.

- **A home-style row under your own list**: "Suggested for this list", a line saying what it's based on ("Based on the 10 titles
  in this list", or "Based on 10 of the 25 titles in this list"), then 20 poster tiles that scroll sideways (arrows with a mouse,
  swipe on phones), each with a "+ Add" button under the poster, the usual Seen / Watchlist quick actions and a small "Not
  interested" x. "More suggestions" (just "More" on phones) sits where the home rows have "See all". It is below the grid, the
  Spotify placement: the list comes first, suggestions are for when you reach its end.
- **Only on your own lists, and not in Edit order or tick-off mode.** Nobody else can add to the list, so the row would be a dead
  end on other people's lists and on official lists you don't own (the admin sees none on another user's official list). Edit
  order and tick-off are modes with their own job and a fixed bottom bar. The fragment and the add endpoint check ownership again,
  so a crafted request gets nothing (`403` for adding).
- **Empty lists get no row.** There is nothing to base suggestions on, and trending titles would ignore the list's theme (a new
  "Best heist films" would get whatever is popular this week); the home page and Discover already have those rows. The row
  appears once the list has one title.
- **How titles are chosen** (`SuggestionScoring`; measured on a 10-film Pixar list, three Harry Potter films, a comedy list of 7
  films and 2 shows, 5 prestige dramas and a single film, with TMDB data of 2026-11-23):
  - *Seeds:* up to 10 list titles, so one list costs at most 10 recommendation calls. Lists of 10 or fewer use every title;
    bigger ones split the 10 between movies and shows by their share (at least one each) and, within a type, take 3 of 10 from
    the newest additions (where the list is going; a title you just added counts at once) and the rest spread evenly over the
    list from #1 (what it is about). Half from the newest was tried first: lists are often imported in one go in rank order, so
    their "newest" titles are just the bottom ranks. The 25-film "Best of the 2000s" test list (last five: Lost in Translation
    and four Pixar films) had five animated films the Pixar titles recommend in its top 10 (Inside Out 2, The Croods: A New
    Age, Spies in Disguise, Elio, The Wild Robot); with 3 of 10 one is left, and Gladiator II, Kill Bill: Vol. 2, Batman and
    The Boondock Saints come in. The cost: series are only found through sampled titles, so The Two Towers (that list has The
    Fellowship of the Ring and The Return of the King, neither sampled now) is missed (see bugs-and-issues).
  - *Score:* every seed whose TMDB recommendations have a title at place p (0-based) gives it 1 / (1 + p/8), so titles several
    list titles recommend come first and TMDB's first pick counts about three times its twentieth. A plain 1 - p/40 left Better
    Call Saul at #12 for the dramas; the steeper curve has it at #6. The sum is multiplied by a quality factor: popularity (log of
    the vote count, full at 10,000) times the TMDB average (full from 7, never under 0.6). Without it the Pixar list's top 12 had
    The Twits (133 votes), Gabby's Dollhouse: The Movie and Winnie the Pooh: Springtime with Roo, and the comedy list had Archie
    Bunker's Place (25 votes) at #2; with it Big Hero 6, Incredibles 2 and Finding Dory move into the top 10.
  - *Filter:* titles in the list, posterless ones and released ones with fewer than **50** TMDB votes are out (the title pages
    use 10; here the long tails of several seeds add up, and 25-50 vote sitcoms were the junk that got through). Upcoming titles
    have no votes yet and stay.
  - *Series:* a TMDB collection with **two or more** titles in the list is a franchise list ("Harry Potter in Order"): its missing
    entries come first, in release order (by score alone they came in vote order: Goblet of Fire, Deathly Hallows 1 and 2,
    Half-Blood Prince, Order of the Phoenix). With **one** title of the series in the list, its other entries get +1 (about one
    more seed's first pick) instead of a pinned place: TMDB's Finding Nemo recommendations don't include Finding Dory at all, yet
    it belongs on a Pixar list, while a comedy list with The Hangover shouldn't open with Part II and III. Collections are read for
    the seeds' series, the most shared first, at most 4 calls (cached a week).
  - *Media mix:* movies and shows are ranked separately and interleaved by the list's own share. Ranked together, 7 of the comedy
    list's top 12 were shows (its two sitcoms recommend the same neighbours); interleaved, it gets about one show in every four or
    five tiles. A movie-only list gets only movies, since a seed's recommendations are its own type.
  - *Not done: a diversity re-rank* (each seed's weight decaying as its picks are used, so one cluster of similar list titles
    can't fill the batch). It helped the Pixar list (Big Hero 6, Incredibles 2, Finding Dory up) but pulled CSI: NY and The
    Lincoln Lawyer into the dramas' top 10 and pushed Better Call Saul down, so the plain score stays.
- **Seen titles stay in, in their place** (unlike "More like this", which hides them): lists are usually favourites or "best of"
  canons built from titles you've seen, so hiding seen ones would hide the best fits (Toy Story 2 for a Pixar favourites list),
  and putting unseen ones first would assume the list is a watchlist. Every tile shows the Seen eye and your score instead.
- **"+ Add" saves in place and the tile stays, as "Added"** (check icon, green button, green ring on the poster), rather than
  leaving the row: nothing moves under your finger while you scroll sideways (the add-to-list sheet keeps its order for the same
  reason). The list region above (`#listRegion`, the region the in-place Remove already swaps) is fetched again, so the title
  appears at the end with its rank, the progress and tab counts include it, and its poster flashes; the page scroll is corrected
  so the row stays where it was on screen. Toast: "Added "Up" to Pixar favorites as #11." (plain lists: no rank). It goes through
  `SetListMembershipHandler`, so it is appended exactly like "Add to list", and a title Ratelog hasn't stored yet is imported the
  way opening its page does. The owner check comes before any TMDB call. Without JS (or if the fetch fails) it is a plain POST
  that comes back to the list with "Added to the list.".
- **"More suggestions" sends what the row has shown** (`skip=m862.t1396...`, the last 200, compact so it fits in a URL) instead
  of a page number: the pool can change between batches (an added title changes the seeds and the exclusions), and a page number
  would then repeat or skip titles. The server stays stateless. When nothing new is left the row says "That's all the suggestions
  for now" with "Start over", which forgets what was shown and hidden.
- **"Not interested" lasts for the session** (`sessionStorage`, per list, try/catch: without storage it only lasts until the
  page reloads): the tile fades out and its key goes into `skip`. Cheap, and no table; a permanent "never suggest" can come later.
- **A suggestion's Seen eye doesn't count in the list's progress.** list-tick.js updates "You've seen 9 of 25" from every
  `tile:changed` event on the page, so the row stops that event unless the tile has been added (then it is a list title).
- **Lazy and capped, so the list page is no slower.** The page renders a skeleton (Discover's); `list-suggest.js` fetches
  `/list/{id}/suggestions` when it is within 600px of the viewport. TMDB: up to 10 recommendation calls and 4 collection calls per
  pool, at most 4 at a time (own small pool, like Discover's rows), all through `TmdbRecommendationsClient`'s cache (a day; a week
  for collections), so lists sharing titles share calls. The scored pool is cached for 6 hours per list and language
  (`ratelog.list-suggestions.cache-hours`, `parallel`) under a signature of the list (SHA-256 of item ids, TMDB ids and
  positions): any add, remove or move builds it again, with no invalidation hooks in other code. A pool built while a TMDB call
  failed isn't kept. Each request then costs two list queries and one marks query per media type. A failure removes the row.
  Measured on the dev machine (2026-11-23, busy with other builds: the list page itself took 1.8-2.5 s): the row's first
  request after a restart 3.6 s, a list nobody had opened since 0.8-1.2 s, a cached pool about 0.5 s, the pool rebuilt
  after an add 0.9 s. "+ Add" of a title Ratelog hadn't stored took 3.2 s (the TMDB import); in the browser the tile, the
  grid and the toast updated without a reload at 1440, 390 and 360px.
- **API:** `GET /api/v1/lists/{id}/suggestions` (`limit`, `skip`) returns the same order with `reason` (`series` /
  `recommended`) and `recommendedBy`, so Claude can use it when extending a list. Owner only: `404` for other people's lists,
  `403` for an official list you don't own.

- **"All seen" in tick-off mode** (user, 2026-11-23: "if I've seen them all there should be a button to select them all on this
  little popup"): the tick-off bar gets an "All seen" button beside Done. It asks first (with the count), then runs the same Seen
  toggle as each poster's eye for every title you haven't seen (`MarkAllSeenHandler`), so the rules are identical: titles that
  aren't out yet are skipped and counted in the message, rated and Watched titles are left alone, Want to watch / Watching /
  Dropped become Watched. In place (`data-inplace="#listRegion"`), one request however long the list. No "Rate it?" prompts for
  the bulk action; rate later from the posters or the To rate tab. On phones (≤420px) the hint text hides so both buttons fit.

- **List cap: 50 per account, 200 for admins** (user, 2026-11-23: "Admin cap maybe raise for now"). Upstream capped everyone at 50;
  the admin curates the site's researched and official lists and had reached 47. `ListLimitPolicy` reads the role from the
  database (`RoleListLimitPolicy`); managers count as users. The API's `list_limit` error names both numbers.
- **One search box on results pages too** (user, 2026-11-23: "did u fix the phone seeing two search bars yet?"): the results page
  (`/?q=…`) had its own text box under the header search. The query now lives only in the header (prefilled); the page keeps
  just the Any / Movies / TV filter, which applies as soon as it changes (`search-type.js`; the search button stays for no-JS),
  and the heading reads "Results for "…"".

### Community: People and Follow buttons (2026-11-23)

Requested by the user on 2026-11-23: "Community: list every user with a follow button? --> Yes and replace current follow
button bc its not very clear, and the unfollow button or button to show alr following them isnt very clear". Roadmap item 25.

- **A People directory on Community, beside Activity.** Every account except you, A-Z (ignoring case), each row an initial
  avatar, the username, "12 ratings" (movie ratings plus rated seasons: the same rows the feed shows, so the count matches what
  you'll see after following) and a Follow / Following button; the row links to the profile. "Following 2 of 5" sits beside the
  heading. It replaces the old right column's plain list of followed names (every row now says whether you follow them); the
  "Search users" box stays, at the top of People. Desktop: Activity | People (2:1, People at least 300px). Phones (900px and
  below): **People first**, then Activity, so following someone is one tap away and the feed below can grow without moving the
  button you just tapped. One query (`CommunityPeopleDAO`: two counts and an `EXISTS` per account), no paging: a self-hosted
  instance has a handful of accounts; page it if one ever grows past a few dozen. Added through `CommunityPeopleAdvice`, so
  upstream's `CommunityController` is untouched.
- **Admins are listed like everyone else.** The user had asked "is an admin account not supposed to show in community?": nothing in
  the code or these docs ever hid admin accounts from people (user search, profiles and following all include them); the only
  related rule is that *official list cards* show no owner, so those lists read as the site's. An admin is also a real person
  on the instance (on this one, the user's own account), and hiding it would stop friends following its ratings. So the
  directory shows admins with no badge. If the admin account should become a site-only account, hiding it is one `WHERE` in
  `CommunityPeopleDAO`.
- **One labelled button, three states, one size.** Not following: **"+ Follow"**, filled accent. Following: **"✓ Following"**,
  outlined on a soft accent fill, so "on" is obvious at a glance. With a mouse it reads **"Unfollow"** (red) on hover and on
  keyboard focus, so the click's effect is visible before you click; on touch screens (no hover) a tap first asks "Unfollow
  marisol?" (`data-confirm-touch`, a touch-only variant added to `confirm.js`). Following never asks. The three labels sit in one
  grid cell with only one visible, so the button is exactly as wide in every state (no jump, in any language); 40px tall, pill
  shaped. Right after you follow someone the new button is under your pointer and would at once read "Unfollow", which looks like
  the follow failed, so it keeps reading "Following" until the pointer leaves it or focus moves (`follow-button.js`, which also
  puts keyboard focus back on the new button). Screen readers get "Follow marisol" / "Following marisol, select to unfollow".
  Used on Community and on profiles (`fragments/follow-button.html`); the old icon-only profile button (user-round-check /
  user-round-x, no text) is gone. The title page's release bell ("Notify me") is a different feature and keeps its own design.
- **The button posts the state it shows** (`POST /user/follow/set`, `following=true|false`), not upstream's toggle
  (`/user/follow`, still there): with a toggle, a stale tab or a double tap could flip the follow the wrong way. In place via
  `data-inplace`: on Community it refreshes the button, "Following 2 of 5" and the Activity feed (which now includes their
  ratings); on a profile just the button. Toast: "Following marisol." / "Unfollowed marisol."
  (short: a longer hint wrapped to four lines on a phone). `429` shows inplace.js's toast. Without JS it is a plain form post that redirects back to the page it
  came from (`back` field, same-site paths only via `BackPath`); errors are flashed and shown on Community too.
- **User search is case-insensitive** and a typed `_` matches only `_` (it was a LIKE wildcard): see bugs-and-issues.md.

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
| List page "Suggested for this list": + Add | — | in place (`list-suggest.js`, JSON answer): the tile turns "Added", the list region refreshes with the new title at the end and its rank, the poster flashes, toast; no JS = form post back to the list |
| List page "Suggested for this list": More suggestions, Start over, Not interested | — | in place: the row is fetched again (or the tile fades out); the list itself isn't touched |
| Lists tab "Load more" | reload | appends in place |
| Profile follow / unfollow user | reload | in place |
| Community People / profile Follow, Following (unfollow asks first on touch screens) | — | in place (`data-inplace`): the button, "Following 2 of 5" and the Activity feed refresh, toast |
| Title page Seen, Watchlist, Watching / Dropped | reload | in place (`title-actions.js`, JSON answer): every toggle updates from the server's state |
| Title page follow bell | reload | in place (`data-inplace=".title-panel"`) |
| "Rate it?" score after Seen | — | **reload**: an explicit save that changes the rating card, Rate button and Seen |
| Watchlist page Remove / Seen / Watching / Drop | — | in place (`watchlist.js`): the tile leaves the tab, counts move, toast with Undo |
| List tick-off, poster Seen on list pages | — | in place: the check, "You've seen 9 of 25", the bar and the tab counts update (`list-tick.js`) |
| List tick-off "All seen" (after confirm) | — | in place: the whole list region refreshes, toast with the count |
| Rate / edit / delete rating (modals) | reload | **reload kept**: the rating card, panel and scores all change; an explicit Save. Since 2026-11-23 "Delete rating" is also inside the editor (edit mode), opening the same confirmation |
| Poster ★: rate, change or delete a quick rating (every poster) | — (title page only) | in place (`tile-rate.js`, `POST /movie/rate/tile`, JSON): every copy's chip and eye update, list progress counts, toast |
| Poster ★ on a detailed rating, "Detailed rating…" | — | **page load**: the title page opens its rate modal (`?rate=edit` / `?rate=detailed`); five categories need the full form |
| Poster Add to list (every poster) | — (title page only) | in place: the title page's sheet loads on the page (`GET /tile/lists`), ticks save in place, the poster's button ticks; New list stays on the page |
| "Rate it?" after Seen on a poster / in tick-off | — | in place, optional prompt on that tile; Skip or the next tap closes it |
| To rate view: rate a title | — | in place: the tile leaves, counts drop |
| Watchlist poster ★ | — | in place: the tile leaves To watch (rating marks it seen), tab counts move |
| Bulk "add unseen to watchlist" on a list | reload | **reload kept**: every badge changes; keeps the current tab |
| Discover bar filters, hide-seen switch, sort | page load | **page load kept**: new result set with its own URL (Letterboxd does the same) |
| Ratings Movies / TV / To rate switch | page load | **page load kept**: a different page |
| Ratings filters (genre, score type, name, count) | page load on Search | **in place with URL + Back** (`ratings-filters.js`, 2026-11-23): a dropdown applies on change, the name after a 450 ms pause or Enter; ✕ clears one filter |
| Create / edit / delete list, settings, tokens, admin, language, logout | page load | **page load kept**: navigation or whole-page changes |
| Title page back link ("← Discover", "› Toy Story 3") | — | **navigation**: `history.back()` when that page is the previous entry, else a page load of its URL with the scroll put back (`back-nav.js`) |

## Deployment (Railway)

- **Dockerfile build, `/api/health` healthcheck, `PORT=8080` pinned** to match the domain's target port; `server.forward-headers-strategy=framework` so the app knows it is on https behind the proxy (HSTS, Secure cookies). Since the security hardening below, only the headers Railway's edge sets are trusted and redirects are relative.
- **`ADMIN_PASSWORD`** replaces the seeded `admin`/`admin` password at startup, but only while the default is still active and only if 12+ characters; it never overwrites a password changed in the UI.
- **`TMDB_API_KEY` env var always wins** over the value stored in Admin > Configuration (upstream only read it on first start). Obvious placeholders (`<...>`, `REPLACE_ME`, whitespace) are refused with a warning. To manage the key in the UI instead, remove the variable.
- **Readable TMDB error page** instead of a raw JSON 500 when TMDB rejects the key or is down.
- **Runtime image pinned to an exact tag** (`eclipse-temurin:21.0.13.1_2-jre-alpine`, 2026-11-23) instead of the floating `21-jre-alpine`: a rebuild could reuse an old cached copy of a floating tag and miss Java security fixes. Bump the tag deliberately.

## Security hardening (2026-11-23)

The user asked for a hardening pass on 2026-11-23. The rules now:

- **Account changes apply to open sessions at once.** Every browser request re-reads the signed-in account from the
  database (one primary-key lookup; static files skip it), in `security/SessionRevalidationFilter`, which runs after the
  remember-me filter and before authorization. A deleted account, or a session that signed in before the password
  changed, is signed out (it continues as anonymous, so pages send it to the login page). A new role, username, email or
  language replaces the session's details and authorities, so the admin URL rule and every handler's
  `currentUser.role` check see the current role. Remember-me logins follow the same rules: they load the account fresh,
  and a password change invalidates the cookie. Changing your own password keeps your current session (the profile
  form refreshes it) and ends the others. API tokens already read the account on every request.
- **Sign-in throttling** (`security/LoginThrottle`, in memory, resets on restart): 10 failed sign-ins for one username
  within 15 minutes lock that username for 15 minutes; 30 failures from one client IP lock that IP for 15 minutes. While
  locked, sign-ins are refused before the password is checked, even a correct one, and the login page says "Too many
  failed sign-in attempts. Wait 15 minutes". Locks always expire, and attempts made during a lock don't extend it.
  Unknown usernames are counted exactly like real ones, so the answers never show which accounts exist. A correct
  password clears that username's count. The client IP is the `X-Real-IP` header Railway's edge sets
  (`RATELOG_CLIENT_IP_HEADER`), else the socket address. At most 10,000 keys are tracked.
- **Password rules for new and changed passwords:** at least 8 characters, at most 72 bytes (the most BCrypt uses).
  Signing in never applies them, so older, shorter passwords keep working, also as the "current password" when changing
  to a new one. `ADMIN_PASSWORD` keeps its own 12-character minimum. Changing your own account always needs your current
  password, the superadmin included; only a superadmin editing someone else skips it.
- **Request rate limit** (approved 2026-11-23, `security/RateLimitFilter` + `RequestRateLimiter`, in memory): one token
  bucket per key for reads and one for writes (POST, PUT, PATCH, DELETE). Keys: the API token for `/api/v1`, the account
  for browser sessions, the client IP otherwise. A bucket holds a minute's worth plus a burst and refills at the
  per-minute rate. Defaults: **600 reads a minute + 100 burst, 60 writes a minute + 30 burst**, set in `application.yml`
  (`ratelog.rate-limit.*`, env `RATELOG_RATE_LIMIT_ENABLED`, `..._READS_PER_MINUTE`, `..._READ_BURST`,
  `..._WRITES_PER_MINUTE`, `..._WRITE_BURST`). Static files and `/api/health` are exempt; sign-in posts are handled by the
  throttle above before this filter. Over the limit: `429` with `Retry-After`; the API answers its usual JSON error
  (`rate_limited`), the in-place scripts get `{ok: false, message}` (or the page's error message) and show it as a toast
  instead of re-posting (the list reorder editor then reloads to show the saved order), plain form posts get a short page. Sized so heavy normal use never trips it: ticking off a
  250-title list at 1.5 taps a second, Discover's lazy rows, the add-to-list sheet and Claude's batched list imports.
- **Letterboxd import limits** (`import/letterboxd/LetterboxdImportLimits`): at most **2 MB and 10,000 rated rows** per
  file (a ratings.csv row is about 60 bytes, so 10,000 rows is about 600 KB; a bigger export can be split, and
  re-importing skips titles already rated). The file is parsed during the upload, so format and size problems show at
  once; the TMDB lookups then run in the background (the upload returns right away, and Railway closes requests that
  are idle for 5 minutes). One import per account, two at once across the app, 4 lookup threads each, and all imports
  share **10 TMDB requests a second** of the app's 40, so pages keep the rest. An import stops starting new rows after
  60 minutes. The method-wide transaction is gone: each save commits on its own and no connection is held during
  lookups. The result is logged as counts only.
- **Cookies and proxy headers.** The session cookie is `Secure` and `SameSite=Lax`; browsers accept Secure cookies on
  `http://localhost`, and `SESSION_COOKIE_SECURE=false` allows signing in over plain http from another host (a phone on
  the LAN). The remember-me cookie is `SameSite=Lax` too. `security/ProxyHeadersConfig` replaces Spring's forwarded-header
  filter with one that trusts only `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-For` (what Railway's edge
  sets) and drops `Forwarded`, `X-Forwarded-Prefix`, `X-Forwarded-Port` and `X-Forwarded-Ssl`; redirects are relative
  (`Location: /path`, status 303 behind the proxy), the login redirect included. Cross-origin requests never carry the
  browser's cookies: the CORS mapping in `WebConfig` no longer allows credentials (API calls authenticate with a token).
- **Browser security headers** (`security/SecurityHeaders`): `Content-Security-Policy` with `default-src 'self'`, images
  from `'self'`, `data:`, `image.tmdb.org` and `i.ytimg.com`, frames only from `www.youtube-nocookie.com`,
  `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`; scripts and styles from
  `'self'` plus `'unsafe-inline'`, because templates still have inline `<script>` blocks, `on*=` handlers and `style=`
  attributes (moving them into the static files would allow a strict policy; roadmap). No fonts or scripts come from a
  CDN. Also `Referrer-Policy: strict-origin-when-cross-origin` (YouTube embeds need the origin),
  `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()` (nothing the trailer iframe asks
  for), `X-Frame-Options: DENY`. The policy is **enforced**: a browser walkthrough of every page (home, Discover, title
  pages with the trailer playing, lists, tick-off, watchlist, ratings, search, profile and its modals, community, admin,
  API settings, the add-account page, login) showed no violations. `RATELOG_CSP_REPORT_ONLY=true` switches it to
  report-only without new code, in case a page breaks. New pages and scripts must stay inside this policy (no CDN, no new hosts).
- **Admin page secrets.** The TMDB key is never sent back to the browser: the field starts empty, a hint shows
  "ends in …abcd", typing a new key replaces it and saving the empty field keeps it. The form only accepts the TMDB key
  (the remember-me signing key is not editable there).
- **Redirects back to the Referer** (follow buttons, deleting a rating, the status form) go through `BackPath`: only a
  same-site path is used, anything else falls back to a fixed page.
- **Logs never carry secrets.** Upstream HTTP clients put API keys in query strings and Spring's I/O errors repeat the
  URL, so the daily TMDB sync logs the title's TMDB id and exception class only, and `GlobalExceptionHandler` (which
  used to log nothing) and the API's catch-all log the request method, path and a stack trace whose messages have
  `api_key=`, `apikey=`, `token=`... values masked (`security/LogSafe`). Malformed requests log one line without a trace.
- **`limit` parameters are clamped to 1-1000** (`system/PageLimits`) on the feed, profile and ranking pages: negative
  values used to reach the database.
- **The container runs as an unprivileged user** (uid 10001) on the pinned runtime image; the app only writes to `/tmp`.
  The build image is `maven:3.9.16-eclipse-temurin-21`.
- **Pinned dependency versions.** Spring Boot 3.5.16 is the last free 3.5 release (free support ended 2026-06-30), so
  `pom.xml` overrides the managed versions of the bundled libraries that have newer patch releases, staying on the same
  minor line: Tomcat 10.1.62, PostgreSQL JDBC 42.7.15, Jackson 2.21.8, Logback 1.5.38, Thymeleaf and its Spring Security
  extras 3.1.7.RELEASE, Hibernate Validator 8.0.6.Final (checked on Maven Central, 2026-11-23). Spring Framework 6.2.19 and
  Spring Security 6.5.11 are already the last free releases. **To bump:** read
  `https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml` for the newest patch of the same minor line
  (never a new minor or major, e.g. Tomcat 10.1.x, not 11), change the property, run `mvn -B -ntp clean package` and the
  browser check. **Drop the overrides** when moving to Spring Boot 4 (roadmap), which manages newer versions itself.

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
- **Back link above list titles** (requested 2026-11-23): "← Lists" on your own lists, "← {owner}'s profile" on someone else's. A plain link, not `history.back()`, so it works when the list was opened directly. *(Title pages work differently: they go back to the page you came from, see "Back links on title pages" under Features.)*
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
- **Ratings filters apply at once, with an ✕ per filter** (user, 2026-11-23: "if I clicking things on dropdown should just auto
  do it not require me to click search, also once select something allow an easy x without having to click dropdown (x on each
  dropdown when something selected)"). On `/movies` and `/tvshows` (`static/ratings-filters.js`, loaded by the Movies | TV Shows
  switch so the upstream templates are untouched):
  - **In place, with the URL pushed**, not a page load: the page is fetched and the ranking under the form swapped, so the
    dropdown you just used keeps focus, the page doesn't flash or jump, and Back / Forward step through filters (`popstate`
    reloads the ranking and sets the dropdowns back). Same pattern as the list page tabs (`list-filter.js`). The URL only carries
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
- **No full-screen loader** (user, 2026-11-23: "for the watchlist why does there need to be a loading animation that covers whole
  screen"). Upstream covered the page with a blurred overlay and a pulsing logo on every navigation (`beforeunload`), so even fast
  page loads flashed it. `fragments/loader.html` is now a 3px accent bar along the top edge (`.nav-progress`, `pointer-events:
  none`), shown only when a navigation takes longer than 400 ms (most local page loads finish well before, so they show
  nothing) and hidden on `pageshow` (bfcache) or after 15 s (a download link never unloads the page). The in-place Ratings
  filters use the same bar (`Loader.start()` / `Loader.stop()`). Every template keeps its one include line, so upstream merges stay
  small; the thin bar was chosen over removing the indicator entirely so a slow TMDB-backed page (title pages, Discover) still
  shows that the tap registered. `prefers-reduced-motion` gets a static bar.
- **Rotten Tomatoes search link next to IMDb's** (user, 2026-11-23: "how the IMDb search thing is there can u add a rotten
  tomatoes through search url"). Title pages (movies and TV) show a "Rotten Tomatoes® ↗" badge styled like the IMDb link
  (external-link icon, new tab, `rel="noopener noreferrer"`) whenever the English title is known. It links to RT's own search
  (`rottentomatoes.com/search?search=<title>`) because there is no free way to resolve RT's page id (see "External scores via
  MDBList"); the app never fetches RT. When MDBList has RT scores, the existing score badge is already that link, so the plain
  badge is left out and there is never a second RT badge.
