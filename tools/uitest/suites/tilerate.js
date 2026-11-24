// Poster-tile actions: the star rating picker on tiles, add to list from a tile, "Rate it?" after Seen in tick-off mode,
// the To rate view, ratings cards and the watchlist rows. Ported from the scratchpad's tilerate/check.js (+ seed.js, reset.js).
const { setTitle, setTvSeasons, createList, findUpcomingMovie } = require('../lib/api');

const M = { matrix: 603, interstellar: 157336, heat: 949, toyStory: 862, inception: 27205, dune2: 693134, pulp: 680, darkKnight: 155, fightClub: 550 };
const TV = { breakingBad: 1396, bear: 136315, severance: 95396 };

module.exports = {
  name: 'tilerate',
  description: 'tile star rating, tile add to list, Rate it? in tick-off, To rate, ratings cards, watchlist rows',

  async setup(env) {
    const upcoming = env.shared.upcoming || (env.shared.upcoming = await findUpcomingMovie(env.api));
    const items = [M.matrix, M.heat, M.toyStory, M.dune2, M.interstellar, upcoming.tmdbId, M.inception, M.pulp, M.darkKnight].map((id) => ['movie', id]);
    items.push(['tv', TV.breakingBad]);
    const scifiName = `${env.prefix} sci-fi`;
    return {
      upcoming,
      scifiName,
      main: await createList(env.api, `${env.prefix} tiles`, items),
      scifi: await createList(env.api, scifiName, [['movie', M.interstellar]]),
    };
  },

  async reset(env, data) {
    const api = env.api;
    await setTitle(api, 'movie', M.matrix, { score: 8.5 });
    await setTitle(api, 'movie', M.interstellar, { score: 9 });
    await setTitle(api, 'movie', M.heat, { detailed: { directing: 9, cinematography: 8, acting: 9, soundtrack: 7, screenplay: 8, reviewTitle: 'Diner scene', reviewText: 'Still the best.' } });
    // Seen but unrated: these fill the To rate view.
    await setTitle(api, 'movie', M.toyStory, { score: null, status: 'watched' });
    await setTitle(api, 'movie', M.inception, { score: null, status: 'watched' });
    await setTvSeasons(api, TV.breakingBad, {});
    await setTitle(api, 'tv', TV.breakingBad, { status: 'watched' });
    await setTvSeasons(api, TV.bear, { 1: 8 });
    await setTitle(api, 'movie', M.dune2, { score: null, status: 'want_to_watch' });
    await setTitle(api, 'movie', data.upcoming.tmdbId, { score: null, status: 'want_to_watch' });
    await setTvSeasons(api, TV.severance, {});
    await setTitle(api, 'tv', TV.severance, { status: 'want_to_watch' });
    for (const id of [M.pulp, M.darkKnight, M.fightClub]) await setTitle(api, 'movie', id, { score: null, status: null });
  },

  async run(t) {
    const { page, assert, mobile } = t;
    const { main, scifiName } = t.data;
    const popOpen = () => t.waitFor(() => { const p = document.querySelector('[data-rate-pop]'); return p && !p.hidden; });

    /** Gives the element returned by `fn` (run in the page with `args`) the id `id`, scrolls it into view, says what it is. */
    const tag = async (id, fn, ...args) => {
      const h = await page.evaluateHandle(fn, ...args);
      const info = await h.evaluate((el, markId) => {
        if (!el) return null;
        document.querySelectorAll('#' + markId).forEach((x) => x.removeAttribute('id'));
        el.id = markId;
        el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
        const stack = el.closest('[data-tile-actions]');
        return stack ? stack.dataset.key + ' ' + stack.dataset.title : el.outerHTML.slice(0, 80);
      }, id);
      await h.dispose();
      if (info) await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
      return info;
    };
    /** Uses the quick action `act` of the poster that holds the element tagged `id` (the "⋯" chooser on touch, hover with a mouse). */
    const posterAct = async (id, act) => {
      const stack = await page.evaluateHandle((x) => document.getElementById(x).closest('[data-tile-actions]'), id);
      return t.tileAct(stack.asElement(), act);
    };
    /** Clicks an element where it can be seen (not under the open picker / prompt), scrolling the page if needed. */
    const tapVisible = async (id) => {
      let point = null;
      for (let i = 0; i < 12 && !point; i++) {
        point = await page.evaluate((elId) => {
          const el = document.getElementById(elId);
          const r = el.getBoundingClientRect();
          for (const fy of [0.3, 0.5, 0.7]) {
            const x = r.left + r.width / 2;
            const y = r.top + r.height * fy;
            if (y < 70 || y > window.innerHeight - 10) continue;
            const hit = document.elementFromPoint(x, y);
            if (hit && el.contains(hit)) return { x, y };
          }
          const pop = document.querySelector('[data-rate-pop]');
          const popTop = pop && !pop.hidden && pop.classList.contains('is-top');
          window.scrollBy({ top: popTop ? -140 : 140, behavior: 'instant' });
          return null;
        }, id);
        if (!point) await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
      }
      assert(point, 'could not reach #' + id);
      await page.mouse.click(point.x, point.y);
    };
    /** Every tile's actions fit inside its poster, the bottom group clear of the top stack and the corner badges. */
    const fitCheck = () => page.evaluate(() => {
      const bad = [];
      let smallest = 9999;
      document.querySelectorAll('[data-tile-actions]').forEach((stack) => {
        const r = stack.getBoundingClientRect();
        if (!r.width || stack.closest('.rank-item')) return;
        smallest = Math.min(smallest, r.width);
        const bottom = stack.querySelector('.tile-acts-bottom');
        const tops = [...stack.querySelectorAll(':scope > .tile-act:not([hidden])')].filter((e) => getComputedStyle(e).display !== 'none');
        if (bottom && getComputedStyle(bottom).display !== 'none') {
          const b = bottom.getBoundingClientRect();
          if (b.left < r.left - 0.5 || b.right > r.right + 0.5 || b.bottom > r.bottom + 0.5) bad.push(`${stack.dataset.title}: bottom outside poster`);
          tops.forEach((x) => { if (x.getBoundingClientRect().bottom > b.top + 0.5) bad.push(`${stack.dataset.title}: top stack overlaps bottom`); });
          stack.parentElement.querySelectorAll('.list-rank, .tile-upcoming').forEach((badge) => {
            const g = badge.getBoundingClientRect();
            if (g.width && g.right > b.left && g.bottom > b.top && g.top < b.bottom) bad.push(`${stack.dataset.title}: badge under the bottom group`);
          });
        }
      });
      return { bad: bad.slice(0, 5), count: bad.length, smallest: Math.round(smallest) };
    });
    // Deletes a quick rating the page way (as the tile picker does), so the next viewport starts from the seed again.
    const tileDelete = (tmdbId) => page.evaluate(async (id) => {
      const csrf = document.querySelector('input[name="_csrf"]').value;
      await fetch('/movie/rate/tile/delete', { method: 'POST', body: new URLSearchParams({ _csrf: csrf, tmdbId: String(id) }), headers: { 'X-Requested-With': 'fetch' } });
    }, tmdbId);

    await t.step('login', () => t.login());

    // ---------- Home ----------
    await t.step('home: rate an unrated movie from a tile (no reload, every copy, chip, Seen on)', async () => {
      await t.go('/');
      const token = await t.mark();
      const fit = await fitCheck();
      assert(fit.count === 0, 'fit: ' + fit.bad.join('; '));
      const target = await tag('target-star', () => [...document.querySelectorAll('.home-row:not(#row-coming-soon) a[data-tile-rate="movie"]:not([data-score])')]
        .find((a) => !a.closest('[data-tile-actions]').querySelector('.tile-seen.is-on')));
      assert(target, 'no unrated movie tile');
      const key = target.split(' ')[0];
      await posterAct('target-star', 'rate');
      await popOpen();
      const info = await page.evaluate(() => {
        const p = document.querySelector('[data-rate-pop]');
        return { sheet: p.classList.contains('is-sheet'), buttons: p.querySelectorAll('.tile-rate-score-btn').length, deleteHidden: p.querySelector('[data-pop-delete-form]').hidden,
          backdrop: !document.querySelector('.tile-rate-backdrop').hidden, inView: p.getBoundingClientRect().right <= window.innerWidth && p.getBoundingClientRect().left >= 0 };
      });
      assert(info.buttons === 10 && info.deleteHidden && info.inView, JSON.stringify(info));
      assert(info.sheet === mobile && info.backdrop === mobile, 'sheet on phones only: ' + JSON.stringify(info));
      await t.shot('home-picker-open');
      const copies = await page.evaluate((k) => document.querySelectorAll(`[data-tile-actions][data-key="${k}"]`).length, key);
      await page.click('[data-rate-pop] .tile-rate-score-btn[value="7"]');
      const toast = await t.toast();
      await t.waitFor((k) => [...document.querySelectorAll(`[data-tile-actions][data-key="${k}"] .tile-rate`)].every((s) => s.classList.contains('is-on')), key);
      // The "★ 7.0" mark fades in (CSS transition): wait for it to finish before reading its opacity.
      await page.mouse.move(2, 2);
      await t.waitFor((k) => getComputedStyle(document.querySelector(`[data-tile-actions][data-key="${k}"] .tile-rate`)).opacity === '1', key, 5000).catch(() => {});
      const after = await page.evaluate((k) => ({
        chips: [...document.querySelectorAll(`[data-tile-actions][data-key="${k}"] .tile-rate-score`)].map((s) => s.textContent),
        seen: [...document.querySelectorAll(`[data-tile-actions][data-key="${k}"] .tile-seen`)].every((s) => s.classList.contains('is-on') && s.classList.contains('is-locked')),
        popHidden: document.querySelector('[data-rate-pop]').hidden,
        chipVisible: getComputedStyle(document.querySelector(`[data-tile-actions][data-key="${k}"] .tile-rate`)).opacity,
      }), key);
      await t.stillHere(token);
      assert(after.chips.length === copies && after.chips.every((c) => c === '7.0'), 'chips ' + JSON.stringify(after.chips));
      assert(after.seen && after.popHidden && after.chipVisible === '1', JSON.stringify(after));
      await t.shot('home-rated');
      return `${target}; copies ${copies}; toast "${toast}"`;
    }, { retry: false });

    await t.step('home: smallest tiles fit all actions, no horizontal overflow', async () => {
      const fit = await fitCheck();
      await t.noOverflow();
      assert(fit.count === 0, fit.bad.join('; '));
      await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
      await t.shot('home-top');
      return `smallest tile ${fit.smallest}px`;
    });

    await t.step('home: upcoming tiles (Coming soon) have no star, list button up top', async () => {
      const info = await page.evaluate(() => {
        const row = document.querySelector('#row-coming-soon');
        if (!row) return null;
        const stacks = [...row.querySelectorAll('[data-tile-actions].is-upcoming')];
        return { upcoming: stacks.length, stars: row.querySelectorAll('.tile-actions.is-upcoming .tile-rate').length, lists: stacks.filter((s) => s.querySelector(':scope > .tile-list')).length };
      });
      assert(info && info.upcoming > 0, 'no upcoming tiles ' + JSON.stringify(info));
      assert(info.stars === 0 && info.lists === info.upcoming, JSON.stringify(info));
      await page.evaluate(() => document.querySelector('#row-coming-soon').scrollIntoView({ block: 'center', behavior: 'instant' }));
      await t.shot('home-coming-soon');
      return JSON.stringify(info);
    });

    // ---------- Add to list from a tile ----------
    await t.step('home: add to list from a tile (sheet in place, tick, button ticked)', async () => {
      await t.go('/');
      const token = await t.mark();
      const target = await tag('target-list', () => [...document.querySelectorAll('.home-row:not(#row-your-lists) a[data-tile-lists]:not(.is-on)')][1]);
      assert(target, 'no list button');
      const key = target.split(' ')[0];
      await posterAct('target-list', 'lists');
      await t.waitFor(() => { const m = document.querySelector('[data-tile-sheet] #addToListModal'); return m && m.classList.contains('visible'); });
      await t.shot('home-list-sheet');
      const found = await page.evaluate((n) => {
        const f = [...document.querySelectorAll('#addToListModal form[data-list-member]')].find((x) => x.querySelector('.list-selection-name')?.textContent.trim() === n);
        if (f) f.querySelector('button').id = 'tick-scifi';
        return !!f;
      }, scifiName);
      assert(found, `seeded list "${scifiName}" not in the sheet`);
      await page.click('#tick-scifi');
      const toast = await t.toast();
      await t.waitFor((k) => [...document.querySelectorAll(`[data-tile-actions][data-key="${k}"] .tile-list`)].every((b) => b.classList.contains('is-on')), key);
      await t.shot('home-list-ticked');
      await page.keyboard.press('Escape');
      await t.waitFor(() => document.querySelectorAll('[data-tile-sheet]').length === 0, null, 10000);
      await t.stillHere(token);
      return `${target}; toast "${toast}"`;
    });

    // ---------- Search: edit a quick rating, detailed opens the title page's modal, TV ----------
    await t.step('search: a quick rating opens prefilled and changes in place', async () => {
      await t.go('/?q=the+matrix');
      const target = await tag('target-star', () => document.querySelector('[data-tile-actions][data-key="movie:603"] a[data-tile-rate]'));
      assert(target, 'no Matrix tile');
      await posterAct('target-star', 'rate');
      await popOpen();
      const info = await page.evaluate(() => {
        const p = document.querySelector('[data-rate-pop]');
        return { current: p.querySelector('[data-pop-current-score]').textContent, kind: p.querySelector('[data-pop-kind]').textContent,
          pressed: [...p.querySelectorAll('.tile-rate-score-btn[aria-pressed="true"]')].map((b) => b.value), del: !p.querySelector('[data-pop-delete-form]').hidden };
      });
      assert(info.kind === 'Quick rating' && info.del, JSON.stringify(info));
      await t.shot('search-picker-quick');
      await page.click('[data-rate-pop] .tile-rate-score-btn[value="9"]');
      const toast = await t.toast();
      await t.waitFor(() => document.querySelector('[data-key="movie:603"] .tile-rate-score')?.textContent === '9.0');
      return `was ${info.current}; toast "${toast}"`;
    });

    await t.step('search: delete a quick rating (confirm step), stays seen', async () => {
      await t.go('/?q=the+matrix');
      await tag('target-star', () => document.querySelector('[data-tile-actions][data-key="movie:603"] a[data-tile-rate]'));
      await posterAct('target-star', 'rate');
      await popOpen();
      await page.click('[data-rate-pop] [data-pop-delete]');
      await t.waitFor(() => document.querySelector('[data-rate-pop] .tile-rate-danger')?.offsetParent);
      await t.shot('search-delete-confirm');
      await page.click('[data-rate-pop] .tile-rate-danger');
      const toast = await t.toast('Deleted');
      await t.waitFor(() => !document.querySelector('[data-key="movie:603"] .tile-rate').classList.contains('is-on'));
      const seen = await page.evaluate(() => { const s = document.querySelector('[data-key="movie:603"] .tile-seen'); return s.classList.contains('is-on') && !s.classList.contains('is-locked'); });
      assert(seen, 'should stay seen, unlocked');
      return `toast "${toast}"`;
    });

    await t.step('search: a detailed rating shows Edit rating, which opens the title page modal in edit mode', async () => {
      await t.go('/?q=heat');
      const target = await tag('target-star', () => document.querySelector('[data-tile-actions][data-key="movie:949"] a[data-tile-rate]'));
      assert(target, 'no Heat tile');
      await posterAct('target-star', 'rate');
      await popOpen();
      const info = await page.evaluate(() => { const p = document.querySelector('[data-rate-pop]'); return { formHidden: p.querySelector('[data-pop-form]').hidden, detailedShown: !p.querySelector('[data-pop-detailed]').hidden, href: p.querySelector('[data-pop-edit]').getAttribute('href'), del: p.querySelector('[data-pop-delete-form]').hidden }; });
      assert(info.formHidden && info.detailedShown && info.href === '/movie/949?rate=edit' && info.del, JSON.stringify(info));
      await t.shot('search-picker-detailed');
      await t.navClick('[data-rate-pop] [data-pop-edit]');
      await t.waitFor(() => document.getElementById('rateMovieModal')?.classList.contains('visible'));
      const modal = await page.evaluate(() => {
        const m = document.getElementById('rateMovieModal');
        return { visible: m.classList.contains('visible'), mode: m.dataset.ratingMode, action: m.querySelector('form').getAttribute('action'), url: location.search,
          directing: m.querySelector('.rate-slider[data-name="directing"]').dataset.value };
      });
      assert(modal.mode === 'detailed' && modal.action === '/movie/rate/update' && modal.url === '', JSON.stringify(modal));
      await t.shot('title-edit-modal');
      return `modal ${JSON.stringify(modal)}`;
    });

    await t.step('title page: ?rate=detailed opens the modal in detailed mode for an unrated movie', async () => {
      await t.go(`/movie/${M.fightClub}?rate=detailed`);
      await t.waitFor(() => document.getElementById('rateMovieModal')?.classList.contains('visible'));
      const modal = await page.evaluate(() => { const m = document.getElementById('rateMovieModal'); return { mode: m.dataset.ratingMode, action: m.querySelector('form').getAttribute('action'), url: location.search }; });
      assert(modal.mode === 'detailed' && modal.action === '/movie/rate' && modal.url === '', JSON.stringify(modal));
      await page.keyboard.press('Escape');
      return JSON.stringify(modal);
    });

    await t.step('search: a show rated by season shows its average; the picker links to the seasons', async () => {
      await t.go('/?q=the+bear');
      const info = await page.evaluate(() => {
        const a = document.querySelector('[data-tile-actions][data-key="tv:136315"] .tile-rate');
        return a && { href: a.getAttribute('href'), seasons: a.dataset.seasons === 'true', chip: a.querySelector('.tile-rate-score')?.textContent };
      });
      assert(info && info.href === '/tv/136315#seasons' && info.seasons && info.chip === '8.0', JSON.stringify(info));
      await tag('tv-star', () => document.querySelector('[data-tile-actions][data-key="tv:136315"] .tile-rate'));
      await t.shot('search-tv');
      // A show rated by season: the picker shows the average and "Rate seasons…", which goes to its seasons.
      await posterAct('tv-star', 'rate');
      await popOpen();
      const edit = await page.evaluate(() => { const p = document.querySelector('[data-rate-pop]'); const a = p.querySelector('[data-pop-edit]'); return { href: a.getAttribute('href'), shown: !p.querySelector('[data-pop-detailed]').hidden }; });
      assert(edit.shown && edit.href === '/tv/136315#seasons', `picker: ${JSON.stringify(edit)}`);
      await t.shot('search-tv-picker');
      await t.navClick('[data-rate-pop] [data-pop-edit]');
      assert(page.url().endsWith('/tv/136315#seasons'), page.url());
      return JSON.stringify(info);
    });

    // ---------- Discover ----------
    await t.step('discover: grid tiles carry the actions and fit', async () => {
      await t.go('/discover?type=movie&genre=878&hideSeen=0');
      const fit = await fitCheck();
      const o = await t.overflow();
      const counts = await page.evaluate(() => ({ tiles: document.querySelectorAll('.browse-tile').length, stars: document.querySelectorAll('.browse-tile .tile-rate').length,
        oldScore: [...document.querySelectorAll('.browse-user-score')].filter((e) => getComputedStyle(e).display !== 'none').length }));
      assert(o <= 0 && fit.count === 0 && counts.oldScore === 0 && counts.tiles > 0, JSON.stringify({ o, fit, counts }));
      await t.shot('discover-grid');
      return `${JSON.stringify(counts)}; smallest ${fit.smallest}px`;
    });

    // ---------- Watchlist ----------
    await t.step('watchlist: same buttons on movie and TV tiles, labels never cut, star on the poster', async () => {
      await t.go('/watchlist');
      const info = await page.evaluate(() => {
        const rows = [...document.querySelectorAll('.watchlist-item:not([hidden]) .watchlist-actions')];
        const cut = [];
        rows.forEach((row) => row.querySelectorAll('.watchlist-act span, .watchlist-note span').forEach((s) => {
          if (getComputedStyle(s).display !== 'none' && s.scrollWidth > s.clientWidth + 1) cut.push(s.textContent);
        }));
        return { tiles: rows.length, cut, stars: document.querySelectorAll('.watchlist-item .tile-rate').length };
      });
      await t.noOverflow();
      assert(info.tiles > 0 && info.cut.length === 0, JSON.stringify(info));
      await t.shot('watchlist', true);
      return JSON.stringify(info);
    });

    await t.step('watchlist: rating a To watch movie from its star moves it to Seen', async () => {
      await t.go('/watchlist');
      const target = await tag('w-star', (k) => document.querySelector(`.watchlist-item:not([hidden]) [data-key="${k}"] a[data-tile-rate]`), `movie:${M.dune2}`);
      assert(target, 'Dune: Part Two has no star on To watch');
      const before = await page.evaluate(() => [...document.querySelectorAll('.watchlist-tabs .status-count')].map((e) => e.textContent).join(','));
      await posterAct('w-star', 'rate');
      await popOpen();
      await page.click('[data-rate-pop] .tile-rate-score-btn[value="7"]');
      await t.toast();
      await t.waitFor(() => document.querySelector('#w-star').closest('.watchlist-item').hidden);
      const after = await page.evaluate(() => [...document.querySelectorAll('.watchlist-tabs .status-count')].map((e) => e.textContent).join(','));
      await tileDelete(M.dune2);
      return `${target}: ${before} -> ${after}`;
    }, { retry: false });

    // ---------- List page: rate in place, tick-off + Rate it? ----------
    await t.step('list page: chips, rate in place updates progress', async () => {
      await t.go(`/list/${main}`);
      const token = await t.mark();
      const fit = await fitCheck();
      assert(fit.count === 0, fit.bad.join('; '));
      const before = await page.evaluate(() => document.querySelector('[data-list-progress]').dataset.seen);
      const target = await tag('target-star', () => document.querySelector('.list-item [data-key="movie:680"] a[data-tile-rate]:not([data-score])'));
      await t.shot('list-before');
      assert(target, 'Pulp Fiction is not unrated on the seeded list');
      await posterAct('target-star', 'rate');
      await popOpen();
      await page.click('[data-rate-pop] .tile-rate-score-btn[value="6"]');
      await t.toast();
      await t.waitFor((b) => Number(document.querySelector('[data-list-progress]').dataset.seen) === Number(b) + 1, before);
      const text = await page.evaluate(() => document.querySelector('[data-progress-text]').textContent);
      await t.stillHere(token);
      await t.shot('list-rated');
      await tileDelete(M.pulp);
      return `${target}: ${text}`;
    }, { retry: false });

    await t.step('list tick-off: Seen asks "Rate it?" on that tile; ticking the next one moves the prompt', async () => {
      for (const id of [M.pulp, M.darkKnight]) await setTitle(t.api, 'movie', id, { score: null, status: null });
      await t.go(`/list/${main}?mark=1`);
      const unseen = await page.evaluate(() => ['movie:680', 'movie:155'].filter((k) => document.querySelector(`.list-item:not(.is-seen) [data-tile-actions][data-key="${k}"]`)));
      assert(unseen.length === 2, 'need two unseen released movies: ' + unseen);
      const card = (k) => document.querySelector(`.list-item [data-tile-actions][data-key="${k}"]`).closest('.list-item').querySelector('a.list-card');
      await tag('tick-a', card, unseen[0]);
      await page.click('#tick-a');
      await t.waitFor(() => { const p = document.querySelector('[data-rate-pop]'); return p && !p.hidden && p.classList.contains('is-prompt'); });
      const prompt = await page.evaluate(() => { const p = document.querySelector('[data-rate-pop]'); return { title: p.querySelector('[data-pop-title]').textContent, sub: p.querySelector('[data-pop-sub-text]').textContent, skip: !p.querySelector('[data-pop-skip]').hidden, ask: !p.querySelector('[data-pop-ask-row]').hidden, backdrop: !document.querySelector('.tile-rate-backdrop').hidden }; });
      assert(prompt.skip && prompt.ask && !prompt.backdrop, JSON.stringify(prompt));
      await t.shot('tick-prompt');
      await page.evaluate((k) => { document.querySelector(`.list-item [data-tile-actions][data-key="${k}"]`).closest('.list-item').querySelector('a.list-card').id = 'tick-b'; }, unseen[1]);
      await tapVisible('tick-b');
      await t.waitFor((k) => { const p = document.querySelector('[data-rate-pop]'); return p && !p.hidden && document.querySelector(`.list-item.is-seen [data-key="${k}"]`); }, unseen[1]);
      const second = await page.evaluate(() => document.querySelector('[data-rate-pop] [data-pop-title]').textContent);
      assert(second !== prompt.title, 'prompt should move to the second tile');
      await t.shot('tick-prompt-second');
      await page.click('[data-rate-pop] [data-pop-skip]');
      await t.waitFor(() => document.querySelector('[data-rate-pop]').hidden);
      // The reset before the next viewport (and the run's restore) clears both again.
      return `prompt "${prompt.sub}" on ${prompt.title}, then ${second}`;
    });

    // ---------- Ratings tab ----------
    await t.step('ratings: ranking cards carry the actions; star opens the picker', async () => {
      await t.go('/movies');
      const info = await page.evaluate(() => ({
        cards: document.querySelectorAll('.rank-item').length,
        stars: document.querySelectorAll('.rank-item .tile-rate.is-on').length,
        lists: document.querySelectorAll('.rank-item .tile-list').length,
        count: document.querySelector('[data-ratings-unrated-count]')?.textContent,
      }));
      await t.noOverflow();
      assert(info.cards > 0 && info.stars === info.cards && info.lists === info.cards, JSON.stringify(info));
      await t.shot('ratings-movies', true);
      const star = await tag('rank-star', () => document.querySelector('.rank-item [data-key="movie:949"] a[data-tile-rate]'));
      assert(star, 'Heat not on the ranking');
      await posterAct('rank-star', 'rate');
      await popOpen();
      await t.shot('ratings-picker');
      await page.keyboard.press('Escape');
      return JSON.stringify(info);
    });

    await t.step('ratings: To rate view lists seen-unrated titles, rating one removes it', async () => {
      await t.go('/ratings/unrated');
      const read = () => page.evaluate(() => ({ tiles: document.querySelectorAll('.unrated-grid .home-tile-wrap').length,
        all: document.querySelector('[data-unrated-tab="all"] [data-unrated-count]').textContent, switchCount: document.querySelector('[data-ratings-unrated-count]').textContent }));
      const before = await read();
      await t.shot('unrated');
      await t.noOverflow();
      assert(before.tiles > 0, JSON.stringify(before));
      const target = await tag('u-star', (k) => document.querySelector(`.unrated-grid [data-key="${k}"] a[data-tile-rate]`), `movie:${M.inception}`);
      assert(target, 'Inception (seen, unrated) not on To rate');
      await posterAct('u-star', 'rate');
      await popOpen();
      await t.shot('unrated-picker');
      await page.click('[data-rate-pop] .tile-rate-score-btn[value="8"]');
      const toast = await t.toast();
      await t.waitFor((n) => document.querySelectorAll('.unrated-grid .home-tile-wrap').length === n - 1, before.tiles);
      const after = await read();
      assert(Number(after.all) === Number(before.all) - 1, JSON.stringify({ before, after }));
      await tileDelete(M.inception);
      return `${target}; toast "${toast}"; ${before.tiles} -> ${after.tiles} tiles`;
    }, { retry: false });

    // ---------- Without JS ----------
    await t.step('without JS the star is a link to the title page', async () => {
      await page.setJavaScriptEnabled(false);
      try {
        await t.go('/?q=inception');
        const href = await page.evaluate(() => document.querySelector('[data-key="movie:27205"] .tile-rate')?.getAttribute('href'));
        assert(href === '/movie/27205?rate=1', href);
        return href;
      } finally {
        await page.setJavaScriptEnabled(true);
      }
    });
  },
};
