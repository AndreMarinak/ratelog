# Posters and tiles

## Standing decisions

- **Poster quick actions:** a stack in the poster's top-right corner: the watchlist bookmark (or a Watching / Dropped badge where the bookmark can't toggle), then the Seen eye. The Watched badge became the eye. The bookmark is rendered hidden rather than left out when it can't toggle, so un-seeing can bring it back in place. With a mouse, controls that are off appear on hover / focus; on touch screens and at 900px and below they're always visible; anything on is always visible. Status icons changed so the eye means Seen everywhere: Watched = `eye`, Watching = `circle-play`. *(Since 2026-11-23 there is a second group bottom right: Add to list and ★, see "Rate and add to list from any poster" below.)*
- **Poster sizes match display size:** tiles `w342` (+ `w500` for 2x screens), title poster `w500` (+ `w780`), ranked-list thumbnails `w185` (+ `w342`), provider logos `w92`. Upstream used `w185` tiles stretched to ~230px, which looked blurry.
- **Home posters at near-full brightness** with a bottom gradient behind titles (upstream dimmed them to 60%).
- **Ranking posters keep their 2:3 shape** on phones (object-fit: cover, fixed width) instead of stretching to the card height.
- **One-click bookmark on posters** only toggles Want to watch; titles already Watching/Watched/Dropped show no bookmark so a click can't silently downgrade them.
- **List posters carry three badges** in the home tiles' style (`home.css` `.home-status` / `.home-score`): rank bottom-left, your status top-right (Want to watch included, since list pages have no bookmark button; Dropped is muted), your score bottom-right. Status badges line up with the type tag.

## Rate and add to list from any poster (2026-11-23)

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
  with a mouse, always visible on touch and at 900px and below. *(Superseded on 2026-11-23 by "Poster quick actions: one button on
  touch, hover with a mouse": the eye moved to the bottom row, and on touch one "⋯" button replaces the four.)*
- **Rated = "★ 8.5", always visible** (one decimal, like the score badges it replaces; the old `.home-score`,
  `.browse-user-score` and list score badges are hidden with `:has()` wherever the ★ is shown, and stay where it isn't, e.g. a
  rated upcoming title or list tick-off mode). Unrated = an outline ★ button. Colours: the light clay score text with a warm
  star, so it reads as "yours" next to the eye and the clay bookmark (the eye is a quiet sage since 2026-11-23, see below).
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
- *(Superseded 2026-11-23 by "TV: rate a show as a whole, or by season" below: ★ on a show now opens the picker for a
  whole-show score, and only a show rated by season links to its seasons.)*
  **Shows: ★ goes to the show's seasons** (`/tv/{id}#seasons`, the same as the title page's "Rate seasons") and the chip shows
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

## "Rate it?" after Seen on tiles (2026-11-23)

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
  *(Since 2026-11-23 a show can be rated as a whole from the same picker, so the prompt covers unrated shows too.)*
  Not after the watchlist page's Seen button either: that tile leaves the tab at once.

## Watchlist buttons line up on every tile (2026-11-23)

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

## Poster quick actions: one button on touch, hover with a mouse (2026-11-23)

Requested by the user on 2026-11-23, with a phone screenshot of a poster covered by a bookmark, an eye, an add-to-list icon and
a ★: "also the mobile, these buttons cover a lot of the tiles. The watchlist, add to list, and star rating button could be
combined into one button so I can click it and then choose, make it user friendly. Maybe on the laptop app make it so I only
see those buttons when hover over movie, except for the eye, that should stay if seen movie, and maybe the rating if they seen
the movie already" / "also the green eye is a bit bright/stands out little too much?"

- **At rest a poster only shows status, never a button you have to read:** the eye when you've seen it and "★ 8.5" when you've
  rated it, both small and together in the bottom-right corner (where your score badge always sat). Unseen, unrated titles show
  nothing (plus "⋯" on touch). Watchlist, Watching / Dropped and "In your lists" are no longer shown on the poster at rest: they
  appear on hover (mouse) or in the chooser (touch). The marks have no drop shadow and sit on the same dark chip, so they read as
  labels; tapping one on touch opens the chooser rather than toggling (an accidental un-see was one tap away before).
- **The eye moved from top right to bottom right**, next to the score: seen + score are one "your status" cluster, and the
  top-right corner is free for the one button (touch) or the bookmark (hover). Top right: bookmark (or badge); bottom right: Add
  to list, eye, ★. Upcoming titles keep their list button under the bookmark (no eye or ★ there), clear of the Upcoming badge.
- **Touch screens (`hover: none`): one "⋯" button per poster**, top right, a 30px dark chip with a 44px tap area. It opens a
  chooser for that title with four rows: **Watchlist** (Add to watchlist / On your watchlist ✓), **Seen** (Mark as seen / Seen ✓,
  "You rated it, so it stays seen" when locked; not on upcoming titles), **Rate** (movies: Rate / Your rating "★ 8.5" / detailed,
  which opens the existing 1-10 picker; shows: "Rate seasons →" to the seasons) and **Add to list** (the existing tile list
  sheet; "In your lists" once listed). On phones (600px and below, like the rating picker and the app's modals) it is a bottom
  sheet over a dimmed page with 52px rows, easy to reach with a thumb on a 360px screen; on wider touch screens (tablets) a
  popover by the button. A bottom sheet over an anchored popover on phones: a popover next to a 96-120px poster would cover the
  neighbouring posters anyway, and its rows would be too small. Close with ✕, Escape, a tap on the dimmed page or anywhere else;
  focus goes back to "⋯" (also after the rating picker or the list sheet it opened). The rows hand over to the poster's own
  (hidden) controls, so every action saves exactly as before: in place, a toast, every copy of the title on the page updates,
  "Rate it?" after Seen, list progress counts. Picking a toggle closes the chooser (the toast confirms); reopening shows the new
  state. In list tick-off mode there is no "⋯": the check stays, and tapping the poster ticks it as before.
- **Mouse (`hover: hover`): the four separate quick buttons stay, shown only while the tile is hovered or has keyboard focus**
  (`:hover` / `:focus-within`). One click per action is quicker with a mouse than open-menu-then-choose, and the buttons cost
  nothing at rest now that they're hidden. The marks turn into buttons on hover (same place, a shadow and a darker chip).
  The old "always show everything at 900px and below" rule is gone: it depends on the pointer, not the window width.
- **The Seen eye is a quiet sage, not a filled bright green:** a `hsl(140 20% 74%)` eye on the dark chip (`rgba(18,16,14,0.8)`)
  instead of a white eye on a `hsl(138 42% 44%)` disc. Contrast of the eye against the chip is 5.9:1 even over a white poster
  (the chip lets 20% through) and 11:1 over a dark one, well above the 3:1 WCAG asks of icons; the tick-off check is white on
  `hsl(140 18% 33%)` (6.6:1). The same sage replaces the bright
  green (`hsl(138 40% 60%)` / `hsl(138 42% 44%)`) everywhere "seen" was green: the `--seen` / `--seen-strong` tokens, the
  "You're here" mark in title-page rows, the list tick-off check, ring and bar, the title page's Seen button and "Rate it?"
  nudge, the watchlist page's Seen button and a suggestion's "Added" state, so the colour means the same thing everywhere.
- **Watchlist page:** its posters only carry Add to list and ★ (the text buttons under each card are the status moves), so the
  same rule gives "★ 8.5" at rest, both buttons on hover, and on touch "⋯" with only Rate and Add to list in the chooser.
- **Ratings tab cards** keep their row of actions at the bottom right of the card: on touch "⋯" plus the eye and score; with a
  mouse the rest on hover.
- **Without JS** "⋯" does nothing (it is a plain button); the app's poster actions all need JS on touch now. Accepted: the rest of
  the app needs JS too, and showing the four buttons until the script runs would flash them on every page load.
- This replaces the visibility rule of "Rate and add to list from any poster" above ("off controls ... always visible on touch
  and at 900px and below", the bright green eye).
- **Smaller chips on narrow posters, phones only** (user, 2026-11-23: "on the lists it looks perfect but homepage and discover
  not so much", then "the 3 dots and even the eye and rating look off"): Home / Discover rows (~98px posters) and the Discover /
  search grid (~105px) get a 24px "⋯" and 22px marks tucked 4px into the corners (tap areas stay 44px), and a rated title shows
  only "★ 7.0": rated already means seen, so the eye said the same thing twice and, with the score, covered three quarters of
  the poster's bottom. Lists, the watchlist and the rankings keep the larger chips; mouse devices are unchanged.
