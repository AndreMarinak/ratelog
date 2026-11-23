# Bugs and issues

Problems found while building and running the fork: what happened, the cause, and the fix (or why it's still open). Newest at the top of each section. Related: [design-decisions.md](design-decisions.md).

## Open

- **Owner-only modals on the profile page use `th:if` + `th:replace` on one element** (upstream, `profile.html`: Letterboxd import and create-list modals). `th:replace` runs before `th:if`, so they render on other people's profiles too. Harmless (hidden, and the server checks ownership), but the same pattern made the list page 500 (see Fixed). Fix: wrap in `<th:block th:if>`.
- **The API can't read progress on other people's public lists:** `GET /api/v1/lists/{id}` only serves your own lists (others are 404 by design), while the web page shows your progress on any public list.
- **List items can only be appended.** The app and API have no reorder / insert-at-position, so restoring a removed title to its rank (Coco at #3 in the Disney list, 2026-11-22) meant deleting and recreating the list. Idea: `position` on API items + drag-to-reorder on list pages.
- **Title matcher is strict with same-name knockoffs.** Aladdin (1992), Mulan (1998), Hercules (1997), Pocahontas (1995), The Hunchback of Notre Dame (1996) and Strange World (2022) came back `ambiguous` because TMDB has budget animated films with the same title and year. TMDB also dates some films differently (Hamilton 2025, Snow White 1938). Workaround: send `tmdbId`. Idea: tie-break by TMDB vote count when one candidate dwarfs the others.
- **TMDB release-date changes break year matching.** Hamilton (2020) is dated 2025 on TMDB after its theatrical re-release, outside the ±1 year slack. Workaround: `tmdbId`.
- **Title detail page 500s when TMDB can't load that title** (upstream): `movie-detail.html` reads `movie.posterPath` when the handler returned no movie. Rare with a valid key; should render a "not found" state.
- **TV auto-Watched may be premature:** rating any season of a show marked Watching sets the whole show to Watched, even if later seasons are unwatched. Decide whether TV should stay Watching until the last season is rated.
- **Old Letterboxd imports look "detailed":** before quick rating existed, imports stored the same score in all five categories. They can't be told apart from a real detailed rating with equal scores, so they were not converted.
- **Float noise in category scores from the API:** categories sent through the API that aren't multiples of 0.25 (e.g. 8.3) display as `8.300000190734863` on the web page because category columns have lower precision. The API itself rounds to 2 decimals; the sliders only produce 0.25 steps.

## Fixed

- **Stale classes after merging branches (local builds only).** After merging `feat/listprogress`, `HomeDashboardViewTest` failed with `NoSuchMethodError: toCard(ListSummary, boolean)`: the branch added a defaulted parameter, and Maven's incremental Kotlin compile didn't recompile `HomeDashboardView.kt`, which calls it. `mvn clean package` fixes it. Run `clean` after merges that change signatures. Railway's Docker build always starts clean, so production is not affected.

- **List page 500 for a missing or private list** (upstream, found 2026-11-23 while testing list progress): `list-detail.html` had `th:if="${isOwner}"` and `th:replace` (edit/delete modals) on the same element. Thymeleaf runs `th:replace` first, so the modals rendered with a null list ("Property 'id' cannot be found on null") instead of the "not authorized" message, and were also rendered (hidden) for non-owners. Fixed with a `<th:block th:if="${isOwner}">` wrapper.
- **No way back from a list page** (2026-11-23): you had to tap the Lists tab again. Added a "← Lists" / "← {owner}'s profile" link above the title.
- **Home "Coming soon" More link opened the unfiltered grid** (found 2026-11-23 while renaming Browse). It used `decade=upcoming`, which the parser rejects (a decade looks like `2010s`) and the page silently ignores. Now `year=upcoming`; "In theaters" also moved from the alias `sort=release_desc` to the canonical `sort=newest`. A test parses every home row's More link and fails on any ignored parameter.
- **api.md's Browse section rendered as one code block:** the Home section's `curl` example was never closed.
- **"Find a title" wrapped onto its own line in the bar at desktop widths** once a menu showed its value ("Genre · Horror"), leaving a mostly empty second row. Flex lines break on an item's basis (260px), not its 180px minimum, so the field wrapped although it fit. It now starts at 180px and grows to 260px (`browse-bar.css`); found in the Discover screenshots at 1440px.

- **White flashes when scrolling on phones.** The dark background was a gradient on `body` only (no background color), so the canvas behind it showed white during overscroll / browser-toolbar resizes. `html` now has a solid `var(--bg)` and `color-scheme: dark` (generic.css).

- **Mobile bottom nav had 6 items** after Watchlist was added. Now 5: Home (leftmost), Movies, TV, Watchlist, Lists; Browse, Tags, Community, Profile and Configuration moved into the menu sheet.
- **Home page posters were blurry** (upstream `w185` stretched). The new home rows load `w342` (+ `w500` for 2x screens); search results and list covers too.

- **Blurry posters** (2026-11-22): tiles loaded TMDB `w185` images stretched to ~230px (worse on 2x screens). Fixed with size-appropriate images + `srcset` (see design decisions).
- **Rating modal taller than the screen** (2026-11-22): modals had no max height, so the rate modal (5 sliders + mode toggle + review) overflowed with no way to scroll. Fixed: viewport cap + internal scroll; bottom sheet on phones.
- **Title page wasted space** (2026-11-22): only the overview/genres sat beside the poster; status, providers, tags, scores and rating were stacked under it. Fixed with the two-column layout.
- **No confirmation before removing a list item** (2026-11-22): one click removed Coco from the Disney list. Fixed with `data-confirm` on remove/untag/revoke forms; Coco restored.
- **No obvious way to add to the watchlist** (2026-11-22): only the status buttons on title pages did it, unlabeled. Fixed: labeled status control ("Your watchlist") and bookmark buttons on poster tiles (`POST /watchlist/toggle`).
- **Home page too short / trending thin** (upstream): only TMDB's top 5 movies + top 5 shows, so e.g. the new Hunger Games film was missing. Replaced by the home dashboard (in progress).
- **Secrets could reach logs** (security review): logging `RestClientException` messages would include TMDB URLs with `api_key`. Now only the exception class and status code are logged, in both the HTML and API error handlers.
- **Redirects used `http://` behind Railway's TLS proxy.** Fixed with `server.forward-headers-strategy: framework`.
- **Home/search returned `{"message":"Internal server error"}`** when TMDB rejected the key (upstream: RestClient exceptions weren't handled). Now a readable "TMDB unavailable" page (`TmdbErrorAdvice`).
- **Locked out of admin + home page 500 on first Railway deploy** (2026-11-22). Cause: the Railway variables were pasted with the setup guide's placeholders still in them, so `ADMIN_PASSWORD` became the placeholder text and `TMDB_API_KEY` stored `<your TMDB "API Key">`; upstream only reads `TMDB_API_KEY` on first start, so fixing the variable later did nothing. Fixes: admin password reset via SQL (procedure in [deploy-railway.md](deploy-railway.md#reset-the-admin-password)), env-var TMDB key now always wins and placeholders are refused, setup guide uses obvious `REPLACE_ME_...` values.
- **Railway 502 / crash loop on first deploy:** the app started without `POSTGRES_*` variables and kept trying `localhost:5432`. Fixed by adding the Postgres service and variable references; documented in [deploy-railway.md](deploy-railway.md).
- **`/**` inside a KDoc comment broke compilation:** Kotlin treats `/*` inside a comment as a nested comment opener. Don't write `/api/v1/**` in comments.
