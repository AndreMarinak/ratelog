// Title-page rows: the series row ("Toy Story Collection") and "More like this": skeleton first, lazy load, Seen /
// bookmark in place, progress, TV order, phone scrolling. Ported from the scratchpad's recs/check_recs.js.
const { setTitle, findUpcomingMovie } = require('../lib/api');

const TOY_STORY = [862, 863, 10193, 301528];
const WOLF = 106646;
const DUNE3 = 1170608;

module.exports = {
  name: 'recs',
  description: 'series row + More like this on title pages: lazy load, Seen/bookmark in place, progress, TV, phones',

  async setup(env) {
    return { upcoming: env.shared.upcoming || (env.shared.upcoming = await findUpcomingMovie(env.api)) };
  },

  async reset(env) {
    // The Toy Story films start unseen so the series progress can move.
    for (const id of TOY_STORY) await setTitle(env.api, 'movie', id, { score: null, status: null });
  },

  async run(t) {
    const { page, assert } = t;
    const open = async (url) => {
      const t0 = Date.now();
      await page.goto(t.base + url, { waitUntil: 'domcontentloaded', timeout: 90000 });
      const dcl = Date.now() - t0;
      const initial = await page.evaluate(() => ({
        pending: !!document.querySelector('.title-related.is-pending[data-src]'),
        rows: document.querySelectorAll('.related-row:not([aria-hidden])').length,
      }));
      return { dcl, initial, token: await t.mark() };
    };
    // Scrolls to the rows (phones load them only when near) and waits for them to be swapped in or removed.
    const loadRows = async () => {
      await page.evaluate(() => window.scrollTo({ top: document.body.scrollHeight, behavior: 'instant' }));
      await t.waitFor(() => !document.querySelector('.title-related.is-pending'), null, 45000);
      await page.waitForNetworkIdle({ idleTime: 300, timeout: 30000 }).catch(() => {});
      const rows = await page.evaluate(() => [...document.querySelectorAll('.related-row')].map((row) => {
        const track = row.querySelector('.home-row-track');
        return {
          id: row.id, title: row.querySelector('h2')?.innerText.trim(), progress: row.querySelector('.related-progress')?.innerText.trim() || null,
          tiles: [...row.querySelectorAll('.home-tile-wrap')].map((x) => ({
            title: x.querySelector('.home-tile-title')?.innerText.trim(), current: x.classList.contains('is-current'),
            upcoming: !!x.querySelector('.tile-upcoming'), actions: !!x.querySelector('[data-tile-actions]'),
            link: x.querySelector('a.home-tile')?.getAttribute('href') || null,
            img: x.querySelector('img')?.currentSrc?.replace(/^.*\/t\/p\//, '') || null, srcset: x.querySelector('img')?.getAttribute('srcset') || null,
          })),
          styled: track ? getComputedStyle(track).display === 'grid' && getComputedStyle(track).overflowX === 'auto' : false,
        };
      }));
      assert(rows.every((r) => r.styled), 'rows are unstyled');
      return rows;
    };
    const rowShot = async (name, selector) => {
      await page.evaluate((sel) => { const el = document.querySelector(sel); if (el) window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - 90, behavior: 'instant' }); }, selector);
      await t.shot(name);
    };

    await t.step('login', () => t.login());

    await t.step('Toy Story 2: first paint has only the skeleton', async () => {
      const { dcl, initial } = await open('/movie/863');
      assert(initial.pending, 'no skeleton in the first HTML');
      assert(initial.rows === 0, 'rows were rendered by the page itself');
      await t.shot('ts2-first-paint');
      return `DOMContentLoaded ${dcl} ms`;
    });

    await t.step('Toy Story 2: rows load lazily', async () => {
      await open('/movie/863');
      const rows = await loadRows();
      const series = rows.find((r) => r.id === 'related-series');
      const more = rows.find((r) => r.id === 'related-more');
      assert(series, 'no series row');
      assert(more, 'no More like this row');
      assert(/Toy Story Collection/.test(series.title), `series title ${series.title}`);
      const names = series.tiles.map((x) => x.title);
      assert(names[0] === 'Toy Story' && names[1] === 'Toy Story 2', `order ${names.join(', ')}`);
      const current = series.tiles.filter((x) => x.current);
      assert(current.length === 1 && current[0].title === 'Toy Story 2', 'current tile');
      assert(!current[0].actions && !current[0].link, 'current tile has actions or a link');
      assert(series.tiles.filter((x) => !x.current).every((x) => x.actions && x.link), 'other entries lack actions');
      assert(more.tiles.every((x) => !/^Toy Story( \d)?$/.test(x.title)), 'series entries in More like this');
      assert(more.tiles.length >= 8 && more.tiles.length <= 20, `${more.tiles.length} tiles`);
      const bad = [...series.tiles, ...more.tiles].filter((x) => x.img && !/^w(342|500)\//.test(x.img));
      assert(bad.length === 0, `poster sizes ${bad.map((b) => b.img).join(',')}`);
      assert(more.tiles.every((x) => !x.srcset || /w342.* 342w, .*w500.* 500w/.test(x.srcset)), 'srcset');
      await t.noOverflow();
      await rowShot('ts2-series', '#related-series');
      return `series: ${names.join(' | ')}; progress "${series.progress}"; more: ${more.tiles.length} tiles`;
    });

    if (!t.mobile) {
      await t.step('Toy Story 2: Seen on a series tile saves in place and moves the progress', async () => {
        const { token } = await open('/movie/863');
        await loadRows();
        const before = await page.$eval('[data-related-progress]', (el) => el.innerText);
        await page.evaluate(() => {
          const wrap = [...document.querySelectorAll('#related-series .home-tile-wrap')].find((x) => x.querySelector('.home-tile-title').innerText.trim() === 'Toy Story');
          wrap.querySelector('form[data-tile-act="seen"] button').click();
        });
        await t.waitFor((b) => document.querySelector('[data-related-progress]').innerText !== b, before);
        const on = await page.evaluate(() => !![...document.querySelectorAll('#related-series .home-tile-wrap')].find((x) => x.querySelector('.home-tile-title').innerText.trim() === 'Toy Story').querySelector('.tile-seen.is-on'));
        await t.stillHere(token);
        assert(on, 'eye not on');
        await rowShot('ts2-after-tile-seen', '#related-series');
        const after = await page.$eval('[data-related-progress]', (el) => el.innerText);
        // The page Seen button updates the current tile and the progress too.
        await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
        await page.click('[data-title-seen]');
        await t.waitFor((b) => document.querySelector('[data-related-progress]').innerText !== b, after);
        const cur = await page.evaluate(() => !document.querySelector('[data-related-current-seen]').hidden);
        await t.stillHere(token);
        assert(cur, 'current tile seen mark not shown');
        const last = await page.$eval('[data-related-progress]', (el) => el.innerText);
        await rowShot('ts2-after-page-seen', '#related-series');
        return `"${before}" -> "${after}" -> "${last}"`;
      }, { retry: false });

      await t.step('More like this: bookmark in place, Seen hides it on the next visit', async () => {
        const { token } = await open('/movie/863');
        await loadRows();
        const first = await page.evaluate(() => document.querySelector('#related-more .home-tile-wrap .home-tile-title').innerText.trim());
        const sel = '#related-more .home-tile-wrap form[data-tile-act="watchlist"]';
        await page.evaluate((s) => document.querySelector(s + ' button').click(), sel);
        await t.waitFor((s) => document.querySelector(s).classList.contains('is-on'), sel);
        await t.stillHere(token);
        await rowShot('more-bookmarked', '#related-more');
        await page.evaluate((s) => document.querySelector(s + ' button').click(), sel);
        await t.waitFor((s) => !document.querySelector(s).classList.contains('is-on'), sel);
        await page.evaluate(() => document.querySelector('#related-more .home-tile-wrap form[data-tile-act="seen"] button').click());
        await t.waitFor(() => document.querySelector('#related-more .home-tile-wrap .tile-seen').classList.contains('is-on'));
        const stays = await page.evaluate((x) => document.querySelector('#related-more .home-tile-wrap .home-tile-title').innerText.trim() === x, first);
        assert(stays, 'tile left the row at once');
        await open('/movie/863');
        const more = (await loadRows()).find((r) => r.id === 'related-more');
        assert(!more.tiles.some((x) => x.title === first), `${first} still shown after marking it seen`);
        return `${first}: bookmarked + unbookmarked in place, seen -> hidden on the next visit`;
      }, { retry: false });
    }

    await t.step('Wolf of Wall Street: More like this only', async () => {
      await open(`/movie/${WOLF}`);
      const rows = await loadRows();
      assert(!rows.some((r) => r.id === 'related-series'), 'unexpected series row');
      const more = rows.find((r) => r.id === 'related-more');
      assert(more && more.tiles.length >= 8, 'More like this missing or short');
      await t.noOverflow();
      await rowShot('wolf-more', '#related-more');
      return `${more.tiles.length} tiles: ${more.tiles.slice(0, 4).map((x) => x.title).join(', ')}...`;
    });

    await t.step('upcoming movie: its series row marks the upcoming entry', async () => {
      const up = t.data.upcoming;
      await open(`/movie/${up.tmdbId}`);
      const series = (await loadRows()).find((r) => r.id === 'related-series');
      if (!series) return `${up.title} is in no collection (nothing to check)`;
      const current = series.tiles.find((x) => x.current);
      assert(current, 'no current tile');
      assert(current.upcoming, `no Upcoming badge on ${current.title}`);
      await rowShot('upcoming-series', '#related-series');
      return `${series.title}: ${series.tiles.map((x) => x.title + (x.upcoming ? ' [upcoming]' : '')).join(' | ')}`;
    });

    await t.step('Dune: Part Three: series row', async () => {
      await open(`/movie/${DUNE3}`);
      const series = (await loadRows()).find((r) => r.id === 'related-series');
      assert(series, 'no series row');
      await rowShot('dune3-series', '#related-series');
      return `${series.title}: ${series.tiles.map((x) => x.title).join(' | ')}`;
    });

    await t.step('Breaking Bad: More like this after the seasons', async () => {
      const { initial } = await open('/tv/1396');
      assert(initial.pending, 'no skeleton');
      const rows = await loadRows();
      assert(!rows.some((r) => r.id === 'related-series'), 'TV got a series row');
      const more = rows.find((r) => r.id === 'related-more');
      assert(more && more.tiles.length >= 8, 'More like this missing or short');
      assert(more.tiles.every((x) => x.link && x.link.startsWith('/tv/')), 'TV links');
      const order = await page.evaluate(() => {
        const seasons = document.getElementById('seasons');
        const related = document.querySelector('.title-related');
        return seasons && related ? !!(seasons.compareDocumentPosition(related) & Node.DOCUMENT_POSITION_FOLLOWING) : null;
      });
      assert(order === true, 'related is not after the seasons');
      await rowShot('bb-more', '#related-more');
      return `${more.tiles.length} tiles`;
    });

    if (t.mobile) {
      await t.step('phone: rows scroll sideways inside, not the page', async () => {
        await open('/movie/863');
        await loadRows();
        await page.evaluate(() => document.querySelector('#related-more .home-row-track').scrollBy({ left: 300, behavior: 'instant' }));
        await t.waitFor(() => document.querySelector('#related-more .home-row-track').scrollLeft > 0);
        const res = await page.evaluate(() => {
          const track = document.querySelector('#related-more .home-row-track');
          return { scrolled: Math.round(track.scrollLeft), page: document.documentElement.scrollWidth - window.innerWidth };
        });
        assert(res.page <= 0, `page overflows by ${res.page}px`);
        await rowShot('ts2-more-scrolled', '#related-more');
        return `track scrollLeft ${res.scrolled}, page overflow ${res.page}px`;
      });
    }
  },
};
