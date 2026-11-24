// API side of the suite: a throwaway token (memory only), calls with 429 back-off, and the state snapshot / restore that lets the
// suite run against any database (fresh or long-lived) and leave it as it found it.
const { sleep } = require('./browser');

/** Creates a token named `name` through /settings/api in a logged-in page. The value is returned, never printed or written. */
async function createToken(t, name) {
  await t.go('/settings/api');
  await t.page.type('form.api-create-form input[name=name]', name);
  await Promise.all([t.page.waitForNavigation({ waitUntil: 'networkidle2' }), t.page.click('form.api-create-form [type=submit]')]);
  const token = await t.page.$eval('#newTokenValue', (el) => el.value);
  if (!/^rl_/.test(token || '')) throw new Error('token page did not show a new token');
  return token;
}

/** Revokes every token whose name starts with `prefix` (this run's token, plus any left by a crashed earlier run). */
async function revokeTokens(t, prefix) {
  let revoked = 0;
  for (let i = 0; i < 20; i++) {
    await t.go('/settings/api');
    const found = await t.page.evaluate((p) => {
      const row = [...document.querySelectorAll('.api-token-row')].find((r) => (r.querySelector('strong')?.textContent || '').startsWith(p));
      if (!row) return false;
      row.querySelector('form').id = 'uitest-revoke';
      return true;
    }, prefix);
    if (!found) break;
    await Promise.all([t.page.waitForNavigation({ waitUntil: 'networkidle2' }), t.page.click('#uitest-revoke [type=submit]')]);
    revoked += 1;
  }
  return revoked;
}

function makeApi(base, token) {
  async function call(method, path, body) {
    for (let attempt = 0; attempt < 8; attempt++) {
      const r = await fetch(base + '/api/v1' + path, {
        method,
        headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      if (r.status === 429) {
        const wait = Number(r.headers.get('retry-after')) || 5;
        await sleep(Math.min(wait, 30) * 1000);
        continue;
      }
      const text = await r.text();
      let json = null;
      try { json = text ? JSON.parse(text) : null; } catch (e) { json = text; }
      return { status: r.status, json };
    }
    throw new Error(`${method} ${path}: still rate limited after 8 tries`);
  }
  /** Like call() but throws unless the status is one of `ok`. */
  async function must(method, path, body, ok = [200, 201, 204]) {
    const r = await call(method, path, body);
    if (!ok.includes(r.status)) throw new Error(`${method} ${path} -> ${r.status} ${JSON.stringify(r.json).slice(0, 200)}`);
    return r.json;
  }
  return { call, must };
}

// ---------- seeding helpers ----------

/**
 * Puts a title into an exact state: rating (quick number, detailed object, or null) and status (string or null).
 * Since the watchlist is independent of the status (2026-11-24): `want_to_watch` means exactly "on the watchlist, no
 * status", any other status means "that status, not on the watchlist", null clears both.
 */
async function setTitle(api, type, tmdbId, { score, detailed, status } = {}) {
  const t = type === 'tv' ? 'tv' : 'movie';
  if (t === 'movie') {
    if (score != null) await api.must('PUT', `/ratings/movie/${tmdbId}`, { score });
    else if (detailed) await api.must('PUT', `/ratings/movie/${tmdbId}`, detailed);
    else if (score === null || detailed === null) await api.must('DELETE', `/ratings/movie/${tmdbId}`, undefined, [204, 404]);
  }
  if (status === 'want_to_watch') {
    await api.must('DELETE', `/statuses/${t}/${tmdbId}`, undefined, [204, 404]);
    await api.must('PUT', `/statuses/${t}/${tmdbId}`, { status });
  } else if (status) {
    await api.must('DELETE', `/watchlist/${t}/${tmdbId}`, undefined, [200, 404]);
    await api.must('PUT', `/statuses/${t}/${tmdbId}`, { status });
  } else if (status === null) await api.must('DELETE', `/statuses/${t}/${tmdbId}`, undefined, [204, 404]);
}

/** Sets a show's season ratings to exactly `seasons` ({1: 8}); other rated seasons are deleted. */
async function setTvSeasons(api, tmdbId, seasons) {
  const cur = await api.call('GET', `/ratings/tv/${tmdbId}`);
  if (cur.status === 200) {
    for (const s of cur.json.seasons || []) {
      if (!(String(s.season) in seasons)) await api.must('DELETE', `/ratings/tv/${tmdbId}/seasons/${s.season}`, undefined, [204, 404]);
    }
  }
  for (const [season, score] of Object.entries(seasons)) await api.must('PUT', `/ratings/tv/${tmdbId}/seasons/${season}`, { score });
}

async function createList(api, name, items, extra = {}) {
  const r = await api.must('POST', '/lists', { name, items: items.map(([type, tmdbId]) => ({ tmdbId, type })), ...extra }, [201]);
  const id = (r.list && r.list.id) || r.id;
  if (!id) throw new Error('list create returned no id');
  const unresolved = (r.items && r.items.unresolved) || [];
  if (unresolved.length) throw new Error(`list ${name}: unresolved ${JSON.stringify(unresolved).slice(0, 200)}`);
  return id;
}

/** The first movie in the home "coming soon" row released at least 3 days from now (our "upcoming" title). */
async function findUpcomingMovie(api) {
  const home = await api.must('GET', '/home');
  const row = (home.rows || []).find((r) => r.id === 'coming_soon');
  const soon = Date.now() + 3 * 86400000;
  const pick = row && row.titles.find((x) => x.type === 'movie' && x.releaseDate && Date.parse(x.releaseDate) > soon);
  if (!pick) throw new Error(`no upcoming movie in the coming_soon row (tmdb ${home.tmdb})`);
  return { tmdbId: pick.tmdbId, title: pick.title, releaseDate: pick.releaseDate };
}

// ---------- snapshot / restore ----------

const ratingBody = (r) => {
  const body = r.mode === 'detailed' && r.categories ? { ...r.categories } : { score: r.score };
  body.reviewTitle = r.reviewTitle || '';
  body.reviewText = r.reviewText || '';
  return body;
};

/** Every rating and status of the account, keyed "movie:603" / "tv:1396". */
async function snapshot(api) {
  const ratings = await api.must('GET', '/ratings');
  const statuses = await api.must('GET', '/statuses');
  const s = { movie: {}, tv: {}, status: {} };
  for (const m of ratings.movies || []) s.movie[m.tmdbId] = m;
  for (const tv of ratings.tv || []) s.tv[tv.tmdbId] = tv;
  // A seen title can be on the watchlist too (2026-11-24): `watched+watchlist`.
  for (const it of statuses.items || []) {
    s.status[`${it.type === 'tv' ? 'tv' : 'movie'}:${it.tmdbId}`] = it.status + (it.onWatchlist && it.status !== 'want_to_watch' ? '+watchlist' : '');
  }
  return s;
}

/** Puts back every rating and status that differs from `before`. Returns how many titles it touched. */
async function restore(api, before) {
  const now = await snapshot(api);
  const sameMovie = (a, b) => (!a && !b) || (a && b && JSON.stringify(ratingBody(a)) === JSON.stringify(ratingBody(b)) && a.mode === b.mode);
  const seasonsKey = (tv) => JSON.stringify((tv ? tv.seasons : []).map((s) => [s.season, ratingBody(s), s.mode]).sort());
  const keys = new Set();
  for (const id of new Set([...Object.keys(before.movie), ...Object.keys(now.movie)])) {
    if (!sameMovie(before.movie[id], now.movie[id])) keys.add(`movie:${id}`);
  }
  for (const id of new Set([...Object.keys(before.tv), ...Object.keys(now.tv)])) {
    if (seasonsKey(before.tv[id]) !== seasonsKey(now.tv[id])) keys.add(`tv:${id}`);
  }
  for (const k of new Set([...Object.keys(before.status), ...Object.keys(now.status)])) {
    if (before.status[k] !== now.status[k]) keys.add(k);
  }
  for (const key of keys) {
    const [type, id] = key.split(':');
    if (type === 'movie') {
      const b = before.movie[id];
      if (!sameMovie(b, now.movie[id])) {
        if (b) await api.must('PUT', `/ratings/movie/${id}`, ratingBody(b));
        else await api.must('DELETE', `/ratings/movie/${id}`, undefined, [204, 404]);
      }
    } else if (seasonsKey(before.tv[id]) !== seasonsKey(now.tv[id])) {
      const bs = (before.tv[id] && before.tv[id].seasons) || [];
      for (const s of (now.tv[id] && now.tv[id].seasons) || []) {
        if (!bs.some((x) => x.season === s.season)) await api.must('DELETE', `/ratings/tv/${id}/seasons/${s.season}`, undefined, [204, 404]);
      }
      for (const s of bs) await api.must('PUT', `/ratings/tv/${id}/seasons/${s.season}`, ratingBody(s));
    }
    // Ratings set statuses as a side effect, so the status goes back last.
    const [st, listed] = (before.status[key] || '').split('+');
    if (st) {
      await setTitle(api, type, id, { status: st });
      if (listed) await api.must('PUT', `/watchlist/${type}/${id}`);
    } else await api.must('DELETE', `/statuses/${type}/${id}`, undefined, [204, 404]);
  }
  return keys.size;
}

/** Deletes every list of the account whose name starts with `prefix`. */
async function deleteLists(api, prefix) {
  const lists = await api.must('GET', '/lists');
  let n = 0;
  for (const l of lists || []) {
    if ((l.name || '').startsWith(prefix)) { await api.must('DELETE', `/lists/${l.id}`, undefined, [204, 404]); n += 1; }
  }
  return n;
}

module.exports = { createToken, revokeTokens, makeApi, setTitle, setTvSeasons, createList, findUpcomingMovie, snapshot, restore, deleteLists };
