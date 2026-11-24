// Title page details: trailer thumbnail size per width + player modal, "Delete rating" in the movie and TV season editors,
// and friendly error pages. Ported from the scratchpad's uitest/trailer_check.js, delete_rating_check.js and status404.js.
const { setTitle, setTvSeasons } = require('../lib/api');

const WOLF = 106646; // The Wolf of Wall Street (the user's trailer screenshot)
const INCREDIBLES = 9806;
const BREAKING_BAD = 1396;

module.exports = {
  name: 'title',
  description: 'trailer thumbnail + modal, Delete rating (movie + TV season), error pages',

  async reset(env) {
    await setTitle(env.api, 'movie', INCREDIBLES, { score: 7.5 });
    await setTvSeasons(env.api, BREAKING_BAD, { 1: 9 });
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
      await t.navClick('#deleteMovieRatingModal button[type=submit]');
      const gone = (await t.api.call('GET', `/ratings/movie/${INCREDIBLES}`)).status;
      assert(gone === 404, `rating still there (API ${gone})`);
      const rate = await page.evaluateHandle(() => [...document.querySelectorAll('[data-open-modal="rateMovieModal"]')].find((b) => b.offsetParent));
      assert(await rate.evaluate((b) => !!b), 'no rate button after delete');
      await t.press(rate);
      await t.waitFor(() => document.querySelector('#rateMovieModal.visible'));
      const hidden = await page.evaluate(() => document.querySelector('#rateMovieModal .rate-modal-delete').hidden);
      assert(hidden, 'a new rating shows Delete rating');
      return 'deleted (API 404); create mode hides the button';
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
