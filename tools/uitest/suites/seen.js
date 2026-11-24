// Seen / Watchlist toggles on title pages and poster tiles, the "Rate it?" nudge, the follow bell rule, /watchlist quick
// actions, list tick-off mode and the Upcoming badge. Ported from the scratchpad's uitest/run_seen.js (+ seed_seen_lists.py).
// Every in-place action is checked with the window marker. The "upcoming" title is not hard-coded: setup takes the first
// movie in TMDB's coming-soon row that is at least 3 days away, so the suite keeps working after any one release date.
const { setTitle, setTvSeasons, createList, findUpcomingMovie } = require('../lib/api');

const M = { toyStory: 862, insideOut: 150540, wallE: 10681, lionKing: 8587, nemo: 12, up: 14160, dune2: 693134, coco: 354912, frozen: 109445 };
const TV = { breakingBad: 1396, severance: 95396, bear: 136315, tlou: 100088 };

module.exports = {
  name: 'seen',
  description: 'Seen/watchlist toggles, Rate it?, bell rule, /watchlist actions, list tick-off, Upcoming badge',

  async setup(env) {
    const upcoming = await findUpcomingMovie(env.api);
    const listA = await createList(env.api, `${env.prefix} seen A`, [['movie', M.toyStory], ['movie', M.insideOut], ['movie', M.wallE]]);
    const listB = await createList(env.api, `${env.prefix} seen B`, [['movie', upcoming.tmdbId], ['movie', M.toyStory]]);
    return { upcoming, listA, listB, titles: {} };
  },

  async reset(env, data) {
    const api = env.api;
    const want = [['movie', M.up, 'up'], ['movie', M.dune2, 'dune2'], ['movie', data.upcoming.tmdbId, 'upcoming'], ['tv', TV.bear, 'bear'], ['tv', TV.tlou, 'tlou']];
    for (const [type, id, key] of want) {
      if (type === 'movie') await setTitle(api, 'movie', id, { score: null });
      // Exactly "To watch": no status (the watchlist is independent of Seen, so a Watched one would stay Watched).
      await api.must('DELETE', `/statuses/${type}/${id}`, undefined, [204, 404]);
      const r = await api.must('PUT', `/statuses/${type}/${id}`, { status: 'want_to_watch' });
      data.titles[key] = r.title;
    }
    await setTvSeasons(api, TV.bear, {});
    await setTvSeasons(api, TV.tlou, {});
    await setTvSeasons(api, TV.severance, {});
    await setTvSeasons(api, TV.breakingBad, {});
    await setTitle(api, 'tv', TV.breakingBad, { status: 'watching' });
    await setTitle(api, 'tv', TV.severance, { status: 'dropped' });
    await setTitle(api, 'movie', M.coco, { status: 'watched' });
    await setTitle(api, 'movie', M.frozen, { score: null, status: 'dropped' });
    for (const id of [M.lionKing, M.insideOut, M.wallE, M.nemo]) await setTitle(api, 'movie', id, { score: null, status: null });
    await setTitle(api, 'movie', M.toyStory, { score: 9.0, status: 'watched' });
  },

  async run(t) {
    const { page, assert } = t;
    const { upcoming, listA, listB, titles } = t.data;
    const upKey = `movie:${upcoming.tmdbId}`;

    await t.step('login', () => t.login());

    // ---------- Home ----------
    await t.step('home rows + Upcoming badge on the upcoming movie, none on classics', async () => {
      await t.go('/');
      const info = await page.evaluate((key, name) => {
        const tiles = [...document.querySelectorAll('.home-tile-wrap')].map((w) => ({
          key: w.querySelector('[data-key]')?.dataset.key || w.dataset.key || null,
          title: w.querySelector('.home-tile-title')?.innerText,
          upcoming: w.querySelector('.tile-upcoming')?.innerText.replace(/\s+/g, ' ').trim() || null,
          seen: !!w.querySelector('.tile-seen'),
          row: w.closest('.home-row')?.querySelector('h2,h3')?.innerText.trim() || '',
        }));
        return { rows: document.querySelectorAll('.home-row').length, tiles, key, name };
      }, upKey, upcoming.title);
      await t.shot('home');
      await t.noOverflow();
      assert(info.rows >= 4, `only ${info.rows} rows`);
      const up = info.tiles.filter((x) => x.key === upKey || x.title === upcoming.title);
      assert(up.length > 0, `${upcoming.title} not on home`);
      assert(up.every((x) => /Upcoming/.test(x.upcoming || '')), `badge: ${JSON.stringify(up[0])}`);
      assert(up.every((x) => !x.seen), `${upcoming.title} has a Seen toggle`);
      const classicBadges = info.tiles.filter((x) => /classics/i.test(x.row) && x.upcoming);
      assert(classicBadges.length === 0, `released classics with the badge: ${classicBadges.map((x) => x.title)}`);
      return `${info.rows} rows; ${upcoming.title} "${up[0].upcoming}"; ${info.tiles.filter((x) => x.upcoming).length} upcoming tiles`;
    });

    await t.step('home tile: Seen and Watchlist toggle in place', async () => {
      await t.go('/');
      const key = await page.evaluate(() => {
        const s = [...document.querySelectorAll('#row-trending-movies [data-tile-actions]')]
          .find((x) => x.querySelector('.tile-seen:not(.is-on)') && x.querySelector('.tile-watchlist:not(.is-on):not([hidden])'));
        return s && s.dataset.key;
      });
      assert(key, 'no unseen, unbookmarked trending tile');
      const token = await t.mark();
      const sel = `#row-trending-movies [data-tile-actions][data-key="${key}"]`;
      await t.tileAct(`${sel}`, 'seen');
      // A seen title keeps its bookmark (2026-11-24: it can go on the watchlist to rewatch).
      await t.waitFor((s) => document.querySelector(s + ' .tile-seen.is-on') && !document.querySelector(s + ' .tile-watchlist[hidden]'), sel);
      await t.stillHere(token);
      const nudge = await page.evaluate(() => !!document.querySelector('.title-nudge:not([hidden])'));
      assert(!nudge, 'Rate it? showed on a tile');
      const toast = await page.evaluate(() => document.querySelector('.tile-toast.visible')?.innerText);
      await t.shot('home-tile-seen');
      await t.tileAct(`${sel}`, 'seen');
      await t.waitFor((s) => document.querySelector(s + ' .tile-seen:not(.is-on)') && !document.querySelector(s + ' .tile-watchlist[hidden]'), sel);
      await t.tileAct(`${sel}`, 'watchlist');
      await t.waitFor((s) => document.querySelector(s + ' .tile-watchlist.is-on'), sel);
      const synced = await page.evaluate((k) => [...document.querySelectorAll(`[data-tile-actions][data-key="${k}"] .tile-watchlist`)].every((f) => f.classList.contains('is-on')), key);
      await t.tileAct(`${sel}`, 'watchlist');
      await t.waitFor((s) => document.querySelector(s + ' .tile-watchlist:not(.is-on)'), sel);
      await t.stillHere(token);
      assert(synced, 'the same title elsewhere on the page did not flip');
      return `${key}: seen on/off, bookmark on/off, no reload; toast "${toast}"`;
    });

    // ---------- Discover ----------
    await t.step('Discover row and grid: Seen in place', async () => {
      await t.go('/discover?hideSeen=0');
      const token = await t.mark();
      const sel = '.discover-row:not(.is-pending) [data-tile-actions]:has(.tile-seen:not(.is-on))';
      await t.waitFor((s) => document.querySelector(s), sel, 30000);
      const key = await page.$eval(sel, (s) => s.dataset.key);
      const one = `.discover-row [data-tile-actions][data-key="${key}"]`;
      await t.tileAct(`${one}`, 'seen');
      await t.waitFor((s) => document.querySelector(s + ' .tile-seen.is-on'), one);
      await t.tileAct(`${one}`, 'seen');
      await t.waitFor((s) => document.querySelector(s + ' .tile-seen:not(.is-on)'), one);
      await t.stillHere(token);
      await t.noOverflow();
      await t.shot('discover');
      await t.go('/discover?type=movie&genre=27&hideSeen=0');
      const token2 = await t.mark();
      const gkey = await page.$eval('#browseGrid [data-tile-actions]:has(.tile-seen:not(.is-on))', (s) => s.dataset.key);
      const g = `#browseGrid [data-tile-actions][data-key="${gkey}"]`;
      await t.tileAct(`${g}`, 'seen');
      await t.waitFor((s) => document.querySelector(s + ' .tile-seen.is-on'), g);
      await t.tileAct(`${g}`, 'seen');
      await t.waitFor((s) => document.querySelector(s + ' .tile-seen:not(.is-on)'), g);
      await t.stillHere(token2);
      await t.shot('discover-grid');
      await t.noOverflow();
      return `row ${key}, grid ${gkey}`;
    });

    // ---------- Search ----------
    await t.step('search: quick actions in place, Upcoming on the upcoming movie', async () => {
      await t.go('/?q=' + encodeURIComponent(upcoming.title));
      const info = await page.evaluate(() => [...document.querySelectorAll('.search-tile-wrap')].map((w) => ({
        key: w.dataset.key, title: w.querySelector('.home-tile-title')?.innerText,
        upcoming: !!w.querySelector('.tile-upcoming'), seenForm: !!w.querySelector('.tile-seen'), stack: !!w.querySelector('[data-tile-actions]'),
      })));
      assert(info.length > 0 && info.every((x) => x.stack), 'results without quick actions');
      // The search wrap's key is "movie-<id>" (the tile actions inside use "movie:<id>").
      const up = info.find((x) => x.key === `movie-${upcoming.tmdbId}` || x.title === upcoming.title);
      assert(up && up.upcoming && !up.seenForm, `${upcoming.title} in search: ${JSON.stringify(up)}`);
      let target = info.find((x) => x.seenForm);
      if (!target) {
        await t.go('/?q=avengers');
        target = (await page.evaluate(() => [...document.querySelectorAll('.search-tile-wrap')].filter((w) => w.querySelector('.tile-seen'))
          .map((w) => ({ key: w.dataset.key, title: w.querySelector('.home-tile-title')?.innerText }))))[0];
      }
      assert(target, 'no released result to toggle');
      const token = await t.mark();
      const s = `.search-tile-wrap[data-key="${target.key}"] [data-tile-actions]`;
      await t.tileAct(`${s}`, 'seen');
      await t.waitFor((x) => document.querySelector(x + ' .tile-seen.is-on'), s);
      await t.shot('search');
      await t.tileAct(`${s}`, 'seen');
      await t.waitFor((x) => document.querySelector(x + ' .tile-seen:not(.is-on)'), s);
      await t.stillHere(token);
      await t.noOverflow();
      return `${info.length} results; toggled ${target.title}`;
    });

    // ---------- Title pages ----------
    await t.step('movie page (The Lion King): four actions above the fold, no bell', async () => {
      await t.go(`/movie/${M.lionKing}`);
      const a = await page.evaluate(() => {
        const acts = [...document.querySelectorAll('.title-actions > *')].filter((x) => x.offsetParent);
        return { labels: acts.map((x) => x.innerText.replace(/\s+/g, ' ').trim()), bottom: Math.max(...acts.map((x) => x.getBoundingClientRect().bottom)),
          vh: innerHeight, bell: !!document.querySelector('.title-follow'), upcoming: !!document.querySelector('.title-upcoming') };
      });
      await t.shot('lionking');
      assert(a.labels.length === 4, `actions ${a.labels.join(' | ')}`);
      assert(/seen/i.test(a.labels[0]) && /watchlist/i.test(a.labels[1]) && /rat/i.test(a.labels[2]) && /list/i.test(a.labels[3]), a.labels.join(' | '));
      assert(a.bottom <= a.vh, `actions end at ${Math.round(a.bottom)} of ${a.vh}`);
      assert(!a.bell, 'bell on a released movie');
      assert(!a.upcoming, 'Upcoming on a released movie');
      await t.noOverflow();
      return `${a.labels.join(' | ')}; bottom ${Math.round(a.bottom)}/${a.vh}`;
    });

    await t.step('movie page: Seen in place, "Rate it?" shows, Skip keeps it seen and unrated', async () => {
      await t.api.must('DELETE', `/statuses/movie/${M.lionKing}`, undefined, [204, 404]);
      await t.go(`/movie/${M.lionKing}`);
      const token = await t.mark();
      await t.pressSel('[data-title-seen]');
      await t.waitFor(() => document.querySelector('[data-title-seen].is-on') && document.querySelector('[data-rate-nudge]:not([hidden])'));
      await t.stillHere(token);
      await t.shot('lionking-nudge');
      await t.pressSel('[data-nudge-skip]');
      await t.waitFor(() => document.querySelector('[data-rate-nudge][hidden]'));
      await t.stillHere(token);
      await t.go(`/movie/${M.lionKing}`);
      const after = await page.evaluate(() => ({ seen: !!document.querySelector('[data-title-seen].is-on'), rated: !!document.querySelector('.title-rating'),
        nudge: !!document.querySelector('[data-rate-nudge]:not([hidden])') }));
      assert(after.seen && !after.rated && !after.nudge, JSON.stringify(after));
      return 'seen, no rating after Skip and reload';
    });

    await t.step('movie page: Watchlist toggles in place on a seen title, no question, and it stays seen', async () => {
      await t.go(`/movie/${M.lionKing}`);
      const token = await t.mark();
      const asked = [];
      const onDialog = (d) => asked.push(d.message());
      page.on('dialog', onDialog);
      await t.pressSel('[data-title-watchlist]'); // seen + unrated: on the watchlist, still seen (2026-11-24)
      await t.waitFor(() => document.querySelector('[data-title-watchlist].is-on') && document.querySelector('[data-title-seen].is-on'));
      await t.pressSel('[data-title-watchlist]');
      await t.waitFor(() => document.querySelector('[data-title-watchlist]:not(.is-on)') && document.querySelector('[data-title-seen].is-on'));
      page.off('dialog', onDialog);
      await t.stillHere(token);
      assert(asked.length === 0, `asked: ${asked.join(' | ')}`);
      const st = await t.api.must('GET', '/statuses?status=watched&type=movie');
      assert(st.items.some((i) => i.tmdbId === M.lionKing && !i.onWatchlist), 'not Watched any more');
      return 'on (Seen stays on), off (Seen stays on), no confirm';
    });

    await t.step('movie page: rating from "Rate it?" saves a quick rating', async () => {
      await setTitle(t.api, 'movie', M.lionKing, { score: null, status: null });
      await t.go(`/movie/${M.lionKing}`);
      await t.pressSel('[data-title-seen]');
      await t.waitFor(() => document.querySelector('[data-rate-nudge]:not([hidden])'));
      const score = await page.$('[data-rate-nudge] .title-nudge-score[value="8"]');
      assert(score, 'no 8 in the nudge');
      // Saves in place since 2026-11-24 (no reload): wait for the rating card to appear with the score.
      await page.evaluate(() => { window.__nudgeMarker = 1; });
      await t.press(score);
      await t.waitFor(() => /8\.00/.test(document.querySelector('.title-rating')?.innerText || ''));
      assert(await page.evaluate(() => window.__nudgeMarker === 1), 'rating from "Rate it?" reloaded the page');
      const r = await page.evaluate(() => ({
        card: document.querySelector('.title-rating')?.innerText.replace(/\s+/g, ' ').trim(),
        rate: document.querySelector('.title-action.is-rated')?.innerText.replace(/\s+/g, ' ').trim(),
        seen: document.querySelector('[data-title-seen]')?.className,
        nudge: !!document.querySelector('[data-rate-nudge]'),
      }));
      await t.shot('lionking-rated');
      assert(/8\.00/.test(r.card || '') && /quick/i.test(r.card || ''), `rating card: ${r.card}`);
      assert(/8\.00/.test(r.rate || ''), `rate button: ${r.rate}`);
      assert(/is-on/.test(r.seen) && /is-locked/.test(r.seen), `seen: ${r.seen}`);
      assert(!r.nudge, 'nudge rendered for a rated movie');
      const token = await t.mark();
      // Since "Rate it?" saves in place, the earlier "Marked as seen." toast can still be showing: let it clear first.
      await t.waitFor(() => !document.querySelector('.tile-toast.visible'), undefined, 10000).catch(() => {});
      await t.pressSel('[data-title-seen]');
      await t.waitFor(() => document.querySelector('.tile-toast.visible'));
      const msg = await page.evaluate(() => document.querySelector('.tile-toast.visible').innerText);
      assert(/rated/i.test(msg), `locked message: ${msg}`);
      await t.stillHere(token);
      await setTitle(t.api, 'movie', M.lionKing, { score: null, status: null });
      return `"${r.card.slice(0, 40)}"; locked tap says "${msg}"`;
    });

    await t.step('movie page: Rate and Add to list open their sheets', async () => {
      await t.go(`/movie/${M.lionKing}`);
      await t.pressSel('.title-actions [data-open-modal="rateMovieModal"]');
      await t.waitFor(() => document.querySelector('#rateMovieModal.visible'));
      await t.pressSel('#rateMovieModal [data-close-modal]');
      await t.waitFor(() => !document.querySelector('#rateMovieModal.visible'));
      await t.pressSel('.title-actions [data-open-modal="addToListModal"]');
      await t.waitFor(() => document.querySelector('#addToListModal.visible'));
      await t.shot('lionking-addtolist');
      await t.pressSel('#addToListModal [data-close-modal]');
      await t.waitFor(() => !document.querySelector('#addToListModal.visible'));
      return 'both open and close';
    });

    await t.step('upcoming movie page: Upcoming, no Seen, bell follows and unfollows in place', async () => {
      await t.go(`/movie/${upcoming.tmdbId}`);
      const d = await page.evaluate(() => ({
        upcoming: document.querySelector('.title-upcoming')?.innerText.replace(/\s+/g, ' ').trim(),
        seen: !!document.querySelector('[data-title-seen]'),
        tile: document.querySelector('.title-toggle-upcoming')?.innerText.trim(),
        bell: document.querySelector('.title-follow-btn')?.innerText.replace(/\s+/g, ' ').trim(),
        watch: !!document.querySelector('[data-title-watchlist]'),
      }));
      await t.shot('upcoming');
      assert(/Upcoming/.test(d.upcoming || ''), 'no Upcoming badge');
      assert(!d.seen && /Out/.test(d.tile || ''), `seen ${d.seen}, tile ${d.tile}`);
      assert(/Notify me|Following/.test(d.bell || ''), `bell: ${d.bell}`);
      assert(d.watch, 'no watchlist toggle');
      const startFollowing = /Following/.test(d.bell);
      const isState = (following) => t.waitFor((f) => (f ? /Following/ : /Notify me/).test(document.querySelector('.title-follow-btn')?.innerText || ''), following);
      const token = await t.mark();
      await t.pressSel('.title-follow-btn');
      await isState(!startFollowing);
      await t.pressSel('.title-follow-btn');
      await isState(startFollowing);
      await t.stillHere(token);
      await t.noOverflow();
      return `${upcoming.title}: "${d.upcoming}", tile "${d.tile}", bell toggled and back`;
    });

    await t.step('TV: returning show has the bell, ended show none; Watching / Seen in place, no nudge', async () => {
      await t.go(`/tv/${TV.breakingBad}`);
      assert(!(await page.evaluate(() => !!document.querySelector('.title-follow'))), 'bell on Breaking Bad (Ended)');
      await t.go(`/tv/${TV.severance}`);
      const s = await page.evaluate(() => ({ bell: !!document.querySelector('.title-follow'), nudge: !!document.querySelector('[data-rate-nudge]') }));
      assert(s.bell, 'no bell on Severance (Returning Series)');
      assert(!s.nudge, 'Rate it? rendered on a show');
      const token = await t.mark();
      await t.pressSel('[data-progress-value="watching"]');
      await t.waitFor(() => document.querySelector('[data-progress-value="watching"].is-on') && !document.querySelector('[data-progress-value="dropped"].is-on'));
      await t.shot('severance-watching');
      await t.pressSel('[data-title-seen]');
      await t.waitFor(() => document.querySelector('[data-title-seen].is-on') && !document.querySelector('[data-progress-value="watching"].is-on'));
      await t.pressSel('[data-title-seen]');
      await t.waitFor(() => document.querySelector('[data-title-seen]:not(.is-on)'));
      await t.stillHere(token);
      const n = await page.evaluate(() => [...document.querySelectorAll('.title-actions > *')].filter((a) => a.offsetParent).length);
      await t.go(`/tv/${TV.severance}`);
      const top = await page.evaluate(() => Math.max(...[...document.querySelectorAll('.title-actions > *')].filter((a) => a.offsetParent).map((a) => a.getBoundingClientRect().bottom)));
      assert(n === 4, `${n} TV actions`);
      assert(top <= t.vp.height, `TV actions end at ${Math.round(top)}`);
      await t.noOverflow();
      return `4 actions above the fold (${Math.round(top)}/${t.vp.height}); Watching, Seen on/off in place`;
    });

    // ---------- Watchlist page ----------
    // A watchlist tile by its title id (its tile actions carry data-key="movie:14160"), so a database with "Upgrade" or
    // another "Up..." title on the watchlist can't confuse it.
    const itemByKey = (key, title) => page.evaluateHandle((k, n) => document.querySelector(`.watchlist-item [data-key="${k}"]`)?.closest('.watchlist-item')
      || [...document.querySelectorAll('.watchlist-item')].find((i) => i.querySelector('h3 > span')?.textContent.trim() === n), key, title);
    const count = (status) => page.$eval(`.watchlist-tabs [data-status="${status}"] .status-count`, (e) => Number(e.innerText));

    await t.step('watchlist: Remove in place with Undo, counts move', async () => {
      await t.go('/watchlist?status=want_to_watch&type=movie');
      const before = await count('want_to_watch');
      const removeVisible = await page.evaluate(() => [...document.querySelectorAll('.watchlist-item')].every((i) => {
        const b = i.querySelector('.watchlist-act.is-remove');
        return b && b.offsetParent && b.getBoundingClientRect().width >= 30;
      }));
      assert(removeVisible, 'a tile without a visible Remove');
      await t.shot('watchlist');
      const token = await t.mark();
      const upItem = await itemByKey(`movie:${M.up}`, titles.up);
      assert(await upItem.evaluate((i) => !!i), `${titles.up} not on the watchlist`);
      await t.press(await upItem.$('.watchlist-act.is-remove'));
      await t.waitFor(() => document.querySelector('.watchlist-toast.visible'));
      const toast = await page.evaluate(() => document.querySelector('.watchlist-toast.visible').innerText);
      const gone = await upItem.evaluate((i) => i.hidden);
      const after = await count('want_to_watch');
      await t.shot('watchlist-removed');
      assert(gone && after === before - 1, `hidden ${gone}, count ${before} -> ${after}`);
      assert(toast.includes(`Removed`) && toast.includes(titles.up), `toast "${toast}"`);
      await t.pressSel('.watchlist-toast-undo');
      await t.waitFor(() => /back/i.test(document.querySelector('.watchlist-toast')?.innerText || ''));
      const back = await upItem.evaluate((i) => !i.hidden);
      assert(back && (await count('want_to_watch')) === before, 'undo did not restore');
      await t.stillHere(token);
      return `"${toast.replace(/\s+/g, ' ')}", undo restored (${before})`;
    });

    await t.step('watchlist: Seen and TV Watching move tiles in place', async () => {
      // The watchlist shows movies or TV, not both (it opens on Movies): Dune on the movie view, The Last of Us on TV.
      await t.go('/watchlist?status=want_to_watch&type=movie');
      let token = await t.mark();
      const seen0 = await count('watched');
      const dune = await itemByKey(`movie:${M.dune2}`, titles.dune2);
      assert(await dune.evaluate((i) => !!i), `${titles.dune2} not on the watchlist`);
      await t.press(await dune.$('.watchlist-act.is-seen'));
      await t.waitFor(() => /seen/i.test(document.querySelector('.watchlist-toast.visible')?.innerText || ''));
      assert(await dune.evaluate((i) => i.hidden), `${titles.dune2} still on To watch`);
      assert((await count('watched')) === seen0 + 1, 'Seen count did not move');
      await t.stillHere(token);
      await t.go('/watchlist?status=want_to_watch&type=tv');
      token = await t.mark();
      const watching0 = await count('watching');
      const tlou = await itemByKey(`tv:${TV.tlou}`, titles.tlou);
      assert(await tlou.evaluate((i) => !!i), `${titles.tlou} not on the watchlist`);
      await t.press(await tlou.$('.watchlist-act.is-watching'));
      await t.waitFor(() => /Watching/.test(document.querySelector('.watchlist-toast.visible')?.innerText || ''));
      assert(await tlou.evaluate((i) => i.hidden), `${titles.tlou} still on To watch`);
      assert((await count('watching')) === watching0 + 1, 'Watching count did not move');
      await t.stillHere(token);
      await t.go('/watchlist?status=watching&type=tv');
      assert(await page.evaluate((n) => document.body.innerText.includes(n), titles.tlou), 'not on the Watching tab');
      await t.shot('watchlist-watching-tv');
      await t.noOverflow();
      return `${titles.dune2} -> Seen, ${titles.tlou} -> Watching`;
    });

    await t.step('watchlist: a seen title on To watch is tagged Rewatch; Remove keeps it seen', async () => {
      // Coco is Watched (reset); put it on the watchlist too.
      const added = await t.api.must('PUT', `/watchlist/movie/${M.coco}`);
      assert(added.onWatchlist && added.seen && added.status === 'watched', JSON.stringify(added));
      await t.go('/watchlist?status=want_to_watch&type=movie');
      const token = await t.mark();
      const seen0 = await count('watched');
      const coco = await itemByKey(`movie:${M.coco}`, added.title);
      assert(await coco.evaluate((i) => !!i), `${added.title} not on To watch`);
      const card = await coco.evaluate((i) => ({
        tag: i.querySelector('.watchlist-note.is-rewatch')?.innerText.trim(),
        seenButton: !!i.querySelector('.watchlist-act.is-seen'),
        eye: !!i.querySelector('.tile-seen.is-on'),
      }));
      assert(card.tag === 'Rewatch' && !card.seenButton && card.eye, JSON.stringify(card));
      await coco.evaluate((i) => i.scrollIntoView({ block: 'center' }));
      await t.shot('watchlist-rewatch');
      await t.press(await coco.$('.watchlist-act.is-remove'));
      await t.waitFor(() => /Removed/.test(document.querySelector('.watchlist-toast.visible')?.innerText || ''));
      assert(await coco.evaluate((i) => i.hidden), 'still on To watch');
      assert((await count('watched')) === seen0, 'the Seen count moved');
      await t.stillHere(token);
      const st = await t.api.must('GET', '/statuses?status=watched&type=movie');
      assert(st.items.some((i) => i.tmdbId === M.coco && !i.onWatchlist), 'Coco is not Watched any more');
      return `tagged "${card.tag}", removed, still seen`;
    });

    // ---------- List tick-off ----------
    await t.step('list: tick-off mode updates progress in place; tabs keep the mode', async () => {
      for (const id of [M.insideOut, M.wallE]) await setTitle(t.api, 'movie', id, { score: null, status: null });
      await t.go(`/list/${listA}`);
      const start = await page.$('.list-tick-start');
      assert(start, 'no "Mark what you\'ve seen"');
      await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle2' }), t.press(start)]);
      assert(page.url().includes('mark=1'), page.url());
      const text = () => page.$eval('.list-progress-text', (e) => e.innerText);
      const item = (id) => page.evaluateHandle((k) => document.querySelector(`.list-item [data-key="${k}"]`)?.closest('.list-item'), `movie:${id}`);
      // Ticking a poster opens the "Rate it?" prompt, which can cover the filter tabs and the next poster: Skip closes it.
      const closePrompt = async () => {
        await page.evaluate(() => { const p = document.querySelector('[data-rate-pop]'); if (p && !p.hidden) p.querySelector('[data-pop-skip]')?.click(); });
        await t.waitFor(() => { const p = document.querySelector('[data-rate-pop]'); return !p || p.hidden; }, null, 5000).catch(() => {});
      };
      const before = await text();
      assert(/seen 1 of 3/.test(before), `progress "${before}" (expected 1 of 3)`);
      const token = await t.mark();
      const io = await item(M.insideOut);
      await t.press(await io.$('.poster-frame'));
      await t.waitFor((x) => document.querySelector('.list-progress-text').innerText !== x, before);
      const after = await text();
      assert(await io.evaluate((c) => c.classList.contains('is-seen')), 'no check state');
      const tick = await page.$eval('.list-tick-count', (e) => e.innerText);
      assert(tick === after, `tick bar "${tick}" vs "${after}"`);
      assert(!(await page.evaluate(() => !!document.querySelector('.title-nudge:not([hidden])'))), 'nudge in tick-off');
      await t.shot('list-tick');
      await closePrompt();
      // The filter tabs swap in place; tick-off must keep working after the swap.
      await t.pressSel('.list-filter-tabs a[href*="show=unseen"]');
      await t.waitFor(() => location.search.includes('show=unseen') && document.querySelector('.list-grid.is-ticking'));
      // Close any "Rate it?" prompt so it does not cover the next poster.
      await closePrompt();
      const before2 = await text();
      const walle = await item(M.wallE);
      await t.press(await walle.$('.poster-frame'));
      await t.waitFor((x) => document.querySelector('.list-progress-text').innerText !== x, before2);
      await t.stillHere(token);
      await closePrompt();
      await t.press(await walle.$('.poster-frame'));
      await t.waitFor((x) => document.querySelector('.list-progress-text').innerText === x, before2);
      await t.pressSel('.list-filter-tabs a:not([href*="show="])');
      await t.waitFor(() => !location.search.includes('show=') && document.querySelector('.list-grid.is-ticking'));
      await closePrompt();
      const io2 = await item(M.insideOut);
      await t.press(await io2.$('.poster-frame'));
      await t.waitFor((x) => document.querySelector('.list-progress-text').innerText === x, before);
      await t.stillHere(token);
      const toy = await item(M.toyStory);
      await t.press(await toy.$('.poster-frame'));
      await t.waitFor(() => /rated/i.test(document.querySelector('.tile-toast.visible')?.innerText || ''));
      await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle2' }), t.pressSel('.list-tick-done')]);
      assert(!page.url().includes('mark=1'), `Done went to ${page.url()}`);
      await t.noOverflow();
      return `"${before}" -> "${after}" -> back; rated Toy Story stays seen`;
    });

    await t.step('list 2: upcoming title has no Seen toggle, badge shown', async () => {
      await t.go(`/list/${listB}?mark=1`);
      const d = await page.evaluate((k) => {
        const it = document.querySelector(`.list-item [data-key="${k}"]`)?.closest('.list-item');
        return { found: !!it, badge: it?.querySelector('.tile-upcoming')?.innerText.replace(/\s+/g, ' ').trim(), seen: !!it?.querySelector('.tile-seen') };
      }, upKey);
      await t.shot('list-upcoming');
      assert(d.found && /Upcoming/.test(d.badge || '') && !d.seen, JSON.stringify(d));
      await t.noOverflow();
      return `${upcoming.title}: ${d.badge}`;
    });

    if (t.mobile) {
      await t.step('regression: mobile bottom nav order', async () => {
        await t.go('/');
        const hrefs = await page.evaluate(() => [...document.querySelectorAll('.bottom-nav a')].map((a) => a.getAttribute('href')));
        assert(JSON.stringify(hrefs) === JSON.stringify(['/', '/ratings', '/discover', '/watchlist', '/lists']), `order ${hrefs.join(' ')}`);
        return hrefs.join(' ');
      });
    }
  },
};
