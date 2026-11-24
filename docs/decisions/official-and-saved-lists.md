# Official and saved lists

## Standing decisions

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
- **Order: most recently active first** (created, or a title added), the rule every other list section uses. Empty official lists
  are shown, unlike empty public lists, because an admin picked them. *(Except on Discover since 2026-11-23: an empty list has
  neither a movie nor a show, so it is in neither mode's row; see "Discover's Official lists follow Movies / TV".)*
- **Discover: the Official lists row comes right after "Popular now"**, not at the end. At 1440x900 it is fully above the fold;
  after the 13 genre rows (most of them loaded on scroll) hardly anyone would reach it. "Popular now" stays first as the "what's new"
  row. The same row shows on the Movies and TV landings (lists mix both) and never on the filtered grid. *(Superseded 2026-11-23:
  each mode only shows the lists with that type of title, see "Discover's Official lists follow Movies / TV".)* It needs no TMDB, so it
  still shows when every TMDB row failed. The model value is a Thymeleaf `LazyContextVariable`, loaded only when the landing renders
  the row, so the grid, "Load more" fragments and the many `/discover/row` fetches run no extra queries.
- **One include per template**: `lists.html` (the row), `list-detail.html` (badge + admin switch under the title; since the
  compact header of 2026-11-23 the badge sits on the meta line and the switch is a "⋯" menu item, both from `fragments/list-header.html`) and
  `fragments/discover-rows.html` (after the first row). The switch posts a CSRF form, asks first (`data-confirm`: since 2026-11-23
  it says whether every account will see the list or it stays hidden) and redirects back with a flash message.

## Saved lists: save an official list and it comes first (2026-11-24)

The user, 2026-11-24: *"For users, when I go to add a movie to a list it doesn't show any official lists, bc it shouldn't,
maybe something like being able to favorite an official list and that pins it to front of official lists and then when I go
to add that movie to list that one shows up but I can't check or uncheck bc its official?"* They agreed to the design below
and said *"I'll tell you if I like it"*, so every rule sits in one small, easy-to-change place (named per point). Code:
`org.ratelog.customlist.saved` (`SavedList.kt` with `SavedListPolicy` and `SavedFirst`, `set/`, `browse/`, `intitle/`, `web/`),
`api/lists/ApiSavedListController`, `templates/fragments/saved-lists.html`, `static/saved-lists.{css,js}`. Migration `V1142`.

- **Save, like a playlist.** A "Save" / "Saved" toggle (`circle-plus` / `circle-check`, accent when saved) on official list
  pages (beside the title, before the "⋯" menu; owners and admins see it too) and on official list cards (the Lists tab's
  Official row, `/lists/official`, Discover's Official row, and the saved cards under My lists), over the cover's top-right
  corner, a sibling of the card link (never inside it). Not on the home "Your lists" row: that shelf stays plain. "Save"
  rather than "favorite" or "pin" because the list is kept like a playlist and the word reads the same on a card and a page.
- **Stored per user** in `saved_lists(user_id, list_id, saved_at_epoch_ms)` (V1142), primary key user + list, both foreign
  keys cascade. The table allows any list; **only official, public lists can be saved for now** (`SavedListPolicy.canSave`),
  so ordinary public lists could be allowed later by changing that one function. Saving twice keeps the first time.
  Un-saving always works on a list you saved, even after it stopped being official or became private.
- **Hidden or un-marked lists keep their row but are shown nowhere** (`SavedListPolicy.visible`): if an official list is made
  private or un-marked, your save stays, but the list leaves your Official sections, My lists, Home, the title page and the
  sheet; it comes back (first again) once it is public and official again. Someone else's private list stays a 404.
- **Saved lists come first** in every Official lists section (Lists tab row, `/lists/official`, Discover's row and
  `GET /api/v1/lists/official`): newest save first, then the rest in the section's usual order (most recently active).
  `SavedFirst.order`, applied after Discover's Movies / TV filter, so that filter still decides which lists are in the row.
  Saving from a card moves it to the front in place (a short accent glow; a row scrolled sideways scrolls back to the start);
  un-saving puts it back at its place in the usual order (`data-base-order`).
- **"Your lists" includes them.** The Lists tab's My lists shows your own lists, then your saved official lists (newest save
  first) with the Official badge, your "3/10 seen" and the toggle. The home "Your lists" row adds them after your own (badge
  and progress, no toggle), still at most 20, and adds the row when you have no lists of your own
  (`SavedListsHomeInterceptor`, so the home slice is untouched). An admin's own official list is never listed twice.
- **Title page: "Official lists" chips** under the scores (both layouts): every public official list that has the title,
  not just saved ones, as "#12 in IMDb Top 250" on a numbered list and just the name on a plain one, each a link to the list.
  Order (`OfficialTitleListsOrder`): saved first (accent chips with a check, newest save first), then numbered lists by the
  title's rank (a #3 says more than a #180), then plain lists by name. Three chips, the rest behind "+N more" (a `<details>`,
  so it works without JS; "Show less" closes it). On phones the chips are one line that scrolls sideways, so they don't stack
  four lines above the action buttons. One query (`OfficialTitleListsDAO`: the title by type + local id across public
  official lists, the rank computed like `ListMembershipDAO`, `NULL` when the list isn't numbered, plus the viewer's save
  time). Hidden official lists are left out, for their owner too (it is in their own lists in the sheet).
- **The add-to-list sheet stays your own lists.** Admins still see the official lists they own as normal checkable rows. Under
  the rows, one quiet read-only line for saved official lists with the title that you don't own: "Also in your saved lists:
  IMDb Top 250 (#12), AFI 100", each a link, no checkboxes. On the title page and in a poster's sheet (`GET /tile/lists`), from
  the same interceptor and query (`OfficialTitleListsInterceptor`).
- **In place:** the toggle posts to `POST /list/{id}/saved` (`saved=true|false`, the state asked for, never "toggle"); with
  `X-Requested-With: fetch` it answers JSON and `saved-lists.js` flips every toggle of that list on the page, reorders the
  Official section, refreshes the Lists tab's My lists from a fresh copy of the page, and shows a toast ("Saved "IMDb Top 250".
  It's first in Official lists now."). A 429 shows the server's message. Without JS it is a plain POST that comes back to the
  page (the Referer) with a flash message. The script loads from the nav (like `inplace.js`), so a row swapped in by Discover's
  Movies / TV switch works too.
- **API:** `PUT` / `DELETE /api/v1/lists/{id}/saved`, `GET /api/v1/lists/saved`, `saved` on official lists and `officialLists`
  on title details ([api.md](../api.md#saved-lists)). The JSON home (`GET /api/v1/home`) is unchanged: its `your_lists` row is
  still only your own lists.
