// Fork: Discover grid enhancements (templates/discover.html). Without JS, "Load more" opens the next page.
// - Load more: fetches the next page's results fragment (?partial=1) and appends its tiles. Delegated from `document`
//   and looked up when used, so it keeps working after view-swap.js swaps the view in place.
// - Coming back keeps your place (user feedback 2026-11-23, docs/decisions/navigation-and-reloads.md "One Back leaves the page"):
//   the pages "Load more" appended are remembered per tab (sessionStorage, the grid's URL + the pages' hrefs + the
//   scroll position). When you return to that same URL with Back / Forward, a reload or a title page's "← Discover"
//   link, those pages are fetched again (fresh, so Seen / bookmarks changed meanwhile show) and appended, and the
//   scroll position is put back unless you have scrolled yourself. The pages are not cached as HTML: Discover is
//   `no-store`, and a stale copy would show a title you just marked seen.
// (The poster quick actions are tile-actions.js.)
(() => {
    if (!window.fetch || !window.DOMParser) return;

    const KEY = 'ratelog.discover.grid';
    const MAX_AGE_MS = 6 * 60 * 60 * 1000;
    const MAX_PAGES = 10;

    const parse = (html) => new DOMParser().parseFromString(html, 'text/html');
    const refreshIcons = () => { if (window.lucide) window.lucide.createIcons(); };
    const grid = () => document.getElementById('browseGrid');
    const more = () => document.getElementById('browseMore');
    const here = () => location.pathname + location.search;

    const read = () => {
        try { return JSON.parse(sessionStorage.getItem(KEY) || 'null'); } catch (err) { return null; }
    };
    const write = (value) => {
        try { sessionStorage.setItem(KEY, JSON.stringify(value)); } catch (err) { /* storage unavailable: page 1 only */ }
    };

    // The pages "Load more" appended to the grid shown now (their hrefs, in order).
    let loaded = [];
    let restoring = false;

    function save() {
        if (!grid() || restoring) return;
        write({ url: here(), loaded, y: Math.round(window.scrollY), t: Date.now() });
    }

    // --- Load more ---

    function partialUrl(href) {
        const url = new URL(href, window.location.href);
        url.searchParams.set('partial', '1');
        return url;
    }

    async function fetchResults(href) {
        const response = await fetch(partialUrl(href), { credentials: 'same-origin', headers: { Accept: 'text/html' } });
        const results = parse(await response.text()).getElementById('browseResults');
        // Logged out (login page) or something unexpected.
        if (!response.ok || !results) return { ok: false, page: true };
        if (results.querySelector('.browse-message')) return { ok: false };
        return { ok: true, results };
    }

    // Appends one page's tiles (skipping any already shown); returns the first new tile.
    function append(results) {
        const list = grid();
        const seen = new Set([...list.querySelectorAll('.browse-tile')].map((tile) => tile.dataset.key));
        let firstNew = null;
        results.querySelectorAll('#browseGrid > .browse-tile').forEach((tile) => {
            if (seen.has(tile.dataset.key)) return;
            const node = document.importNode(tile, true);
            list.appendChild(node);
            firstNew = firstNew || node;
        });
        const nextMore = results.querySelector('#browseMore');
        const nextLink = nextMore && nextMore.querySelector('a[data-load-more]');
        more().replaceChildren(...(nextLink ? [document.importNode(nextLink, true)] : []));
        return firstNew;
    }

    document.addEventListener('click', (event) => {
        const trigger = event.target.closest && event.target.closest('#browseMore a[data-load-more], #browseMore button[data-retry-href]');
        if (!trigger || !grid() || event.button > 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        loadMore(trigger.dataset.retryHref || trigger.getAttribute('href'), trigger);
    });

    async function loadMore(href, trigger) {
        if (trigger.getAttribute('aria-busy') === 'true') return;
        trigger.setAttribute('aria-busy', 'true');
        const box = more();
        const label = trigger.querySelector('span') || trigger;
        const original = label.textContent;
        label.textContent = box.dataset.loading || original;
        try {
            const answer = await fetchResults(href);
            if (!answer.ok) {
                if (answer.page) window.location.assign(href); else showLoadError(href);
                return;
            }
            const firstNew = append(answer.results);
            loaded.push(href);
            save();
            refreshIcons();
            if (firstNew) firstNew.querySelector('a')?.focus({ preventScroll: true });
        } catch (error) {
            showLoadError(href);
        } finally {
            trigger.removeAttribute('aria-busy');
            if (trigger.isConnected) label.textContent = original;
        }
    }

    function showLoadError(href) {
        const box = more();
        const message = document.createElement('p');
        message.className = 'browse-message';
        message.setAttribute('role', 'alert');
        const text = document.createElement('span');
        text.textContent = box.dataset.error || 'Something went wrong.';
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'secondary';
        retry.dataset.retryHref = href;
        retry.textContent = box.dataset.retry || 'Try again';
        message.append(text, retry);
        box.replaceChildren(message);
    }

    // --- Coming back: the pages you had loaded, and where you were ---

    const navigationType = () => {
        try {
            const entry = performance.getEntriesByType('navigation')[0];
            if (entry && entry.type) return entry.type;
        } catch (err) { /* old browser */ }
        return 'navigate';
    };

    async function restore(record) {
        let touched = false;
        const stop = () => { touched = true; };
        ['wheel', 'touchstart', 'mousedown', 'keydown'].forEach((type) =>
            window.addEventListener(type, stop, { passive: true, once: true }));
        restoring = true;
        if (window.Loader) window.Loader.start();
        const hrefs = record.loaded.slice(0, MAX_PAGES);
        try {
            // Fetched together, appended in order; a page that fails stops there with its "Try again".
            const answers = await Promise.all(hrefs.map((href) => fetchResults(href).catch(() => ({ ok: false }))));
            if (!grid() || here() !== record.url) return; // the view changed meanwhile
            for (let i = 0; i < answers.length; i++) {
                if (!answers[i].ok) { showLoadError(hrefs[i]); break; }
                append(answers[i].results);
                loaded.push(hrefs[i]);
            }
            refreshIcons();
        } finally {
            restoring = false;
            if (window.Loader) window.Loader.stop();
        }
        const scroll = () => {
            if (!touched && Math.abs(window.scrollY - record.y) > 2) window.scrollTo({ top: record.y, left: 0, behavior: 'instant' });
        };
        scroll();
        // Posters above may still change the height a little.
        [300, 900].forEach((ms) => setTimeout(scroll, ms));
        save();
    }

    function start() {
        if (!grid()) return;
        const record = read();
        const returned = navigationType() !== 'navigate' || !!(window.RatelogBackNav && window.RatelogBackNav.returned);
        if (returned && record && record.url === here() && Array.isArray(record.loaded) && record.loaded.length
            && Date.now() - (record.t || 0) < MAX_AGE_MS && more() && more().querySelector('a[data-load-more]')) {
            restore(record);
        } else {
            save();
        }
    }

    // Keep the record current: the scroll position, right before a link leaves, and when the page is hidden.
    let scrollTimer = 0;
    window.addEventListener('scroll', () => {
        clearTimeout(scrollTimer);
        scrollTimer = setTimeout(save, 150);
    }, { passive: true });
    document.addEventListener('click', (event) => { if (event.target.closest && event.target.closest('a[href]')) save(); }, true);
    window.addEventListener('pagehide', save);
    // A filter applied in place (view-swap.js) shows a new grid from its first page.
    document.addEventListener('view:swapped', () => {
        loaded = [];
        save();
    });

    start();
})();
