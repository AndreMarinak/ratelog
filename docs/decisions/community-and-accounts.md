# Community and accounts

## Standing decisions

- **Manager role** (user request, 2026-11-23: "there's two types of accounts, admins and users, I want a third called managers, whose only diff between a normal user is that they can add accounts"). `Role.MANAGER` (stored as text in `users.role`, no migration needed) has the USER and MANAGER authorities, never ADMIN. Managers get an "Add account" entry (header icon and the phone menu sheet) leading to `/accounts/new`, a plain form (username, email, password) that always creates a USER account: `CreateUserHandler` refuses any other role from a manager, whatever the request says. Nothing else changes for them: no user list, no editing or deleting other accounts, no configuration, no official lists (those checks use `isAdminLike`). Admins and superadmins give or take the role in the existing admin create and edit forms (labelled "Manager"); an ADMIN may switch accounts only between USER and MANAGER, and may edit or delete managers like users. Role changes apply to open sessions at once (session revalidation). `GET /api/v1/me` reports `MANAGER`.

## Community: People and Follow buttons (2026-11-23)

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
