#!/usr/bin/env node
// Ratelog browser walkthroughs. Self-seeding: logs in, makes its own API token (memory only), snapshots the account's
// ratings and statuses, seeds what each suite needs under unique "uitest <stamp>" names, runs the suites per viewport,
// then deletes its lists, puts every rating / status back and revokes the token. See docs/testing.md.
//
// node tools/uitest/run.js --base http://localhost:8125 [--suite home,seen,...] [--viewports desktop,phone390,phone360]
//                          [--out dir] [--user admin] [--password admin]
const fs = require('fs');
const path = require('path');
const { launch, makeContext, VIEWPORTS } = require('./lib/browser');
const { createToken, revokeTokens, makeApi, snapshot, restore, deleteLists } = require('./lib/api');

// Run order; a name only counts once its suites/<name>.js exists.
const ORDER = ['home', 'seen', 'tilerate', 'title', 'inlists', 'recs', 'listorder', 'backnav', 'listsuggest', 'misc'];
const SUITES = ORDER.filter((n) => fs.existsSync(path.join(__dirname, 'suites', `${n}.js`)));

function parseArgs(argv) {
  const a = { base: null, suite: null, viewports: 'desktop,phone390,phone360', out: null,
    user: process.env.UITEST_USER || 'admin', password: process.env.UITEST_PASSWORD || 'admin' };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    const v = () => { const x = argv[++i]; if (x === undefined) throw new Error(`${k} needs a value`); return x; };
    if (k === '--base') a.base = v().replace(/\/+$/, '');
    else if (k === '--suite' || k === '--suites') a.suite = v();
    else if (k === '--viewports' || k === '--viewport') a.viewports = v();
    else if (k === '--out') a.out = v();
    else if (k === '--user') a.user = v();
    else if (k === '--password') a.password = v();
    else if (k === '--help' || k === '-h') a.help = true;
    else throw new Error(`unknown argument ${k}`);
  }
  return a;
}

function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

(async () => {
  let args;
  try { args = parseArgs(process.argv.slice(2)); } catch (e) { console.error(e.message); process.exit(2); }
  if (args.help || !args.base) {
    console.log('usage: node tools/uitest/run.js --base http://localhost:PORT [--suite ' + SUITES.join(',') + ']\n' +
      '       [--viewports ' + Object.keys(VIEWPORTS).join(',') + '] [--out dir] [--user admin] [--password admin]');
    process.exit(args.help ? 0 : 2);
  }
  const suiteNames = args.suite ? args.suite.split(',').map((s) => s.trim()).filter(Boolean) : SUITES;
  for (const s of suiteNames) if (!SUITES.includes(s)) { console.error(`unknown suite ${s} (known: ${SUITES.join(', ')})`); process.exit(2); }
  const vps = args.viewports.split(',').map((s) => s.trim()).filter(Boolean);
  for (const v of vps) if (!VIEWPORTS[v]) { console.error(`unknown viewport ${v} (known: ${Object.keys(VIEWPORTS).join(', ')})`); process.exit(2); }
  const runStamp = stamp();
  const prefix = `uitest ${runStamp}`;
  const out = path.resolve(args.out || path.join(__dirname, 'screenshots', runStamp));
  fs.mkdirSync(out, { recursive: true });
  console.log(`Ratelog walkthrough ${prefix} against ${args.base}; suites ${suiteNames.join(',')}; viewports ${vps.join(',')}; screenshots ${out}`);

  const browser = await launch();
  const ctxOpts = { base: args.base, outDir: out, user: args.user, password: args.password };
  const adminCtx = await browser.createBrowserContext();
  const admin = await makeContext(adminCtx, { ...ctxOpts, suite: 'setup', vpName: 'desktop' });
  const summary = [];
  let api = null;
  let before = null;
  let cleaning = false;

  async function cleanup() {
    if (cleaning) return;
    cleaning = true;
    const notes = [];
    try {
      if (api) notes.push(`${await deleteLists(api, prefix)} lists deleted`);
      if (api && before) notes.push(`${await restore(api, before)} titles restored`);
    } catch (e) { notes.push(`CLEANUP FAILED: ${e.message}`); }
    try { notes.push(`${await revokeTokens(admin, prefix)} token revoked`); } catch (e) { notes.push(`TOKEN REVOKE FAILED: ${e.message}`); }
    console.log(`cleanup: ${notes.join(', ')}`);
    return notes.every((n) => !/FAILED/.test(n));
  }
  process.on('SIGINT', async () => { console.log('\ninterrupted: cleaning up'); await cleanup(); await browser.close().catch(() => {}); process.exit(130); });

  let failed = 0;
  let cleanOk = false;
  try {
    await admin.login();
    api = makeApi(args.base, await createToken(admin, prefix));
    const me = await api.must('GET', '/me');
    before = await snapshot(api);
    console.log(`setup: logged in as ${me.username}; snapshot of ${Object.keys(before.movie).length} movie ratings, ` +
      `${Object.keys(before.tv).length} show ratings, ${Object.keys(before.status).length} statuses`);
    const env = { api, prefix, base: args.base, shared: {} };

    for (const name of suiteNames) {
      const suite = require(`./suites/${name}`);
      const results = [];
      let data = {};
      try {
        data = (await suite.setup?.(env)) || {};
      } catch (e) {
        const line = `[${name}] FAIL setup: ${e.message}`;
        console.log(line);
        results.push({ ok: false, line });
      }
      if (!results.length) {
        for (const vpName of vps) {
          try { await suite.reset?.(env, data); } catch (e) {
            console.log(`[${name}/${vpName}] FAIL reset: ${e.message}`);
            results.push({ ok: false });
            continue;
          }
          const bctx = await browser.createBrowserContext();
          const t = await makeContext(bctx, { ...ctxOpts, suite: name, vpName, api, data: { ...data, prefix, shared: env.shared } });
          try { await suite.run(t); } catch (e) {
            console.log(`[${name}/${vpName}] FAIL (suite crashed): ${e.message.split('\n')[0]}`);
            t.results.push({ ok: false });
          }
          results.push(...t.results);
          await bctx.close().catch(() => {});
        }
      }
      const ok = results.filter((r) => r.ok).length;
      failed += results.length - ok;
      summary.push(`${name}: ${ok}/${results.length} passed`);
      console.log(`== ${name}: ${ok}/${results.length} passed`);
    }
  } catch (e) {
    console.log(`FAIL setup: ${e.message}`);
    failed += 1;
  } finally {
    cleanOk = await cleanup();
    await browser.close().catch(() => {});
  }
  console.log('\n' + summary.join('\n'));
  console.log(failed || !cleanOk ? `FAILED (${failed} failing steps${cleanOk ? '' : ', cleanup incomplete'})` : 'ALL PASSED');
  process.exit(failed || !cleanOk ? 1 : 0);
})();
