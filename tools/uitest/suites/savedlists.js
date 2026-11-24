// Saved lists (2026-11-24): Save on an official list's card and page saves in place and moves it to the front of the
// Official sections, /lists/official puts saved lists first, the title page's "Official lists" chips put saved ones first.
// Seeds two official public lists of its own (deleted at the end with the run's other lists, which drops their saves).
const { createList } = require('../lib/api');

const PULP = 680; // #3 in "saved A", #1 in "saved B"

module.exports = {
  name: 'savedlists',
  description: 'Save / Saved on official cards and pages in place, saved lists first, title page official chips',

  async setup(env) {
    const a = await createList(env.api, `${env.prefix} saved A`, [603, 27205, PULP, 155, 13].map((id) => ['movie', id]));
    const b = await createList(env.api, `${env.prefix} saved B`, [PULP, 278].map((id) => ['movie', id]));
    for (const id of [a, b]) await env.api.must('PATCH', `/lists/${id}`, { isOfficial: true, isPublic: true });
    return { a, b, nameA: `${env.prefix} saved A`, nameB: `${env.prefix} saved B` };
  },

  async reset(env, data) {
    for (const id of [data.a, data.b]) await env.api.must('DELETE', `/lists/${id}/saved`);
  },

  async run(t) {
    const { page, assert } = t;
    const { a, b, nameA, nameB } = t.data;
    const names = (sel) => page.$$eval(`${sel} > *`, (els) => els.map((el) => (el.querySelector('.list-tile-name') || {}).textContent));
    const isSaved = (sel) => page.$eval(sel, (f) => f.classList.contains('is-saved')).catch(() => null);

    await t.step('login', () => t.login());

    await t.step('Lists tab: Save on an official card saves in place and moves it first', async () => {
      await t.go('/lists');
      const card = `#official-lists [data-saved-card][data-list-key="/list/${a}/saved"]`;
      assert(await page.$(card), 'the seeded official list has no card in the Official row');
      const token = await t.mark();
      await t.pressSel(`${card} .list-save-button`);
      await t.waitFor((sel) => document.querySelector(sel)?.classList.contains('is-saved'), card);
      const toast = await t.toast('Saved');
      await t.stillHere(token);
      const order = await names('#official-lists .home-row-track');
      assert(order[0] === nameA, `first card is ${order[0]}`);
      assert((await page.$eval(`${card} .list-save-button`, (b) => b.getAttribute('aria-pressed'))) === 'true', 'aria-pressed not true');
      await t.shot('lists-saved');
      await t.noOverflow();
      return toast;
    });

    await t.step('list page: Save beside the title, in place', async () => {
      await t.go(`/list/${b}`);
      const form = '.list-head-row .list-save.is-page';
      assert((await isSaved(form)) === false, 'no unsaved Save button beside the title');
      const token = await t.mark();
      await t.pressSel(`${form} .list-save-button`);
      await t.waitFor((sel) => document.querySelector(sel)?.classList.contains('is-saved'), form);
      await t.stillHere(token);
      const label = await page.$eval(`${form} .list-save-label`, (e) => e.textContent.trim());
      assert(label === 'Saved', `label ${label}`);
      await t.shot('list-page-saved');
      await t.noOverflow();
      return label;
    });

    await t.step('/lists/official: saved lists first, newest save first', async () => {
      await t.go('/lists/official');
      const order = await names('.official-grid');
      assert(order[0] === nameB && order[1] === nameA, `order ${order.slice(0, 3).join(' | ')}`);
      await t.noOverflow();
      return order.slice(0, 2).join(' | ');
    });

    await t.step('title page: official chips, saved ones first', async () => {
      await t.go(`/movie/${PULP}`);
      const chips = await page.$$eval('.title-official-lists .title-official-chip', (els) =>
        els.map((e) => ({ text: e.textContent.replace(/\s+/g, ' ').trim(), saved: e.classList.contains('is-saved') })));
      assert(chips.length >= 2, `chips ${JSON.stringify(chips)}`);
      assert(chips[0].saved && chips[0].text.includes(`#1 in ${nameB}`), `first chip ${JSON.stringify(chips[0])}`);
      assert(chips[1].saved && chips[1].text.includes(`#3 in ${nameA}`), `second chip ${JSON.stringify(chips[1])}`);
      await page.evaluate(() => document.querySelector('.title-official-lists').scrollIntoView({ block: 'center', behavior: 'instant' }));
      await t.shot('title-chips');
      await t.noOverflow();
      return `${chips.length} chips`;
    });

    await t.step('un-save on the list page puts it back', async () => {
      await t.go(`/list/${a}`);
      const form = '.list-head-row .list-save.is-page';
      assert((await isSaved(form)) === true, 'not shown as saved');
      await t.pressSel(`${form} .list-save-button`);
      await t.waitFor((sel) => !document.querySelector(sel)?.classList.contains('is-saved'), form);
      await t.go('/lists/official');
      const order = await names('.official-grid');
      // B is still saved (first); A is back among the rest, in their usual order, with a plain "Save".
      assert(order[0] === nameB, `order ${order.slice(0, 3).join(' | ')}`);
      assert((await isSaved(`.official-grid [data-list-key="/list/${a}/saved"] .list-save`)) === false, 'A still shows as saved');
      const saved = (await t.api.must('GET', '/lists/saved')).map((l) => l.id);
      assert(!saved.includes(a) && saved.includes(b), `API saved ${JSON.stringify(saved)}`);
      return 'back in place';
    });
  },
};
