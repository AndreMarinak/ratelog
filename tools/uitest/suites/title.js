// Title page details: trailer thumbnail size per width + player modal, the movie rate modal saving in place (create, edit
// quick and detailed, delete, ?rate=edit), "Delete rating" in the movie and TV season editors, and friendly error pages.
// Ported from the scratchpad's uitest/trailer_check.js, delete_rating_check.js and status404.js.
const { setTitle, setTvSeasons } = require('../lib/api');

const WOLF = 106646; // The Wolf of Wall Street (the user's trailer screenshot)
const INCREDIBLES = 9806;
const BREAKING_BAD = 1396;
const PARASITE = 496243; // rated / edited / deleted in place; unrated with no status before each viewport

module.exports = {
  name: 'title',
  description: 'trailer thumbnail + modal, movie rating in place (create / edit / delete, ?rate=edit), Delete rating (movie + TV season), error pages',

  async reset(env) {
    await setTitle(env.api, 'movie', INCREDIBLES, { score: 7.5 });
    await setTvSeasons(env.api, BREAKING_BAD, { 1: 9 });
    await setTitle(env.api, 'movie', PARASITE, { score: null, status: null });
  },

  async run(t) {
    const { page, assert } = t;
    await t.step('login', () => t.login());

    for (const path of [`/movie/${WOLF}`, `/tv/${BREAKING_BAD}`]) {
      await t.step(`trailer thumbnail size on ${path}`, async () => {
        await t.go(path);
        await page.evaluate(() => document.querySelector('.title-trailer')?.scrollIntoView({ block: 'center', behavior: 'instant' }));
        const w = t.vp.width;
        // The page upgrades the thumbnail to the HD image when YouTube has one; wait for that (or the fallback) to settle.
        await t.waitFor(() => { const i = document.querySelector('.title-trailer img'); return !i || (i.complete && i.naturalWidth > 0); }, null, 15000).catch(() => {});
        if (w >= 1100) await t.waitFor(() => { const i = document.querySelector('.title-trailer img'); return !i || i.naturalWidth >= 1280 || /hqdefault/.test(i.currentSrc); }, null, 15000).catch(() => {});
        const r = await page.evaluate(() => {
          const tr = document.querySelector('.title-trailer');
          if (!tr) return null;
          const img = tr.querySelector('img');
          return { trailer: Math.round(tr.getBoundingClientRect().width), height: Math.round(tr.getBoundingClientRect().height),
            column: Math.round(tr.parentElement.getBoundingClientRect().width), src: img ? img.currentSrc.split('/').pop() : null, natural: img ? img.naturalWidth : 0 };
        });
        assert(r, 'no trailer on the page');
        await t.shot(`trailer${path.replace(/\//g, '-')}`);
        await t.noOverflow();
        const tag = `trailer ${r.trailer}x${r.height}, column ${r.column}, ${r.src} (${r.natural}px)`;
        if (w >= 1100) {
          assert(r.trailer >= Math.min(r.column, 720) - 2, `${tag}: does not fill the column`);
          assert(r.natural >= 1280 || /hqdefault/.test(r.src || ''), `${tag}: not the HD image or the fallback`);
        } else if (w >= 700) {
          assert(r.trailer <= 720 && r.trailer >= 300, `${tag}: not tablet size`);
        } else {
          assert(r.trailer < 260, `${tag}: phone should keep the compact button`);
        }
        return tag;
      });
    }

    await t.step('trailer opens the player modal', async () => {
      await t.go(`/movie/${WOLF}`);
      await t.pressSel('.title-trailer .trailer-launch');
      await t.waitFor(() => !!document.querySelector('#trailerModal iframe'));
      await t.shot('trailer-modal');
      return 'iframe shown';
    });

    // Since 2026-11-24 the movie rate modal and its delete confirmation save in place (user: "also does editing the rating
    // when clicked on movie cause a refresh?"): "Your rating", the panel's Rate / Edit rating + score and Seen are swapped in.
    const card = () => page.evaluate(() => {
      const region = document.getElementById('your-rating');
      const edit = document.querySelector('.title-panel [data-open-modal="rateMovieModal"]');
      const seen = document.querySelector('[data-title-seen]');
      return {
        shown: !!region && !region.hidden && region.getBoundingClientRect().height > 0,
        text: region && !region.hidden ? region.innerText.replace(/\s+/g, ' ').trim() : '',
        bars: region ? region.querySelectorAll('.rating-breakdown .breakdown-row').length : 0,
        mode: edit ? edit.dataset.modalMode : null,
        score: document.querySelector('.title-panel .title-action-score')?.innerText.trim() || null,
        seen: !!seen && seen.classList.contains('is-on'),
        locked: !!seen && seen.classList.contains('is-locked'),
        modalOpen: !!document.querySelector('.modal.visible'),
        search: location.search,
      };
    });
    const clearToast = () => page.evaluate(() => document.querySelector('.inplace-toast')?.classList.remove('visible'));
    const toastText = async () => {
      await t.waitFor(() => document.querySelector('.inplace-toast.visible'));
      return page.evaluate(() => document.querySelector('.inplace-toast.visible').innerText.trim());
    };
    const panelScore = (want) => t.waitFor((w) => document.querySelector('.title-panel .title-action-score')?.innerText.trim() === w, want);
    const openEditor = async (selector) => {
      await t.pressSel(selector);
      await t.waitFor(() => document.querySelector('#rateMovieModal.visible'));
    };
    // Sliders are set by script (dragging to an exact 0.25 step is not what this checks); the mode by its own toggle.
    const setMode = (mode) => t.pressSel(`#rateMovieModal [data-rating-mode-option="${mode}"]`);
    const setSliders = (values) => page.evaluate((v) => {
      for (const [name, value] of Object.entries(v)) {
        window.rateLogSliders.set(document.querySelector(`#rateMovieModal .rate-slider[data-name="${name}"]`), value);
      }
    }, values);
    const save = async () => { await clearToast(); await t.pressSel('#rateMovieModal button[type=submit]'); };

    await t.step('movie rate modal: quick rate, edit quick, edit detailed, delete, all in place', async () => {
      await setTitle(t.api, 'movie', PARASITE, { score: null, status: null });
      await t.go(`/movie/${PARASITE}`);
      const token = await t.mark();
      const c0 = await card();
      assert(!c0.shown && c0.mode === 'create' && !c0.seen, `start: ${JSON.stringify(c0)}`);
      const notes = [];

      // 1. Create: quick 8.
      await openEditor('.title-panel [data-open-modal="rateMovieModal"][data-modal-mode="create"]');
      await setMode('quick');
      await setSliders({ score: 8 });
      await save();
      await panelScore('8.00');
      let c = await card();
      notes.push(`saved "${await toastText()}"`);
      await t.stillHere(token);
      assert(c.shown && /8\.00 \/ 10/.test(c.text) && /quick/i.test(c.text), `Your rating after create: ${JSON.stringify(c)}`);
      assert(c.mode === 'edit' && c.seen && c.locked && !c.modalOpen, `panel after create: ${JSON.stringify(c)}`);
      await t.noOverflow();
      await t.shot('movie-rated-quick');

      // 2. Edit quick (the panel's Edit rating): prefilled from the swapped-in button, posts to update.
      await openEditor('.title-panel .title-action.is-rated');
      const pre = await page.evaluate(() => ({ action: document.querySelector('#rateMovieModal form').getAttribute('action'),
        score: document.querySelector('#rateMovieModal .rate-slider[data-name="score"]').dataset.value,
        del: !document.querySelector('#rateMovieModal .rate-modal-delete').hidden }));
      assert(pre.action === '/movie/rate/update' && pre.score === '8.00' && pre.del, `editor prefill ${JSON.stringify(pre)}`);
      await setSliders({ score: 6.5 });
      await save();
      await panelScore('6.50');
      c = await card();
      notes.push(`updated "${await toastText()}"`);
      await t.stillHere(token);
      assert(c.shown && /6\.50 \/ 10/.test(c.text) && c.seen && !c.modalOpen, `after quick edit: ${JSON.stringify(c)}`);

      // 3. Edit detailed (the card's pencil): five categories; the card redraws its bars (breakdown.js).
      await openEditor('#your-rating .edit-rating-btn');
      await setMode('detailed');
      await setSliders({ directing: 9, cinematography: 8, acting: 7, soundtrack: 6, screenplay: 5 });
      await save();
      await panelScore('7.00');
      await t.waitFor(() => document.querySelectorAll('#your-rating .rating-breakdown .breakdown-row').length === 5);
      c = await card();
      await toastText();
      await t.stillHere(token);
      assert(c.shown && c.bars === 5 && !/quick/i.test(c.text) && c.seen, `after detailed edit: ${JSON.stringify(c)}`);
      await t.noOverflow();
      await t.shot('movie-rated-detailed');
      const directing = await page.evaluate(() => document.querySelector('.title-panel .title-action.is-rated').dataset.directing);
      assert(Number(directing) === 9, `the swapped-in Edit rating carries the new categories (directing ${directing})`);

      // 4. Delete (the card's trash): confirmation, then back to Rate; Seen stays on but unlocked (rating marked it Watched).
      await t.pressSel('#your-rating .delete-rating-btn');
      await t.waitFor(() => document.querySelector('#deleteMovieRatingModal.visible'));
      await clearToast();
      await t.pressSel('#deleteMovieRatingModal button[type=submit]');
      await t.waitFor(() => !document.querySelector('.title-panel .title-action.is-rated'));
      c = await card();
      notes.push(`deleted "${await toastText()}"`);
      await t.stillHere(token);
      assert(!c.shown && c.mode === 'create' && c.seen && !c.locked && !c.modalOpen, `after delete: ${JSON.stringify(c)}`);
      const gone = (await t.api.call('GET', `/ratings/movie/${PARASITE}`)).status;
      assert(gone === 404, `rating still there (API ${gone})`);
      await t.noOverflow();
      await t.shot('movie-deleted');
      return `no reload; ${notes.join(', ')}; 8.00 quick -> 6.50 -> 7.00 detailed (5 bars) -> deleted (API 404)`;
    }, { retry: false });

    await t.step('movie ?rate=edit (a poster\'s "Edit rating"): opens the editor, saves in place, URL replaced', async () => {
      await setTitle(t.api, 'movie', PARASITE, { score: 7, status: null });
      await t.go(`/movie/${PARASITE}?rate=edit`);
      await t.waitFor(() => document.querySelector('#rateMovieModal.visible'));
      const token = await t.mark();
      const h0 = await page.evaluate(() => history.length);
      const pre = await page.evaluate(() => ({ action: document.querySelector('#rateMovieModal form').getAttribute('action'),
        score: document.querySelector('#rateMovieModal .rate-slider[data-name="score"]').dataset.value }));
      assert(pre.action === '/movie/rate/update' && pre.score === '7.00', `?rate=edit editor ${JSON.stringify(pre)}`);
      await setMode('quick');
      await setSliders({ score: 9 });
      await save();
      await panelScore('9.00');
      const c = await card();
      await toastText();
      await t.stillHere(token);
      const h1 = await page.evaluate(() => history.length);
      assert(!/rate=/.test(c.search) && !/rate=/.test(page.url()), `URL kept rate=: ${page.url()}`);
      assert(h1 === h0, `history grew ${h0} -> ${h1} (pushed, not replaced)`);
      assert(c.shown && /9\.00 \/ 10/.test(c.text) && !c.modalOpen, `after save: ${JSON.stringify(c)}`);
      await t.shot('movie-rate-edit-param');
      await setTitle(t.api, 'movie', PARASITE, { score: null, status: null });
      return `opened from ?rate=edit, saved 9.00 in place, URL ${page.url().replace(t.base, '')}, history ${h0} -> ${h1}`;
    }, { retry: false });

    await t.step('movie editor: Delete rating -> confirmation -> deleted; new ratings show no delete', async () => {
      await setTitle(t.api, 'movie', INCREDIBLES, { score: 7.5 });
      await t.go(`/movie/${INCREDIBLES}`);
      const opener = await page.evaluateHandle(() => [...document.querySelectorAll('.title-panel [data-open-modal="rateMovieModal"][data-modal-mode="edit"], .title-actions [data-open-modal="rateMovieModal"]')].find((b) => b.offsetParent));
      assert(await opener.evaluate((b) => !!b), 'no rate / edit button');
      await t.press(opener);
      await t.waitFor(() => document.querySelector('#rateMovieModal.visible'));
      const m = await page.evaluate(() => {
        const btn = document.querySelector('#rateMovieModal .rate-modal-delete');
        const r = btn?.getBoundingClientRect();
        return { shown: !!btn && !btn.hidden && r.width > 0, text: btn?.innerText.trim() };
      });
      assert(m.shown && m.text === 'Delete rating', `editor delete button ${JSON.stringify(m)}`);
      await t.shot('movie-editor');
      await t.pressSel('#rateMovieModal .rate-modal-delete');
      await t.waitFor(() => !document.querySelector('#rateMovieModal').classList.contains('visible') && document.querySelector('#deleteMovieRatingModal').classList.contains('visible'));
      await t.shot('movie-confirm');
      // In place since 2026-11-24 (was a page load).
      const token = await t.mark();
      await t.pressSel('#deleteMovieRatingModal button[type=submit]');
      await t.waitFor(() => !document.querySelector('.title-panel .title-action.is-rated'));
      await t.stillHere(token);
      const gone = (await t.api.call('GET', `/ratings/movie/${INCREDIBLES}`)).status;
      assert(gone === 404, `rating still there (API ${gone})`);
      const rate = await page.evaluateHandle(() => [...document.querySelectorAll('[data-open-modal="rateMovieModal"]')].find((b) => b.offsetParent));
      assert(await rate.evaluate((b) => !!b), 'no rate button after delete');
      await t.press(rate);
      await t.waitFor(() => document.querySelector('#rateMovieModal.visible'));
      const hidden = await page.evaluate(() => document.querySelector('#rateMovieModal .rate-modal-delete').hidden);
      assert(hidden, 'a new rating shows Delete rating');
      return 'deleted in place (API 404); create mode hides the button';
    }, { retry: false });

    await t.step('TV season editor: Delete rating targets that season', async () => {
      await setTvSeasons(t.api, BREAKING_BAD, { 1: 9 });
      await t.go(`/tv/${BREAKING_BAD}`);
      // Not tapped like a person: the season's edit button can sit in a collapsed season card, so click it by script.
      const opened = await page.evaluate(() => {
        const b = document.querySelector('[data-open-modal="rateTvShowModal"][data-modal-mode="edit"][data-season="1"]');
        if (!b) return false;
        b.scrollIntoView({ block: 'center', behavior: 'instant' });
        b.click();
        return true;
      });
      assert(opened, 'season 1 edit button missing');
      await t.waitFor(() => document.querySelector('#rateTvShowModal.visible'));
      const tv = await page.evaluate(() => { const b = document.querySelector('#rateTvShowModal .rate-modal-delete'); return { shown: !!b && !b.hidden, season: b?.dataset.season }; });
      assert(tv.shown && tv.season === '1', `TV editor delete ${JSON.stringify(tv)}`);
      await t.pressSel('#rateTvShowModal .rate-modal-delete');
      await t.waitFor(() => document.querySelector('#deleteTvShowRatingModal').classList.contains('visible'));
      const season = await page.evaluate(() => document.querySelector('#deleteTvShowRatingModal input[name="seasonNumber"]')?.value);
      assert(season === '1', `confirmation targets season ${season}`);
      await t.shot('tv-confirm');
      // Since 2026-11-23 season deletes save in place (the season cards and the action panel are swapped in).
      const token = await t.mark();
      await t.pressSel('#deleteTvShowRatingModal button[type=submit]');
      await t.waitFor(() => !document.querySelector('[data-open-modal="rateTvShowModal"][data-modal-mode="edit"][data-season="1"]'));
      await t.stillHere(token);
      const gone = (await t.api.call('GET', `/ratings/tv/${BREAKING_BAD}`)).status;
      assert(gone === 404, `season rating still there (API ${gone})`);
      return 'season 1 deleted in place (API 404)';
    }, { retry: false });

    await t.step('error pages: 404 page, not a JSON 500', async () => {
      const seen = [];
      for (const [url, want] of [['/no-such-page', [404]], ['/movie/abc', [400, 404]], ['/static-missing.css', [404]]]) {
        const r = await page.goto(t.base + url, { waitUntil: 'networkidle2' });
        const body = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 80);
        assert(want.includes(r.status()), `${url}: HTTP ${r.status()} "${body}"`);
        assert(!/internal server error|whitelabel|exception/i.test(body), `${url}: "${body}"`);
        seen.push(`${url} ${r.status()}`);
      }
      await t.go('/no-such-page').catch(() => {});
      await t.shot('not-found');
      return seen.join(', ');
    }, { allow: /(HTTP 40[04] |@ )\/(no-such-page|movie\/abc|static-missing\.css)|@ chrome-error:\/\/chromewebdata/ });
  },
};
