// Back links on title pages: the origin label and link (Home, Discover, Search results, a list's name...), the one-step
// "previous title" link, scroll / row position restored, reload and browser Back, the server's pending state, no-JS Referer
// links, long names. Ported from the scratchpad's backnav/run_backnav.js; its "Best Disney Movies" list is seeded under the
// run's own name, and tiles are scrolled into view instead of being picked inside a fixed band of the screen.
const { setTitle, createList } = require('../lib/api');

const DISNEY = [862, 863, 10193, 354912, 585, 14160, 12, 8587, 109445, 277834, 2062, 10681, 150540, 269149, 812, 10020, 10674, 38757, 9806, 568124];
const UNSEEN = [863, 585, 2062, 10020, 10674, 38757]; // cleared before each viewport so the "Not seen" tab has titles

module.exports = {
  name: 'backnav',
  description: 'title page back links: origin labels, previous-title step, scroll restore, reload, Back, no JS',

  async setup(env) {
    const listName = `${env.prefix} disney`;
    return { listName, listId: await createList(env.api, listName, DISNEY.map((id) => ['movie', id])) };
  },

  async reset(env) {
    for (const id of UNSEEN) await setTitle(env.api, 'movie', id, { score: null, status: null });
  },

  async run(t) {
    const { page, assert, mobile } = t;
    const { listId, listName } = t.data;

    const navType = (p = page) => p.evaluate(() => performance.getEntriesByType('navigation')[0].type);
    const backInfo = (p = page) => p.evaluate(() => {
      const nav = document.querySelector('[data-back-nav]');
      if (!nav) return null;
      const origin = nav.querySelector('[data-back-origin]');
      const step = nav.querySelector('[data-back-step]');
      const prev = nav.querySelector('[data-back-prev]');
      const r = nav.getBoundingClientRect();
      const text = origin.querySelector('[data-back-text]');
      return {
        label: text.textContent.trim(), href: origin.getAttribute('href'),
        step: !!step && !step.hidden && getComputedStyle(step).display !== 'none',
        stepLabel: prev ? prev.textContent.trim() : '', pending: nav.hasAttribute('data-pending'),
        visibility: getComputedStyle(nav).visibility, navHeight: Math.round(r.height), linkHeight: Math.round(origin.getBoundingClientRect().height),
        state: history.state && history.state.backNav ? { direct: history.state.backNav.direct, hasOrigin: !!history.state.backNav.origin } : null,
      };
    });
    const fold = (p = page) => p.evaluate(() => {
      const actions = document.querySelector('.title-actions');
      const bottomNav = document.querySelector('.bottom-nav');
      const navTop = bottomNav && getComputedStyle(bottomNav).display !== 'none' ? bottomNav.getBoundingClientRect().top : window.innerHeight;
      const a = actions ? actions.getBoundingClientRect() : null;
      return { actionsBottom: a ? Math.round(a.bottom + window.scrollY) : null, visibleBottom: Math.round(navTop), fits: a ? a.bottom + window.scrollY <= navTop : null };
    });
    /** A link matching `selector` that is fully on screen; if none is, the first one, scrolled to the middle of the screen. */
    const onScreen = async (selector, p = page) => {
      const h = await p.evaluateHandle((sel) => {
        const vh = window.innerHeight;
        const links = [...document.querySelectorAll(sel)].filter((a) => a.getBoundingClientRect().width > 20);
        const inView = links.find((a) => { const r = a.getBoundingClientRect(); return r.top > 90 && r.bottom < vh - 90 && r.left >= 0 && r.right <= window.innerWidth; });
        if (inView) return inView;
        const first = links.find((a) => a.getBoundingClientRect().top + window.scrollY >= window.scrollY) || links[0];
        if (first) first.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' });
        return first || null;
      }, selector);
      const el = h.asElement();
      assert(el, `nothing matches ${selector}`);
      return el;
    };
    // The "More like this" row loads lazily at the bottom of a title page; returns a tile in the last related row.
    const relatedTile = async (index = 0, p = page) => {
      await p.evaluate(() => window.scrollTo({ top: document.body.scrollHeight, behavior: 'instant' }));
      await p.waitForFunction(() => document.querySelectorAll('.related-row a.home-tile').length > 0, { timeout: 45000 });
      const h = await p.evaluateHandle((i) => {
        const rows = [...document.querySelectorAll('.related-row')].filter((r) => r.querySelector('a.home-tile'));
        const row = rows[rows.length - 1];
        row.scrollIntoView({ block: 'center', behavior: 'instant' });
        const tiles = [...row.querySelectorAll('a.home-tile')];
        return tiles[Math.min(i, tiles.length - 1)];
      }, index);
      return h.asElement();
    };
    const titleName = (p = page) => p.evaluate(() => document.querySelector('.title-head h1 span').textContent.trim());
    /** A row of the Discover landing with more than 6 tiles, scrolled sideways by `x`; returns its id and real scrollLeft. */
    const scrollRow = (x) => page.evaluate(async (dx) => {
      const rows = [...document.querySelectorAll('.discover-row:not(.is-pending)')].filter((r) => r.querySelectorAll('a.home-tile').length > 6);
      const row = rows[1] || rows[0];
      if (!row) return null;
      row.scrollIntoView({ block: 'center', behavior: 'instant' });
      const track = row.querySelector('.home-row-track');
      track.style.scrollBehavior = 'auto';
      track.scrollLeft = dx;
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
      return { id: row.id, x: Math.round(track.scrollLeft), y: Math.round(window.scrollY) };
    }, x);
    const tileInRow = async (id) => (await page.evaluateHandle((rid) => {
      const track = document.querySelector('#' + CSS.escape(rid) + ' .home-row-track');
      const tr = track.getBoundingClientRect();
      return [...track.querySelectorAll('a.home-tile')].find((a) => { const r = a.getBoundingClientRect(); return r.left >= tr.left && r.right <= tr.right; });
    }, id)).asElement();
    const waitDiscoverRows = () => t.waitFor(() => document.querySelectorAll('.discover-row:not(.is-pending) a.home-tile').length > 12, null, 45000);

    await t.step('login', () => t.login());

    await t.step('direct open -> Home', async () => {
      const tab = await t.newTab();
      try {
        await t.go('/movie/10193', tab);
        const b = await backInfo(tab);
        assert(b && b.label === 'Home' && b.href === '/', 'expected Home: ' + JSON.stringify(b));
        assert(b.state && !b.state.hasOrigin, 'no origin expected');
        assert(!b.step, 'no step expected');
        assert(b.linkHeight >= 44, 'tap target ' + b.linkHeight);
        const f = await fold(tab);
        if (mobile) assert(f.fits, 'action row below the fold: ' + JSON.stringify(f));
        await t.shot('movie-direct-fold', false, tab);
        await t.noOverflow(tab);
        await t.navClick('[data-back-origin]', tab);
        assert(new URL(tab.url()).pathname === '/', 'went to ' + tab.url());
        return `label ${b.label}, link ${b.linkHeight}px; actions end ${f.actionsBottom} of ${f.visibleBottom}`;
      } finally { await tab.close(); }
    });

    await t.step('direct open TV -> Home, fold', async () => {
      const tab = await t.newTab();
      try {
        await t.go('/tv/1396', tab);
        const b = await backInfo(tab);
        assert(b && b.label === 'Home', JSON.stringify(b));
        const f = await fold(tab);
        if (mobile) assert(f.fits, 'action row below the fold: ' + JSON.stringify(f));
        await t.shot('tv-direct-fold', false, tab);
        await t.noOverflow(tab);
        return `actions end ${f.actionsBottom} of ${f.visibleBottom}`;
      } finally { await tab.close(); }
    });

    let gridUrl = null;
    await t.step('discover grid -> title -> back (same URL and scroll)', async () => {
      await t.go('/discover?type=movie');
      gridUrl = await page.evaluate(() => [...document.querySelectorAll('.discover-row .home-row-more')].map((a) => a.getAttribute('href')).find((h) => /genre=/.test(h)));
      assert(gridUrl, 'no genre More link');
      await t.go(gridUrl);
      await page.evaluate((y) => window.scrollTo({ top: y, behavior: 'instant' }), mobile ? 1600 : 1100);
      const tile = await onScreen('a.browse-tile-link');
      const y0 = await page.evaluate(() => Math.round(window.scrollY));
      await t.shot('grid-scrolled');
      await t.navClick(tile);
      const b = await backInfo();
      assert(b.label === 'Discover', 'label ' + b.label);
      assert(b.href === t.base + gridUrl || b.href === gridUrl, 'href ' + b.href + ' vs ' + gridUrl);
      assert(b.state && b.state.direct === true, 'expected direct: ' + JSON.stringify(b.state));
      await t.shot('title-from-grid');
      if (mobile) { const f = await fold(); assert(f.fits, 'action row below the fold: ' + JSON.stringify(f)); }
      await t.navClick('[data-back-origin]');
      await t.waitFor((y) => Math.abs(window.scrollY - y) < 120, y0, 15000).catch(() => {});
      const after = await page.evaluate(() => ({ url: location.pathname + location.search, y: Math.round(window.scrollY) }));
      assert(after.url === gridUrl, 'came back to ' + after.url);
      assert(Math.abs(after.y - y0) < 120, `scroll ${after.y} vs ${y0}`);
      return `${gridUrl} y ${y0} -> ${after.y} via ${await navType()}`;
    });

    await t.step('discover -> title -> related -> related -> back to Discover', async () => {
      await t.go('/discover');
      await waitDiscoverRows();
      const picked = await scrollRow(520);
      assert(picked, 'no Discover row with more than 6 tiles');
      const tile = await tileInRow(picked.id);
      assert(tile, 'no tile fully inside the row');
      await t.shot('landing-row-scrolled');
      await t.navClick(tile);
      const t1 = await titleName();
      let b = await backInfo();
      assert(b.label === 'Discover' && !b.step, 'title 1: ' + JSON.stringify(b));
      await t.navClick(await relatedTile(0));
      const t2 = await titleName();
      b = await backInfo();
      assert(b.label === 'Discover', 'title 2 label ' + b.label);
      assert(b.step && b.stepLabel === t1, `title 2 step "${b.stepLabel}" vs "${t1}"`);
      assert(!b.pending && b.visibility === 'visible', 'pending left on');
      await t.shot('title2-step');
      if (mobile) { const f = await fold(); assert(f.fits, 'title 2 action row below the fold: ' + JSON.stringify(f)); }
      await t.noOverflow();
      await t.navClick(await relatedTile(1));
      const t3 = await titleName();
      b = await backInfo();
      assert(b.label === 'Discover', 'title 3 label ' + b.label);
      assert(b.step && b.stepLabel === t2, `title 3 step "${b.stepLabel}" vs "${t2}"`);
      assert(b.linkHeight >= 44 && b.navHeight <= 46, `heights link ${b.linkHeight} nav ${b.navHeight}`);
      await t.shot('title3-step');
      // One step back: the title before (history.back), which still shows its own links.
      await t.navClick('[data-back-prev]');
      const back2 = await titleName();
      b = await backInfo();
      assert(back2 === t2, `prev went to "${back2}" not "${t2}"`);
      assert(b.label === 'Discover' && b.stepLabel === t1, 'title 2 again: ' + JSON.stringify(b));
      // And the origin from there: Discover, not title 1, with the row's sideways scroll back.
      await t.navClick('[data-back-origin]');
      await t.waitFor((p) => { const tr = document.querySelector('#' + CSS.escape(p.id) + ' .home-row-track'); return tr && Math.abs(tr.scrollLeft - p.x) < 40; }, picked, 20000).catch(() => {});
      const after = await page.evaluate((id) => {
        const tr = document.querySelector('#' + CSS.escape(id) + ' .home-row-track');
        return { url: location.pathname + location.search, y: Math.round(window.scrollY), x: tr ? Math.round(tr.scrollLeft) : null };
      }, picked.id);
      assert(after.url === '/discover', 'came back to ' + after.url);
      assert(Math.abs(after.y - picked.y) < 120, `scroll ${after.y} vs ${picked.y}`);
      assert(after.x !== null && Math.abs(after.x - picked.x) < 40, `row x ${after.x} vs ${picked.x}`);
      await t.shot('discover-after-chain');
      return `"${t1}" -> "${t2}" -> "${t3}"; prev -> "${back2}"; origin -> /discover, row x ${picked.x} -> ${after.x}`;
    });

    await t.step('reload keeps the origin', async () => {
      await t.go('/discover');
      await waitDiscoverRows();
      await t.navClick(await onScreen('.discover-row:not(.is-pending) a.home-tile'));
      await t.navClick(await relatedTile(0));
      await page.reload({ waitUntil: 'networkidle2' });
      const b = await backInfo();
      assert(b.label === 'Discover' && b.step, 'after reload: ' + JSON.stringify(b));
      return `label ${b.label} + step ${b.stepLabel}`;
    });

    await t.step('browser Back restores the row', async () => {
      await t.go('/discover');
      await waitDiscoverRows();
      const picked = await scrollRow(400);
      assert(picked, 'no Discover row with more than 6 tiles');
      await t.navClick(await tileInRow(picked.id));
      await page.goBack({ waitUntil: 'networkidle2' });
      await t.waitFor((p) => { const tr = document.querySelector('#' + CSS.escape(p.id) + ' .home-row-track'); return tr && Math.abs(tr.scrollLeft - p.x) < 40; }, picked, 20000).catch(() => {});
      const x = await page.evaluate((id) => Math.round(document.querySelector('#' + CSS.escape(id) + ' .home-row-track').scrollLeft), picked.id);
      assert(Math.abs(x - picked.x) < 40, `row x ${x} vs ${picked.x}`);
      return `row ${picked.id} x ${picked.x} -> ${x} via ${await navType()}`;
    });

    await t.step('search -> title -> Search results', async () => {
      await t.go('/');
      await page.click('#navSearchInput');
      await page.type('#navSearchInput', 'toy story');
      await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle2' }), page.keyboard.press('Enter')]);
      const searchUrl = await page.evaluate(() => location.pathname + location.search);
      await t.navClick(await onScreen('a.search-tile'));
      const b = await backInfo();
      assert(b.label === 'Search results', 'label ' + b.label);
      assert(b.href.endsWith(searchUrl), `href ${b.href} vs ${searchUrl}`);
      await t.shot('title-from-search');
      await t.navClick('[data-back-origin]');
      const url = await page.evaluate(() => location.pathname + location.search);
      assert(url === searchUrl, 'came back to ' + url);
      return `${searchUrl} -> back ${url} via ${await navType()}`;
    });

    await t.step('list -> title -> list name (tab and scroll kept)', async () => {
      await t.go(`/list/${listId}`);
      const tab = await page.$('.list-filter-tabs a.status-segment[href*="show=unseen"]');
      assert(tab, 'no Not seen tab');
      await tab.click();
      await t.waitFor(() => location.search.includes('show=unseen') && /not seen/i.test(document.querySelector('.list-filter-tabs .active')?.innerText || ''));
      await page.waitForNetworkIdle({ idleTime: 300, timeout: 20000 }).catch(() => {});
      const listUrl = await page.evaluate(() => location.pathname + location.search);
      await page.evaluate(() => window.scrollTo({ top: Math.max(0, Math.min(700, document.body.scrollHeight - window.innerHeight)), behavior: 'instant' }));
      const card = await onScreen('a.list-card');
      const y0 = await page.evaluate(() => Math.round(window.scrollY));
      await t.navClick(card);
      const b = await backInfo();
      assert(b.label === listName, `label "${b.label}" vs "${listName}"`);
      assert(b.href.endsWith(listUrl), `href ${b.href} vs ${listUrl}`);
      await t.shot('title-from-list');
      await t.navClick('[data-back-origin]');
      await t.waitFor((y) => Math.abs(window.scrollY - y) < 120, y0, 15000).catch(() => {});
      const after = await page.evaluate(() => ({ url: location.pathname + location.search, y: Math.round(window.scrollY), tab: document.querySelector('.list-filter-tabs .status-segment.active')?.textContent.trim() }));
      assert(after.url === listUrl, 'came back to ' + after.url);
      assert(Math.abs(after.y - y0) < 120, `scroll ${after.y} vs ${y0}`);
      await t.shot('list-after-back');
      return `${listUrl} y ${y0} -> ${after.y}, tab "${after.tab}"`;
    });

    await t.step('other origins name themselves', async () => {
      const seen = [];
      const label = () => page.evaluate(() => document.querySelector('script[data-back-nav-page]')?.dataset.label);
      await t.go('/');
      assert((await label()) === 'Home', `/: ${await label()}`);
      await t.navClick(await onScreen('.home-row a.home-tile'));
      assert((await backInfo()).label === 'Home', 'home -> title label');
      seen.push('/=Home');
      for (const [url, expected] of [['/lists/official', 'Official lists'], ['/watchlist', 'Watchlist'], ['/movies', 'Ratings'], ['/lists', 'Lists'],
        ['/community', 'Community'], ['/profile', 'Your profile'], [`/list/${listId}`, listName]]) {
        await t.go(url);
        const l = await label();
        assert(l === expected, `${url}: "${l}" (expected "${expected}")`);
        seen.push(`${url}=${l}`);
      }
      await t.go('/settings/api');
      assert(!(await label()), 'settings has a label');
      return seen.join(', ');
    });

    await t.step('title from title is pending on the server', async () => {
      await t.go('/movie/863');
      const html = await page.evaluate(async () => (await fetch('/movie/10193', { referrer: location.origin + '/movie/863', credentials: 'same-origin' })).text());
      assert(/data-back-nav[^>]*data-pending="true"/.test(html), 'not pending');
      const plain = await page.evaluate(async () => (await fetch('/movie/10193', { referrer: location.origin + '/discover?type=movie', credentials: 'same-origin' })).text());
      assert(!/data-pending/.test(plain) && /href="\/discover\?type=movie"/.test(plain), 'Referer link missing');
      return 'pending from a title; /discover?type=movie from Discover';
    });

    await t.step('no JS: plain link from the Referer', async () => {
      const tab = await t.newTab(false);
      try {
        const grid = gridUrl || '/discover?type=movie';
        await t.go(grid, tab);
        await t.navClick('a.browse-tile-link', tab);
        const b = await backInfo(tab);
        assert(b.label === 'Discover', 'label ' + b.label);
        assert(b.href === grid, 'href ' + b.href);
        assert(!b.step, 'step visible without JS');
        assert(b.visibility === 'visible', 'hidden without JS');
        await t.shot('nojs-title', false, tab);
        await t.navClick('[data-back-origin]', tab);
        const url = await tab.evaluate(() => location.pathname + location.search);
        assert(url === grid, 'went to ' + url);
        return `${b.label} -> ${url}`;
      } finally { await tab.close(); }
    });

    await t.step('long names stay on one line', async () => {
      await t.go(`/list/${listId}`);
      await t.navClick(await onScreen('a.list-card'));
      await t.navClick(await relatedTile(0));
      const forced = await page.evaluate(() => {
        const nav = document.querySelector('[data-back-nav]');
        nav.querySelector('[data-back-origin] [data-back-text]').textContent = 'A Very Long List Name For Testing Truncation Of Back Links';
        nav.querySelector('[data-back-prev] [data-back-text]').textContent = 'The Lord of the Rings: The Fellowship of the Ring';
        const r = nav.getBoundingClientRect();
        return { height: Math.round(r.height), width: Math.round(r.width), vw: window.innerWidth, overflow: document.documentElement.scrollWidth - window.innerWidth };
      });
      assert(forced.height <= 46, 'wrapped: ' + forced.height);
      assert(forced.overflow <= 0, 'overflow ' + forced.overflow);
      await t.shot('long-names');
      return `nav ${forced.width}x${forced.height} in ${forced.vw}`;
    });
  },
};
