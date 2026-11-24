// Shared browser helpers for the walkthrough suites: launch, viewports, step runner, waits, problem collector.
// Every suite step gets a `t` (test context) built by makeContext(); see docs/testing.md.
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');

const VIEWPORTS = {
  desktop: { width: 1440, height: 900 },
  laptop: { width: 1280, height: 720 },
  phone390: { width: 390, height: 844, deviceScaleFactor: 3, isMobile: true, hasTouch: true },
  phone360: { width: 360, height: 740, deviceScaleFactor: 2, isMobile: true, hasTouch: true },
};

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean);

function chromePath() {
  const found = CHROME_CANDIDATES.find((p) => fs.existsSync(p));
  if (!found) throw new Error('Chrome not found; set CHROME_PATH');
  return found;
}

const PHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Known noise that is not a bug: YouTube has no maxresdefault thumbnail for some trailers (the page falls back to
// hqdefault), and browsers ask for /favicon.ico on their own.
const IGNORED = [/maxresdefault/, /favicon\.ico/];
const ignored = (text) => IGNORED.some((re) => re.test(text));

async function launch() {
  return puppeteer.launch({ executablePath: chromePath(), headless: 'new', args: ['--no-sandbox', '--disable-dev-shm-usage'] });
}

class StepError extends Error {}

/**
 * Builds the per-viewport test context. `opts`: { base, outDir, suite, vpName, user, password, report, api, data }.
 */
async function makeContext(browser, opts) {
  const vp = VIEWPORTS[opts.vpName];
  if (!vp) throw new Error(`unknown viewport ${opts.vpName} (known: ${Object.keys(VIEWPORTS).join(', ')})`);
  const page = await browser.newPage();
  await page.setViewport(vp);
  if (vp.isMobile) {
    await page.setUserAgent(PHONE_UA);
  }
  page.setDefaultTimeout(30000);
  page.setDefaultNavigationTimeout(90000);
  const BASE = opts.base;
  const label = `${opts.suite}/${opts.vpName}`;
  const problems = [];
  let allow = null; // per-step regex of expected problems (e.g. a deliberate 404)
  const add = (p) => { if (!ignored(p) && !(allow && allow.test(p))) problems.push(p); };
  // Console errors, page errors and failed requests of a page count against the step that is running.
  const watch = (p) => {
    p.on('console', (m) => {
      if (m.type() !== 'error') return;
      const where = (m.location() && m.location().url) || '';
      add(`console: ${m.text()}${where ? ' @ ' + where.replace(BASE, '') : ''}`);
    });
    p.on('pageerror', (e) => add(`pageerror: ${e.message}`));
    p.on('response', (r) => { if (r.status() >= 400) add(`HTTP ${r.status()} ${r.url().replace(BASE, '')}`); });
    p.on('dialog', async (d) => { await d.accept().catch(() => {}); });
  };
  watch(page);

  const outDir = path.join(opts.outDir, opts.suite);
  fs.mkdirSync(outDir, { recursive: true });
  let shotNo = 0;
  const results = [];

  const t = {
    page, base: BASE, vp, vpName: opts.vpName, mobile: !!vp.isMobile, label, api: opts.api, data: opts.data, sleep, results,

    /** A second tab in the same session (same cookies), watched like the main one. */
    async newTab(js = true) {
      const p = await page.browserContext().newPage();
      await p.setViewport(vp);
      if (vp.isMobile) await p.setUserAgent(PHONE_UA);
      await p.setJavaScriptEnabled(js);
      p.setDefaultTimeout(30000);
      p.setDefaultNavigationTimeout(90000);
      watch(p);
      return p;
    },

    assert(cond, msg) { if (!cond) throw new StepError(msg); },

    async go(url, p = page) {
      await p.goto(BASE + url, { waitUntil: 'networkidle2', timeout: 90000 });
    },

    /** Screenshot named <viewport>-<nn>-<name>.png under <out>/<suite>/. Waits for fonts and two frames, not a fixed sleep. */
    async shot(name, fullPage = false, p = page) {
      shotNo += 1;
      await p.evaluate(() => document.fonts && document.fonts.ready).catch(() => {});
      await p.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))).catch(() => {});
      await sleep(250); // let a CSS transition (toast / sheet) settle
      const file = `${opts.vpName}-${String(shotNo).padStart(2, '0')}-${name.replace(/[^a-z0-9-]+/gi, '-').slice(0, 50)}.png`;
      await p.screenshot({ path: path.join(outDir, file), fullPage });
      return file;
    },

    overflow(p = page) { return p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth); },
    async noOverflow(p = page) { const o = await t.overflow(p); t.assert(o <= 0, `horizontal overflow ${o}px`); },

    /** Sets a window marker; stillHere(token) fails if the page reloaded or navigated since. */
    mark() { return page.evaluate(() => { window.__marker = Math.random(); return window.__marker; }); },
    async stillHere(token) {
      const v = await page.evaluate(() => window.__marker);
      t.assert(v === token, 'the page reloaded or navigated (marker gone)');
    },

    waitFor(fn, arg, timeout = 20000) { return page.waitForFunction(fn, { timeout, polling: 100 }, arg); },
    /** Clicks something that navigates and waits for the new page. */
    async navClick(selectorOrHandle, p = page) {
      const h = typeof selectorOrHandle === 'string' ? await p.$(selectorOrHandle) : selectorOrHandle;
      t.assert(h, `missing ${selectorOrHandle}`);
      // Pages save their scroll position with history.replaceState as a link is clicked, which puppeteer also reports as a
      // navigation. Done once the URL changed or a new document replaced this one (the per-click token is gone), e.g. a
      // form post that redirects back to the same URL.
      const start = p.url();
      const token = `nav${Math.random()}`;
      await p.evaluate((v) => { window.__navClick = v; }, token).catch(() => {});
      const sameDoc = () => p.evaluate((v) => window.__navClick === v, token).catch(() => false);
      const deadline = Date.now() + 90000;
      await Promise.all([p.waitForNavigation({ waitUntil: 'networkidle2', timeout: 90000 }), h.click()]);
      while (p.url() === start && (await sameDoc()) && Date.now() < deadline) {
        await p.waitForNavigation({ waitUntil: 'networkidle2', timeout: Math.min(15000, deadline - Date.now()) }).catch(() => {});
      }
    },

    /** Tap on touch viewports, a real mouse click otherwise (hover first makes hover-only controls appear). */
    async press(handle) {
      // html has scroll-behavior: smooth; scroll instantly so the tap lands where the element really is.
      await handle.evaluate((el) => el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }));
      await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
      if (vp.isMobile) await handle.tap(); else await handle.click();
    },
    async pressSel(selector) { const h = await page.$(selector); t.assert(h, `missing ${selector}`); await t.press(h); },

    /**
     * Uses a poster's quick action the way a person does: `act` is watchlist, seen, rate or lists. On touch screens the
     * poster shows one "⋯" button that opens a chooser (tile-actions.js); with a mouse the buttons appear on hover.
     * `stack` is a [data-tile-actions] element (handle) or a selector for one. Returns 'menu' or 'hover'.
     */
    async tileAct(stack, act) {
      const s = typeof stack === 'string' ? await page.$(stack) : stack;
      t.assert(s && (await s.evaluate((el) => !!el)), `no poster actions ${typeof stack === 'string' ? stack : ''}`);
      const menuBtn = await s.$('[data-tile-menu]');
      const touch = !!menuBtn && (await menuBtn.evaluate((b) => b.getClientRects().length > 0 && getComputedStyle(b).display !== 'none'));
      if (touch) {
        await t.press(menuBtn);
        await t.waitFor(() => { const m = document.querySelector('[data-menu-pop]'); return m && !m.hidden; });
        const item = await page.$(`[data-menu-pop]:not([hidden]) [data-menu-act="${act}"]:not([hidden])`);
        t.assert(item, `the poster's "⋯" chooser has no ${act} row`);
        await t.press(item);
        return 'menu';
      }
      const sel = { watchlist: 'form.tile-watchlist button', seen: 'form.tile-seen button', rate: 'a.tile-rate', lists: 'a.tile-list' }[act];
      const control = await s.$(sel);
      t.assert(control, `no ${act} button on the poster`);
      await control.evaluate((el) => el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }));
      await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
      await s.hover(); // the buttons are hover-only with a mouse
      await control.hover();
      await control.click();
      return 'hover';
    },

    /** Waits for a visible toast (any of the app's toast kinds) whose text differs from the last one, or contains `want`. */
    async toast(want) {
      const sel = '.tile-toast.visible, #browseToast.visible, .watchlist-toast.visible, .inplace-toast.visible, .home-toast.visible';
      await page.waitForFunction((s, prev, w) => {
        const el = document.querySelector(s);
        if (!el) return false;
        const text = el.textContent.trim();
        return w ? text.includes(w) : text !== prev;
      }, { timeout: 25000, polling: 100 }, sel, t._lastToast || '', want || null);
      t._lastToast = await page.evaluate((s) => document.querySelector(s).textContent.trim(), sel);
      return t._lastToast.replace(/\s+/g, ' ');
    },
    _lastToast: '',

    /** Runs one step: OK / FAIL line, console + HTTP problems fail it, one retry on a timeout. */
    async step(name, fn, stepOpts = {}) {
      let note;
      let error = null;
      let retried = '';
      for (let attempt = 1; attempt <= 2; attempt++) {
        const before = problems.length;
        allow = stepOpts.allow || null;
        try {
          note = await fn();
          error = null;
          const extra = problems.slice(before);
          if (extra.length) error = new StepError('console/HTTP problems: ' + extra.join(' | '));
          break;
        } catch (e) {
          error = e;
          // A busy laptop shows up as timeouts, or as a navigation landing while a check was reading the old page.
          const flaky = e && (e.name === 'TimeoutError' || /Waiting failed|timeout|exceeded|Execution context was destroyed|detached Frame/i.test(e.message || ''));
          if (attempt === 1 && flaky && stepOpts.retry !== false) {
            retried = `retried after: ${String(e.message).split('\n')[0]}`;
            problems.length = before;
            continue;
          }
          break;
        } finally {
          allow = null;
        }
      }
      const line = error
        ? `[${label}] FAIL ${name}: ${String(error.message).split('\n')[0]}${retried ? ' [' + retried + ']' : ''}`
        : `[${label}] OK   ${name}${note ? ' (' + note + ')' : ''}${retried ? ' [' + retried + ']' : ''}`;
      console.log(line);
      results.push({ ok: !error, name, line });
      if (error) await t.shot('FAIL-' + name).catch(() => {});
      return !error;
    },

    async login() {
      await t.go('/login');
      await page.type('input[name=username]', opts.user);
      await page.type('input[name=password]', opts.password);
      await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle2' }), page.click('form[action="/login/process"] [type=submit]')]);
      t.assert(!page.url().includes('/login'), `login failed for ${opts.user} (still on ${page.url().replace(BASE, '')})`);
    },
  };
  return t;
}

module.exports = { VIEWPORTS, launch, makeContext, sleep, StepError, chromePath };
