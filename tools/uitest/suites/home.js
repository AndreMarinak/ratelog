// Main walkthrough: home rows, poster sharpness, bookmark, header search, Discover, title page actions, add / remove from a
// list in place, official lists, Lists tab, list page tabs, mobile nav, Ratings tab, tags hidden.
// Ported from the scratchpad's uitest/run_home.js; every list it needs is seeded, nothing is looked up by a fixed id.
const { setTitle, createList } = require('../lib/api');

const TOY_STORY = 862;
const INSIDE_OUT = 150540;
const WALL_E = 10681;
const LION_KING = 8587;

module.exports = {
  name: 'home',
  description: 'home rows, bookmark, search, Discover, title page, add/remove list, official lists, nav, Ratings',

  async setup(env) {
    const items = [['movie', TOY_STORY], ['movie', INSIDE_OUT], ['movie', WALL_E]];
    return {
      progressList: await createList(env.api, `${env.prefix} home progress`, items),
      officialList: await createList(env.api, `${env.prefix} home official`, items, { isPublic: true }),
    };
  },

  async reset(env) {
    // Toy Story rated (seen); Inside Out and WALL-E unseen: the progress list reads "You've seen 1 of 3".
    await setTitle(env.api, 'movie', TOY_STORY, { score: 8.5, status: 'watched' });
    for (const id of [INSIDE_OUT, WALL_E]) await setTitle(env.api, 'movie', id, { score: null, status: null });
  },

  async run(t) {
    const { page, assert, mobile } = t;
    const { progressList, officialList } = t.data;

    await t.step('login', () => t.login());

    await t.step('home dashboard', async () => {
      await t.go('/');
      const info = await page.evaluate(() => ({
        rows: [...document.querySelectorAll('.home-row')].map((r) => `${(r.querySelector('h2,h3')?.innerText || '?').trim()}:${r.querySelectorAll('.home-tile').length}`),
        browseBar: !!document.querySelector('.bb-main, .bb-menus'),
        wordmark: !!document.querySelector('.nav-brand a[href="/"], a[href="/"] .nav-wordmark'),
        search: !!document.getElementById('navSearchInput'),
      }));
      await t.shot('home', true);
      await t.shot('home-fold');
      assert(info.rows.length >= 4, `only ${info.rows.length} rows`);
      assert(info.browseBar, 'browse bar missing');
      assert(info.search, 'header search missing');
      assert(mobile || info.wordmark, 'wordmark link missing');
      await t.noOverflow();
      return `${info.rows.length} rows: ${info.rows.slice(0, 5).join(', ')}...`;
    });

    await t.step('home poster sharpness', async () => {
      const imgs = await page.evaluate(() => [...document.querySelectorAll('.home-poster img')].slice(0, 6).map((i) => ({
        src: i.currentSrc.replace(/^.*\/t\/p\//, ''), natural: i.naturalWidth, shown: Math.round(i.getBoundingClientRect().width),
      })));
      // naturalWidth is density-corrected for srcset w-descriptors, so read the TMDB file width from the URL instead.
      const fileWidth = (src) => { const m = /^w(\d+)\//.exec(src); return m ? Number(m[1]) : Infinity; };
      const blurry = imgs.filter((i) => fileWidth(i.src) < i.shown * (t.vp.deviceScaleFactor || 1));
      assert(imgs.length > 0, 'no home posters');
      assert(blurry.length === 0, `undersized images ${JSON.stringify(blurry)}`);
      return imgs.slice(0, 2).map((i) => `${i.src.split('/')[0]} for ${i.shown}px`).join('; ');
    });

    await t.step('row scrolls sideways', async () => {
      const ok = await page.evaluate(() => {
        const tr = [...document.querySelectorAll('.home-row-track')].find((x) => x.scrollWidth > x.clientWidth);
        if (!tr) return false;
        tr.id = 'uitest-track';
        tr.scrollBy({ left: 400 });
        return true;
      });
      assert(ok, 'no row wider than the screen');
      await t.waitFor(() => document.getElementById('uitest-track').scrollLeft > 0);
      return `scrolled ${await page.$eval('#uitest-track', (x) => Math.round(x.scrollLeft))}px`;
    });

    await t.step('bookmark adds to watchlist', async () => {
      await t.go('/');
      const title = await page.evaluate(() => {
        const f = [...document.querySelectorAll('form.tile-watchlist:not([hidden])')].find((x) => !x.classList.contains('is-on'));
        if (!f) return null;
        f.id = 'uitest-bookmark';
        return f.closest('.home-tile-wrap').querySelector('.home-tile-title').innerText;
      });
      assert(title, 'no bookmark button');
      // Saves in place: no reload (the marker survives), the button flips, the scroll position stays.
      await page.evaluate(() => window.scrollTo({ top: 400, behavior: 'instant' }));
      const token = await t.mark();
      await page.evaluate(() => document.querySelector('#uitest-bookmark button').click());
      await t.waitFor(() => document.querySelector('#uitest-bookmark.is-on'));
      await t.stillHere(token);
      const y = await page.evaluate(() => window.scrollY);
      assert(y >= 300, `scroll jumped to ${y}`);
      await t.go('/watchlist');
      const inList = await page.evaluate((x) => document.body.innerText.includes(x), title);
      await t.shot('watchlist-after-bookmark');
      assert(inList, `${title} not on /watchlist`);
      // Undo: the run's restore would also put it back, but the step leaves the page as it found it.
      await t.go('/');
      const undone = await page.evaluate((x) => {
        const w = [...document.querySelectorAll('.home-tile-wrap')].find((e) => e.querySelector('.home-tile-title')?.innerText === x && e.querySelector('form.tile-watchlist.is-on'));
        if (!w) return false;
        w.querySelector('form.tile-watchlist').id = 'uitest-unbookmark';
        w.querySelector('form.tile-watchlist button').click();
        return true;
      }, title);
      if (undone) await t.waitFor(() => !document.querySelector('#uitest-unbookmark.is-on'));
      return `${title}; no reload, scroll kept, on /watchlist, undone`;
    });

    await t.step('header search ("/" shortcut)', async () => {
      await t.go('/movies');
      if (mobile) await page.click('#navSearchInput').catch(() => {});
      else await page.keyboard.press('/');
      await t.waitFor(() => document.activeElement?.id === 'navSearchInput', null, 5000).catch(() => {});
      const focused = await page.evaluate(() => document.activeElement?.id);
      await page.keyboard.type('hunger games');
      await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle2' }), page.keyboard.press('Enter')]);
      const results = await page.evaluate(() => document.querySelectorAll('.result-card, .home-tile').length);
      await t.shot('search-results', true);
      assert(focused === 'navSearchInput', `focus on ${focused}`);
      assert(results > 0, 'no results');
      return `${results} results at ${page.url().replace(t.base, '')}`;
    });

    await t.step('discover landing: genre rows + hide seen', async () => {
      await t.go('/discover');
      // Rows below the fold load as they scroll into view.
      await page.evaluate(async () => {
        for (let y = 0; y < document.body.scrollHeight; y += 600) { window.scrollTo(0, y); await new Promise((r) => setTimeout(r, 100)); }
        window.scrollTo(0, 0);
      });
      await t.waitFor(() => !document.querySelector('.discover-row.is-pending'), null, 45000).catch(() => {});
      const d = await page.evaluate(() => ({
        rows: [...document.querySelectorAll('.discover-row')].map((r) => `${(r.querySelector('h2,h3')?.innerText || '?').trim()}:${r.querySelectorAll('.home-tile').length}`),
        toggle: !!document.querySelector('a[href*="hideSeen"], input[name="hideSeen"], [data-hide-seen]'),
        h1: document.querySelector('h1')?.innerText.trim(),
      }));
      await t.shot('discover', true);
      assert(d.rows.length >= 6, `rows ${d.rows.join(', ')}`);
      assert(d.toggle, 'no hide-seen toggle');
      await t.noOverflow();
      return `${d.h1}: ${d.rows.slice(0, 4).join(', ')}... (${d.rows.length} rows)`;
    });

    await t.step('discover genre rows span the years; home has classics', async () => {
      await t.go('/discover?hideSeen=0');
      await t.waitFor(() => [...document.querySelectorAll('.discover-row')].some((r) => /horror/i.test(r.querySelector('h2,h3')?.innerText || '') && r.querySelectorAll('.home-tile-meta').length >= 10), null, 45000).catch(() => {});
      const years = await page.evaluate(() => {
        const row = [...document.querySelectorAll('.discover-row')].find((r) => /horror/i.test(r.querySelector('h2,h3')?.innerText || ''));
        return row ? [...row.querySelectorAll('.home-tile-meta')].map((m) => parseInt(m.innerText, 10)).filter((y) => y > 1900) : [];
      });
      const old = years.filter((y) => y < 2015).length;
      assert(years.length >= 10 && old >= years.length * 0.4, `horror years ${years.join(',')}`);
      await t.go('/');
      const classics = await page.evaluate(() => {
        const row = [...document.querySelectorAll('.home-row')].find((r) => /classics/i.test(r.querySelector('h2,h3')?.innerText || ''));
        return row ? [...row.querySelectorAll('.home-tile')].map((x) => `${x.querySelector('.home-tile-title')?.innerText} ${x.querySelector('.home-tile-meta')?.innerText}`) : null;
      });
      assert(classics && classics.length >= 10, `classics row ${classics}`);
      assert(classics.every((c) => parseInt(c.split(' ').pop(), 10) < 2000), `classics not all pre-2000: ${classics.join(', ')}`);
      return `horror: ${old}/${years.length} before 2015; classics: ${classics.slice(0, 3).join(', ')}...`;
    });

    await t.step('discover grid + load more, /browse redirect', async () => {
      await t.go('/browse?type=movie&genre=27');
      const url = page.url().replace(t.base, '');
      assert(url.startsWith('/discover?') && url.includes('genre=27'), `redirected to ${url}`);
      const before = await page.evaluate(() => document.querySelectorAll('.browse-tile').length);
      const more = await page.$('.browse-load-more, .browse-more a, .browse-more button');
      let after = before;
      if (more) {
        await more.click();
        await t.waitFor((n) => document.querySelectorAll('.browse-tile').length > n, before, 30000);
        after = await page.evaluate(() => document.querySelectorAll('.browse-tile').length);
      }
      const chips = await page.evaluate(() => [...document.querySelectorAll('.bb-chip')].map((c) => c.innerText.trim()));
      await t.shot('discover-horror');
      assert(before > 0, 'no tiles');
      return `${url}; ${before} -> ${after} tiles; chips ${chips.join('|')}`;
    });

    await t.step('title page: actions above the fold, rate modal', async () => {
      await t.go(`/movie/${LION_KING}`);
      const a = await page.evaluate(() => {
        const acts = [...document.querySelectorAll('.title-action, .title-panel .status-segment')].filter((x) => x.offsetParent);
        return { n: acts.length, labels: acts.map((x) => x.innerText.replace(/\s+/g, ' ').trim()).slice(0, 6),
          maxBottom: Math.max(...acts.map((x) => x.getBoundingClientRect().bottom)), vh: innerHeight };
      });
      await t.shot('title-lionking');
      assert(a.n >= 4, `only ${a.n} actions`);
      assert(a.maxBottom <= a.vh, `actions end at ${Math.round(a.maxBottom)} of ${a.vh}`);
      await t.noOverflow();
      const opened = await page.evaluate(() => {
        const b = [...document.querySelectorAll('[data-open-modal="rateMovieModal"]')].find((x) => x.offsetParent);
        if (b) b.click();
        return !!b;
      });
      assert(opened, 'no rate button');
      await t.waitFor(() => document.querySelector('#rateMovieModal.visible, #rateMovieModal[open]'));
      await t.shot('title-rate-modal');
      return `${a.n} actions (${a.labels.join(' | ')}), bottom ${Math.round(a.maxBottom)}/${a.vh}`;
    });

    const walkList = `${t.data.prefix} walk ${t.vpName}`;
    let walkCreated = false;
    await t.step('add to list in place (title page)', async () => {
      await t.go(`/movie/${LION_KING}`);
      const token = await t.mark();
      const openModal = async () => {
        await page.evaluate(() => [...document.querySelectorAll('[data-open-modal="addToListModal"]')].find((b) => b.offsetParent)?.click());
        await t.waitFor(() => document.querySelector('#addToListModal.visible'));
      };
      await openModal();
      if (!walkCreated) {
        await page.type('#newListName', walkList);
        await page.evaluate(() => document.querySelector('form.list-create-inline [type=submit]').click());
        await t.waitFor(() => document.querySelector('.inplace-toast.visible'));
        walkCreated = true;
        const a = await page.evaluate(() => ({ path: location.pathname, open: !!document.querySelector('#addToListModal.visible') }));
        assert(a.path === `/movie/8587`, `left the page: ${a.path}`);
        assert(!a.open, 'modal stayed open');
        await t.stillHere(token);
        await t.waitFor(() => !document.querySelector('.inplace-toast.visible'), null, 15000).catch(() => {});
        await openModal();
      }
      // The new list is ticked; tapping it removes it (after a confirm), tapping again adds it back.
      // State of the sheet row for our list: true (in), false (out), null (row missing). Passed by name, no eval (CSP).
      const rowState = (n) => {
        const f = [...document.querySelectorAll('#addToListModal form.list-selection-item')].find((x) => x.querySelector('.list-selection-name')?.innerText.trim() === n);
        return f ? f.querySelector('.list-member-toggle').classList.contains('is-in') : null;
      };
      const state = () => page.evaluate(rowState, walkList);
      const tap = () => page.evaluate((n) => [...document.querySelectorAll('#addToListModal form.list-selection-item')]
        .find((x) => x.querySelector('.list-selection-name')?.innerText.trim() === n).querySelector('[type=submit]').click(), walkList);
      const s0 = await state();
      assert(s0 === true, `new list not ticked in the sheet (${s0})`);
      await tap();
      const waitState = (want) => t.waitFor(([n, w]) => {
        const f = [...document.querySelectorAll('#addToListModal form.list-selection-item')].find((x) => x.querySelector('.list-selection-name')?.innerText.trim() === n);
        return f && f.querySelector('.list-member-toggle').classList.contains('is-in') === w;
      }, [walkList, want]);
      await waitState(false);
      await tap();
      await waitState(true);
      const b = await page.evaluate(() => ({ open: !!document.querySelector('#addToListModal.visible'), button: document.querySelector('[data-list-button] .title-toggle-label')?.innerText.trim() }));
      await t.shot('add-to-list');
      await t.stillHere(token);
      assert(b.open, 'sheet closed after a tick');
      assert(/^In \d+ lists?$/.test(b.button || ''), `title button "${b.button}"`);
      return `created "${walkList}"; untick + tick in place, sheet open, button "${b.button}"`;
    });

    await t.step('remove from list in place (list page)', async () => {
      assert(walkCreated, 'no list from the previous step');
      await t.go('/lists');
      const href = await page.evaluate((n) => [...document.querySelectorAll('a[href^="/list/"]')].find((a) => a.innerText.includes(n))?.getAttribute('href'), walkList);
      assert(href, 'created list not on /lists');
      await t.go(href);
      const token = await t.mark();
      const before = await page.evaluate(() => document.querySelectorAll('.list-grid .list-card').length);
      assert(before > 0, 'created list is empty');
      await page.evaluate(() => document.querySelector('.list-grid form[action$="/remove"] [type=submit]').click());
      await t.waitFor((n) => document.querySelectorAll('.list-grid .list-card').length < n, before);
      const after = await page.evaluate(() => document.querySelectorAll('.list-grid .list-card').length);
      await t.stillHere(token);
      assert(page.url().replace(t.base, '').split('?')[0] === href.split('?')[0], 'navigated');
      // Delete the throwaway list through its own form (the run's cleanup deletes it too if this fails).
      const del = await page.$('form[action$="/delete"]');
      if (del) await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle2' }), del.evaluate((f) => f.submit())]);
      return `${before} -> ${after} items, no reload`;
    }, { retry: false });

    await t.step('official lists: mark in place, Lists tab section, Discover row, unmark', async () => {
      const href = `/list/${officialList}`;
      await t.go(href);
      const badge = () => page.evaluate(() => !!document.querySelector('.list-official-badge'));
      const toggle = async () => {
        const was = await badge();
        const token = await t.mark();
        await page.evaluate(() => document.querySelector('form.list-official-toggle [type=submit]').click());
        await t.waitFor((w) => !!document.querySelector('.list-official-badge') !== w, was);
        await t.stillHere(token);
        return badge();
      };
      if (await badge()) await toggle(); // a retry may find it still marked
      assert(await toggle(), 'mark did not show the badge');
      await t.go('/lists');
      const section = await page.evaluate((h) => { const row = document.querySelector('.official-row'); return { row: !!row, has: !!row && !!row.querySelector(`a[href="${h}"]`) }; }, href);
      await t.shot('lists-official');
      assert(section.row && section.has, `Lists tab official section ${JSON.stringify(section)}`);
      await t.go('/discover');
      const disc = await page.evaluate((h) => !!document.querySelector(`.official-row a[href="${h}"]`), href);
      assert(disc, 'not in the Discover official row');
      await t.go(href);
      assert(!(await toggle()), 'unmark left the badge');
      return `${href}: marked in place, top of Lists, on Discover, unmarked in place`;
    });

    await t.step('lists tab', async () => {
      await t.go('/lists');
      const txt = await page.evaluate(() => document.querySelector('main, .page')?.innerText.slice(0, 200).replace(/\s+/g, ' '));
      const mine = await page.evaluate((id) => !!document.querySelector(`a[href="/list/${id}"]`), progressList);
      await t.shot('lists', true);
      await t.noOverflow();
      assert(mine, 'seeded list not on /lists');
      return txt.slice(0, 80);
    });

    await t.step('list page: back link, progress, seen filter', async () => {
      await t.go('/lists');
      await t.go(`/list/${progressList}`);
      const a = await page.evaluate(() => ({
        back: document.querySelector('.list-back')?.getAttribute('href'),
        backText: document.querySelector('.list-back')?.innerText.trim(),
        progress: document.querySelector('.list-progress-text, .list-progress-line')?.innerText.trim(),
        tabs: [...document.querySelectorAll('.list-filter-tabs a, .list-filter-tabs .status-segment')].map((x) => x.innerText.replace(/\s+/g, ' ').trim()),
      }));
      await t.shot('list-detail');
      assert(a.back === '/lists', `back link ${a.back}`);
      assert(/seen 1 of 3/.test(a.progress || ''), `progress "${a.progress}" (expected 1 of 3: Toy Story rated, two unseen)`);
      assert(a.tabs.length >= 3, `tabs ${a.tabs}`);
      await t.noOverflow();
      // Tabs switch in place: no reload, URL and active tab change, Back restores.
      const token = await t.mark();
      await page.evaluate(() => [...document.querySelectorAll('.list-filter-tabs a.status-segment')].find((x) => /show=unseen/.test(x.href))?.click());
      await t.waitFor(() => location.search.includes('show=unseen') && /not seen/i.test(document.querySelector('.list-filter-tabs .active, .list-filter-tabs [aria-current]')?.innerText || ''));
      const unseen = await page.evaluate(() => document.querySelector('.list-filter-tabs .active, .list-filter-tabs [aria-current]')?.innerText.replace(/\s+/g, ' ').trim());
      const cards = await page.evaluate(() => document.querySelectorAll('.list-grid .list-card').length);
      await t.stillHere(token);
      assert(cards === 2, `Not seen tab shows ${cards} titles (expected 2)`);
      await page.goBack();
      await t.waitFor(() => !location.search.includes('show=unseen') && /all/i.test(document.querySelector('.list-filter-tabs .active')?.innerText || ''));
      await t.navClick('.list-back');
      assert(page.url().endsWith('/lists'), `back went to ${page.url()}`);
      return `"${a.backText}"; ${a.progress}; tabs ${a.tabs.join(' / ')}; unseen tab: ${unseen}`;
    });

    if (mobile) {
      await t.step('mobile bottom nav order', async () => {
        await t.go('/');
        const hrefs = await page.evaluate(() => [...document.querySelectorAll('.bottom-nav a')].map((a) => a.getAttribute('href')));
        assert(JSON.stringify(hrefs) === JSON.stringify(['/', '/ratings', '/discover', '/watchlist', '/lists']), `order ${hrefs.join(' ')}`);
        return hrefs.join(' ');
      });
      await t.step('mobile menu sheet', async () => {
        const btn = await page.$('#btnNavSettings');
        assert(btn, 'no menu button');
        await btn.click();
        await t.waitFor(() => [...document.querySelectorAll('.nav-sheet-item')].filter((a) => a.offsetParent).length > 0);
        await t.shot('menu-sheet');
        const items = await page.evaluate(() => [...document.querySelectorAll('.nav-sheet-item')].filter((a) => a.offsetParent).length);
        assert(items === 4, `menu sheet shows ${items} items (expected Community, Profile, Configuration, Logout)`);
        return `${items} items`;
      });
    }

    await t.step('nav links', async () => {
      await t.go('/');
      const links = await page.evaluate(() => [...document.querySelectorAll('header a, .site-header a')].map((a) => a.getAttribute('href')).filter(Boolean));
      assert(!links.includes('/tags'), 'Tags link should be hidden');
      assert(!links.includes('/movies') && !links.includes('/tvshows'), 'Movies/TV should be one Ratings tab');
      if (!mobile) {
        const missing = ['/ratings', '/discover', '/watchlist', '/lists', '/community'].filter((h) => !links.includes(h));
        assert(missing.length === 0, `missing ${missing.join(' ')}`);
      }
      return [...new Set(links)].join(' ');
    });

    await t.step('Ratings tab: switch + filters', async () => {
      await t.go('/ratings');
      const a = await page.evaluate(() => ({ url: location.pathname, h1: document.querySelector('h1')?.innerText, active: document.querySelector('.ratings-switch-link.active')?.innerText.trim(),
        navActive: [...document.querySelectorAll('.nav-link.active, .bottom-nav-item.active')].map((x) => x.getAttribute('href')) }));
      assert(a.url === '/movies' && a.active === 'Movies', `landed ${a.url} active ${a.active}`);
      assert(a.navActive.includes('/ratings'), `nav active ${a.navActive}`);
      await t.shot('ratings-movies');
      await t.navClick('.ratings-switch-link:not(.active)');
      const b = await page.evaluate(() => ({ url: location.pathname, active: document.querySelector('.ratings-switch-link.active')?.innerText.trim(), filter: !!document.querySelector('form.filter select#category') }));
      assert(b.url === '/tvshows' && b.active === 'TV Shows' && b.filter, JSON.stringify(b));
      await t.shot('ratings-tv');
      await t.go('/movies?category=16&limit=25');
      const c = await page.evaluate(() => ({ sel: document.querySelector('#category')?.value, limit: document.querySelector('#limit')?.value }));
      assert(c.sel === '16' && c.limit === '25', JSON.stringify(c));
      await t.noOverflow();
      await t.go('/movies');
      const ratio = await page.evaluate(() => { const i = document.querySelector('.grid a.card img'); if (!i) return null; const r = i.getBoundingClientRect(); return r.height / r.width; });
      // Phones keep the 2:3 box; desktop may crop (object-fit: cover), which never distorts.
      const fit = await page.evaluate(() => getComputedStyle(document.querySelector('.grid a.card img') || document.body).objectFit);
      assert(ratio === null || (mobile ? Math.abs(ratio - 1.5) < 0.08 : fit === 'cover'), `ranking poster ratio ${ratio} fit ${fit}`);
      await t.go('/ratings?type=tv');
      assert(page.url().endsWith('/tvshows'), page.url());
      return `${a.h1}; /ratings -> /movies, switch -> /tvshows, filters kept, ?type=tv ok`;
    });

    await t.step('tags hidden on title, top and profile pages', async () => {
      const found = [];
      for (const url of ['/movie/862', '/tv/1396', '/movies', '/tvshows', '/profile']) {
        await t.go(url);
        if (await page.evaluate(() => !!document.querySelector('.title-tags, select#tag, [onclick*="/tags"], a[href="/tags"]'))) found.push(url);
      }
      assert(found.length === 0, `tags still visible on ${found.join(', ')}`);
      await t.go('/tags');
      return `none visible; /tags still loads (${page.url().replace(t.base, '')})`;
    });
  },
};
