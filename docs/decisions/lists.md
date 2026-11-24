# Lists

## Standing decisions

- **"New list" inside the add-to-list modal** creates the list and adds the title in one step, then opens the list (same destination as adding to an existing list). *(With JS it saves in place since the reload audit; moved to the top of the sheet, see "In your lists" below.)*
- **List progress is always the viewer's own**, on their lists and on other people's public lists. "Seen" = rated (a show: at least one rated season, the same rule as Browse's hide-rated filter) or status Watched; Watching, Want to watch and Dropped are not seen. Marks are read with one query per media type in the list (`ListProgressDAO`), and the `/lists` cards' "9/25 seen" with one query for the whole page. Added to the upstream list page with a `@ControllerAdvice` (`ListProgressModelAdvice`) and template include lines; the upstream list controller and handler are untouched.
- **List filters are plain links** (`/list/{id}?show=unseen` / `?show=seen`, unknown values = All) so they work without JS and can be shared (with JS they switch in place and replace the URL, see "One Back leaves the page"). Ranks are places in the whole list (by position, then id), computed before filtering, so a filtered view never renumbers and removed titles leave no gaps.
- **"Add the N unseen to my watchlist" never touches an existing status.** *(Since 2026-11-24 it writes `watchlist_entries` and also skips titles already on the watchlist; see "Rewatch".)* N counts unseen titles with **no status** (so the button disappears once there's nothing left to add, and Watching/Dropped titles aren't "added"). The write is a single `INSERT ... SELECT ... ON CONFLICT DO NOTHING`, so even a status set a moment earlier wins; rated titles without a status are skipped. Earlier ranks get later timestamps, so the watchlist (newest first) lists them in the list's order. Asks first (`data-confirm`), flashes the count, and returns to the same tab. Since the compact header (2026-11-23) it is "Add N unseen to watchlist" in the list's "⋯" menu and saves in place (toast with the count); without JS it still posts and comes back to the tab.
- **The add-to-list sheet shows a title's rank only on numbered lists** (2026-11-23, after list order merged): on a plain list
  the row has the tick but no "#3", the remove question uses the no-rank wording, and the API's `inLists[].rank` is `null`.
- **List tick-off mode** ("Mark what you've seen", `?mark=1`): tapping a poster toggles Seen instead of opening the title; seen posters get a green check and outline, unseen an empty ring; a bar fixed above the phone tab bar shows the live count and Done (the same view without `mark`). The mode lives in the URL and every tab link keeps it, so master's in-place tab switch (`list-filter.js`) re-renders the grid in the same mode; all handlers are delegated from `document`. Without JS the posters stay links and each poster's eye is the per-poster form. Rated movies stay checked (a tap explains), upcoming titles have no toggle.
- **In your lists: the add-to-list sheet shows and toggles membership** (user, 2026-11-23: "Also when I click add to list it should alr show if its added to any, any should show the add to list butotn should be diff when added to atleast one list" / "and should be able to unseclt"). Every one of your lists has a tick box: filled with the accent and a check, plus the title's place ("#3"), when the title (same type + TMDB id, i.e. the same local title) is in it. A line under the heading says "In 2 of your lists" / "In one of your lists" / "Not in any of your lists yet". Tapping an unticked list adds the title (appended, through upstream's `AddToListHandler`, as before); tapping a ticked one takes it out (through `RemoveFromListHandler`, after the same owner check). Only your own lists are shown and counted.
- **Lists that have the title come first** in the sheet, each group in the usual order (newest list first). With many lists (the admin has 26+ official lists) a ticked list could otherwise sit below the fold, and the question was "is it in any?". The order never changes while the sheet is open, so nothing moves under your finger: it is re-sorted when the sheet opens again (`list-membership.js`) and on the next page load.
- **The sheet stays open after a tap**, so several lists can be ticked in a row; it now shows state, so closing after each tap would only hide the result. The row, the summary line and the title page button update in place from the server's JSON answer (`POST /list/{id}/membership` with `X-Requested-With: fetch`), and a toast says "Added to X" / "Removed from X" (above the modal backdrop; on phones at the top of the screen, so it doesn't cover the lists). Taps are sent one at a time, so two quick taps can't leave the count out of date. Without JS, or on any error, it is a plain POST that redirects back to the title page with "Added to the list." / "Removed from the list." (`BackPath`, so only a local path).
- **The request carries the wanted state, not "toggle"** (`inList=true|false`): a double tap or a stale second tab can't do the opposite of what the sheet showed, and asking for the state it already has changes nothing.
- **Removing asks first, adding never does**: `data-confirm` (the app's pattern, works inside modals) asks "Remove from "Best Disney Movies"? It's #3 there.", because a mis-tap would lose the title's place in a ranked list (Coco and the Disney list, 2026-11-22); adding it back appends it at the end.
- **The title page button**: "Add to list" with `list-plus` until the title is in one of your lists, then "In 1 list" / "In 2 lists" with `list-checks` and the accent "on" style the Watchlist toggle uses. Tapping it still opens the sheet. It fits the phone's four-tile action row at 360px on one line (checked with "In 12 lists" too).
- **"New list" at the top of the sheet** (user, 2026-11-23: "Also put the create new list when I click add to list at the top"): one compact row (name field + "Create & add", label for screen readers only) under the heading and summary, above your lists, so at 390x844 and 360x740 the lists still start in the upper half of the sheet. Creating a list still closes the sheet (it is a finishing action) and refreshes the sheet and the button in place (`data-inplace="#addToListModal, [data-list-button]"`); without JS it opens the new list as before.
- **Built as a fork slice** (`customlist/membership`): `ListMembershipInterceptor` adds `listMembership` to the movie and TV pages from the page's own `userLists` plus one query (`ListMembershipDAO`: your lists that have the title, with its rank), so upstream's detail controllers and handlers stay untouched. The template edits are the sheet fragment, one include line for the button and one stylesheet + one script line per title page. The API's `GET /api/v1/titles/{type}/{tmdbId}` returns the same lists as `inLists`.
- **Lists get their own tab** (`/lists`), separate from the watchlist: the watchlist is a status ("want to watch") on titles; lists are curated, ordered collections (often imported by Claude).
- **Back link above list titles** (requested 2026-11-23): "← Lists" on your own lists, "← {owner}'s profile" on someone else's. A plain link, not `history.back()`, so it works when the list was opened directly. *(Title pages work differently: they go back to the page you came from, see "Back links on title pages" under Features.)*
- **`/lists` cards show "9/25 seen"** in place of "25 titles" (your progress, also on other people's lists). The home "Your lists" row keeps the title count.

## Suggested for this list (2026-11-23)

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

- **An ✕ in the header search** (user, 2026-11-24: "doesn't make it easily able to click the x on that search at the top in
  the searchbar maybe an x could go there?"). While the box has text, a 30px round ✕ sits at its right end (it replaces the "/"
  hint); tapping it empties the box and keeps the cursor there, without leaving the page. The browser's own small cancel glyph
  is hidden so there is only one ✕ (`nav.js`, `nav.css`). Searching stays name-only: Discover's genre / rating / service filters
  don't carry into search, because a name search should find the title whatever the filters say and TMDB's search can't filter
  by those anyway (a Year box for search is queued).

## Compact list header (2026-11-23)

User, 2026-11-23, with a desktop screenshot of a list page: *"Also when viewing lists, there's a lot here and it takes up a lot
of space and looks messy/unorganized w all the buttons"*. The top of `/list/{id}` had grown to five stacked rows: back link;
title with Public, pencil and trash far right; "Make official" (admins); "You've seen 3 of 13" + bar; the tabs, "Mark what you've
seen" in the middle and a big orange "Add the 10 unseen to my watchlist" on the right; then Sort left and "Edit order" right.
Actions sat left, centre and right with no grouping.

- **Three parts, in reading order.** (1) Title row: the name and one **"⋯" List actions** button (40px, outlined like Sort).
  (2) A small **meta line**: Official badge, Public pill, "You've seen 3 of 14" and a 120px bar (88px on phones). (3) **One
  toolbar row**: All / Not seen / Seen, Sort, and "Mark what you've seen" pushed right. Everything else is in the menu.
- **What goes in the menu, in this order:** Add N unseen to watchlist, Edit order, Edit list, Make / Remove official (admins),
  then Delete list, set apart by a rule and in the danger colour. These are occasional, list-level actions; browsing (tabs,
  Sort) and the one frequent action (tick-off, which the user asked for the same day) stay one tap away. The menu only shows when
  it has something in it: other people see it only while there are titles to add; signed-out visitors can't open lists at all.
- **"Add N unseen to watchlist", not a big orange button.** Shorter label, a menu item, and it now **saves in place** like
  "All seen" (`data-inplace="#listRegion"`, toast "Added 11 titles to your watchlist."), so the menu's count and every badge
  refresh. The confirmation and the server rules are unchanged.
- **Phones (≤600px):** title row, meta line, then two control rows: the three tabs (40px tall, full width) and Sort + "Mark seen"
  (short label; the title keeps the long one). At ≤420px Sort drops the word "Sort" and keeps the arrows icon and the current
  order. No horizontal overflow at 360px; the menu panel is right-aligned under "⋯" and fits.
- **Measured** (top of "← Lists" to the first poster, 14-title list, admin owner): 1440px **301 → 169px** (4 → 2 rows of
  controls); 390px and 360px **483 → 227px** (6 → 3 bands, counting the "⋯" on the title row). Someone else's official list:
  1440px 288 → 169px, phones 430 → 250px (the meta line wraps once at 360px).
- **Modes keep working the same way.** Tick-off: the toolbar keeps the tabs and Sort, "Mark seen" gives way to the bottom bar
  (All seen / Done). Reorder: the toolbar and the progress text hide; the menu keeps Edit list, the official switch and Delete.
  An empty list still has its menu (Edit / Delete). `list-filter.js` swaps all of `#listRegion`, so the header and its menu
  re-render with every tab or Sort change (the add form keeps `show=`).
- **No-JS:** the menu is a `<details>`, its items plain forms and links; Edit / Delete open modals, which need JS as before.
  `list-header.js` only closes the menu on an outside click, Escape (focus back on "⋯") or once an item is chosen.
- Code: `fragments/list-header.html` (new), `list-header.css`, `list-header.js`; the Sort menu became its own fragment
  (`list-order :: sort`), the official badge and switch two (`official-lists :: listHeader` / `toggle`), and the progress line
  `list-progress :: line` (`list-tick.js` finds the bar by `data-list-progress-bar`).
