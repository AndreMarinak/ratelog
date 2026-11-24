# People and credits

## Credits on title pages: director, writers, cast, age rating, tagline (2026-11-24)

Asked by the user on 2026-11-24: "also with the description is there more data we can get, director? Cast?" Agreed: a
director / writers line, the top cast with photos and characters, the age rating and the tagline. Layout picked after mockups
(`Movies/mockups/title-credits`, variant D): for laptops it was the user's own idea, "it could probably go right below seen,
watchlist, rating buttons? on the right side?"; for phones, "pick the best one for mobile … and go with it".

- **Data: no extra request.** The TMDB extras call each title page already makes appends `credits,release_dates` (movies) or
  `aggregate_credits,content_ratings` (shows); `created_by`, networks, episodes, status, tagline, budget, revenue and studios
  are in the base response. Parsed by `TitleCreditsParser` into `TitleCredits` and cached with the other extras in
  `title_extras.credits_json` (V1143, one compact JSON value, refreshed weekly like the rest). Kept: up to 3 directors, up to 6
  writers (one entry per person with their jobs: screenplay, novel, story...; story editors / staff writers are left out), the
  top 10 cast (TV: the character with the most episodes, plus their episode count), the **US** certification (theatrical
  release first; no fallback to other countries, whose scales differ), up to 3 studios / networks, budget and box office
  (TMDB's 0 = unknown), first / last air years and episode count. Text comes in English (the extras call is `en-US`), like the
  trailer and runtime.
- **Old cached rows** (no credits yet): V1143 makes them due, and `GetTitleInfoHandler` treats a due row without credits like
  a first view: it fetches while the page waits (at most `ratelog.extras.sync-timeout-ms`, 3 s), so the first view after the
  deploy already shows the cast. A failed fetch defers TMDB an hour as before, so it is not retried on every view.
- **Laptop (>= 1100px, variant D):** a **Details** card (Director / Created by, Writers with their job in grey, Studio /
  Network, Budget + Box office; TV: Aired + Episodes) and a **Cast** card (round 40px photo, name, character; six rows, "Show
  all 10" expands in place) under the action panel. The **right column now scrolls with the page** whenever it has these
  cards (`.title-col-side:has(.title-about) { position: static }`): it is often taller than the screen, and a sticky column
  taller than the screen clips its bottom (the cast) until the page ends. Making only the action panel sticky was rejected:
  inside a column that is exactly as tall as its content a sticky panel has no room to move, and stretching the column to
  the row height would make the cards scroll underneath the panel (overlap). Titles without credits keep the sticky panel.
- **Tablet and phone (< 1100px, the main session's pick):** tagline above the overview; one line under it, "Directed by X ·
  Written by Y" (TV: "Created by X"; at most 3 writers, then "+N more", the full list then sits in More details); a sideways
  **cast row** of small round photos (64px) with name and character, about half the height of the mockup's poster-style cards,
  scrolling inside itself (`overflow-x: auto`, the page never scrolls sideways); studio / budget / box office (TV: network,
  years, episodes) inside a collapsed **"More details"** (`<details>`, works without JS). Where to watch and the action bar
  stay where they were (the action bar is still above the fold). Both layouts are in the page; CSS shows one
  (`static/title-credits.css`).
- **Everywhere:** the tagline in italics above the overview, and the age rating as an outlined chip right after the runtime
  (R, PG-13, TV-MA), titled "Rated R (US)".
- **Names link only once person pages exist:** every name carries `data-person-id`; it becomes a link to `/person/{id}` only
  when the feature flag `ratelog.features.people` (`RATELOG_FEATURES_PEOPLE`, default false) is on, so no dead links ship
  before the person pages (the next feature) do.
- **Missing data degrades cleanly:** no empty cards (each card, the line, the cast row and More details render only with
  something in them), a cast member without a photo gets their initial in a circle, and a title TMDB knows nothing about looks
  exactly as before. "Show all 10" without JS: every name shows and the toggle stays hidden (`static/title-credits.js` folds
  rows 7-10 and reveals it).
- **Images:** `image.tmdb.org` only (inside the CSP): `w185` profiles; the 40px desktop photos use `w45` at 1x and `w185` at 2x.
- **API:** `credits` on `GET /api/v1/titles/{type}/{tmdbId}` ([api.md](../api.md#title-details-scores-and-trailers)).

## People: person pages and People in search (2026-11-24)

The user asked: "am I able to click on a name like tom hanks and see his movies by search bc rn search doesn't do that", and
agreed to person pages plus People in search ("yea I think those would work"). A Year box for search, queued earlier, came
with it. Code: `org.ratelog.person` (`TmdbPersonClient`, `Person.kt`, `Filmography.kt`, `detail/`, `search/`),
`templates/person.html`, `fragments/search-people.html`, `static/person-page.css`, `search-people.css`, `search-type.js`.

- **`/person/{tmdbPersonId}`**: photo (TMDB `w185`, `h632` for 2x screens), name, "Known for" (TMDB's department), born
  (date, age, place) and died (date, age at death) when TMDB knows them, then the biography's first three sentences (never past
  its first paragraph; "Jr." / "Dr." / initials don't end a sentence) with **More** as a plain `<details>`, so it works
  without JS; once open the link goes away. Then the filmography as the app's standard poster tiles (quick actions: hover with
  a mouse, "⋯" on touch; your Seen / ★ marks; the Upcoming badge), with the character or job under the title and the year.
- **One TMDB request per person**: `/person/{id}?append_to_response=combined_credits` (details and every cast and crew
  credit, movies and TV), cached in memory for a day per person and language (`TtlCache`, like Discover's pages; no table, no
  migration: the data is TMDB's and cheap to fetch again after a restart). Filters and sorts are worked out from the cached
  answer, so switching them costs no TMDB call.
- **Filters**: department tabs (Acting, then Directing, Writing, Creator, Production, Camera, Editing, Sound, Art... only the
  ones the person has, each with its title count), **All | Movies | TV** (with counts; an empty type is hidden unless chosen)
  and **Newest | Most popular**. The default department is TMDB's "known for" one, so Tom Hanks opens on Acting and
  Christopher Nolan on Directing. They are links that apply in place and replace the URL (`view-swap.js`, "One Back leaves the
  page"); the default view has no parameters (`/person/31`), others are `?dept=directing&type=movie&sort=popular`.
- **One tile per title per department**: several jobs on one film (Director and Writer under different departments stay in
  their own tabs; Screenplay and Story inside Writing) are joined ("Screenplay, Story"); several characters on one show are
  joined with " / ". Repeats ("Host" twice on SNL) are listed once.
- **Newest first** puts upcoming titles on top and titles with no date yet last (announced projects shouldn't push the
  filmography down). **Most popular** sorts by TMDB vote count (how well known a title is) rather than TMDB "popularity"
  (this week's trend), so Forrest Gump, Toy Story and The Green Mile lead for Tom Hanks.
- **Appearances that aren't roles are left out of Acting**: on TV, talk, news and reality shows and any "Self" credit (Tom
  Hanks has 96 TV cast credits, ~70 of them talk shows, award shows and interviews); in movies, "Self" (or archive footage) in
  a documentary or in a title with no genre (making-ofs, award galas). Playing yourself in a comedy stays. Crew credits are
  kept as TMDB lists them. Since 2026-11-24 a switch shows them on request: see
  [Show appearances as himself / herself](#show-appearances-as-himself--herself-2026-11-24).
- **Long filmographies show 60 tiles** and a "Show all N" link (in place, URL replaced): Tom Hanks' 180-odd acting credits
  would otherwise put ~600 KB of tiles on the page before you picked a filter.
- **Back link like a title page**: a person counts as a title in the back-link chain (`BackNavPages.isTitle` matches
  `/person/{id}`), so opened from search it reads "← Search results", from a title "← Discover › Cast Away", and a title
  opened from a person keeps the person as its one extra step.
- **People in search**: on the first results page for **Any** type, a "People" row above the title results (round photo,
  name, "Acting · Forrest Gump, Toy Story, The Green Mile"), up to 6, each linking to `/person/{id}`. Title results are
  unchanged below. Only people whose name is exactly the query, or who have a photo and some popularity (TMDB popularity
  ≥ 1), are shown: a search for "dune" finds 81 people named Dune, none of whom you meant, so no row. With Movies or TV
  chosen there is no row (the filter says what you want); no "People only" type was added. The people search runs in
  parallel with the title search (`SearchPeopleInterceptor`: started in `preHandle`, read in `postHandle`, at most 4 s), so
  the page waits for the slower of the two, not both; a TMDB failure only loses the row. Cached 30 minutes per query.
- *(Superseded by [Search Year menu](#search-year-menu-2026-11-24), 2026-11-24.)* **Year box** next to Any / Movies / TV: TMDB's own year filters (`primary_release_year` for movies, `first_air_date_year`
  for TV; both for Any). It applies like the type filter (on Enter, leaving the box or the arrows, after a short pause so a
  few arrow clicks make one search); an empty box leaves the URL; the URL keeps `year=`, later pages (infinite scroll) and the
  type filter keep it. A new header search starts without it (a new title usually means a different year). Anything that
  isn't a year between 1870 and 2100 is ignored.
- **Title pages link names here** only when `ratelog.features.people` is on (credits on title pages, another branch); this
  page doesn't depend on the flag.

- **Person links are on** (2026-11-24, merged together with person pages): `ratelog.features.people` is `true` in
  `application.yml`, so cast and crew names on title pages link to `/person/{id}`; `RATELOG_FEATURES_PEOPLE=false` hides the
  links again without a code change.

### Cast photos open the person page (2026-11-24)

The user: "should be able to click their picture to be taken to their [page]". The whole cast entry (photo, name and
character) opens the person page, on the desktop Cast card and the phone cast row: the name's link is stretched over the entry
(`::after`, `title-credits.css`), so there is still one link per person for screen readers and the keyboard, with the focus
ring around the whole entry. Hovering an entry underlines the name and rings the photo in the accent colour. Names in the
Directed by / Written by lines were links already.

### Show appearances as himself / herself (2026-11-24)

The user chose to keep talk-show, award-show and "Self" appearances hidden by default (as above) **with a switch to show them**.
Code: `person/Person.kt` (`PersonCredit.appearance`), `Filmography.kt` (`visible`, `appearanceCount`, `appearancesLabelKey`),
`detail/GetPersonHandler.kt`, `detail/web/PersonPageView.kt` (`PersonSwitch`), `templates/person.html`, `static/person-page.css`,
`person-page.js`.

- **The same rule decides what an appearance is**; appearances are now kept, marked, in the cached person (one TMDB answer
  still serves both states), instead of being dropped when TMDB's answer is read.
- **A switch under the type / sort tabs, off by default:** "Show 141 appearances as himself" with "Talk shows, award shows and
  interviews" under it, drawn like Discover's "Hide titles I've seen" switch, a size smaller. The count is the titles it would
  add to Acting for the chosen type (All, Movies or TV): a title where the person also has a role is already shown, so it
  isn't counted (its "Self" credit only joins the role text). No count in this view, no switch.
- **Wording by TMDB gender:** 1 "as herself", 2 "as himself", anything else (not set, non-binary) "as themselves"; "1
  appearance" in the singular.
- **On:** appearances count everywhere (the Acting tab's number, All / Movies / TV, the tiles, Newest / Most popular) and keep
  TMDB's role text ("Self", "Himself - Guest", "Self - Presenter"). `appearances=1` in the URL, kept by every tab and by "Show
  all"; applied in place like the tabs (a link with `role="switch"` swapped by `view-swap.js`, URL replaced; Space flips it).
- **The switch belongs to Acting:** it shows on the Acting tab, and on any tab when the person has no Acting tab at all (every
  acting credit is an appearance, e.g. a TV host); then turning it on opens Acting (`dept=acting`).
- **API:** `GET /api/v1/people/{id}?appearances=true`, with `appearances`, `appearanceCount` and each credit's `appearance`
  in the answer ([api.md](../api.md#people)).

## Search Year menu (2026-11-24)

The user: "can the year under search allow you to type but also still [have the] same style of like 2020s but can click exact
year if you want from [the] other filter". The number box became Discover's Year menu. Code: `search/SearchYearFilter.kt`,
`SearchYearMenu.kt`, `SearchHandler.kt` (decades), `browse/web/BrowseBarViews.yearMenu` (shared), `fragments/browse-bar.html`
(`browseMenu` fragment, "Type a year" box), `templates/search.html`, `static/search-type.js`, `search.js`, `view-swap.js`.

- **Same menu as Discover**, next to Any / Movies / TV: Any year, This year, Last year, the decades newest first (2020s ...
  1960s, then "1950s and earlier", which opens the older decades), and once a decade is chosen its exact years. Picking a
  decade filters to it and keeps the menu open on its years (Discover's 2026-11-24 behaviour); picking a year closes it. No
  "Upcoming" (search finds titles by name; Discover has it). The builder, the fragment, the CSS and browse-bar.js are
  Discover's own, not copies: `BrowseBarViews.yearMenu` takes a link builder, and the menu is the bar's `browseMenu` fragment.
  The trigger is drawn like the select beside it (a bordered 40px control); on phones the panel spans the filter row.
- **"Type a year"** at the top of the panel: a box and **Go**; Enter or Go applies a year from 1874 to ten years ahead (the
  same bounds as Discover's `year`). Anything else says "Type a year from 1874 to 2036." under the box and loads nothing. It is
  a plain GET form, so it works without JS too (then the server shows the menu open with the same message).
- **URL** like Discover: `year=2023` or `decade=2020s` (`decade=earlier`); a year wins over a decade. The filters apply in
  place and replace the URL (`#searchView` swapped by `view-swap.js`, which now also applies GET forms inside a
  `data-view-links` group; "One Back leaves the page"): the Year menu, Type a year and, since the same change, the Any /
  Movies / TV select (it loaded a page). Later pages (infinite scroll) and the type filter keep the year or decade; a new
  header search starts without it.
- **A decade is filtered on our side**: TMDB's search only filters exact years (`primary_release_year`,
  `first_air_date_year`), and 10 exact-year searches per page would cost 10-20 calls and break TMDB's relevance order. So a
  decade reads the normal search's pages from the start and keeps the titles of that decade (by release / first air date;
  titles without a date are left out), movies and TV page by page together for Any. **Trade-off (latency vs completeness):**
  one results page reads TMDB pages until it has 9 titles of the decade, at most **5 TMDB pages per type** (up to 10 calls for
  Any, ~1-2 s on a sparse decade; a dense one fills from page 1), and every match of the pages read is shown (none dropped).
  The page number in the URL is then the TMDB page to start from, so infinite scroll continues after the last page read;
  `search.js` takes the next page from the fresh page and loads again at once when a short page leaves the end in view. A
  decade only looks through TMDB's **first 20 pages per type (400 titles, by relevance)**: a search with few titles of that
  decade ends there instead of reading 500 pages. For anything further down, type the exact year (TMDB filters it, no limit).

