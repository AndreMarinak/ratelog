// Fork: filters that apply in place (user feedback 2026-11-23: "the back button thing is REAL annoying on the phone,
// as well as refreshes for filtered list when it shouldn't need a refresh"; docs/design-decisions.md "One Back
// leaves the page"). Rule: filters replace, navigation pushes.
//
// <div id="discoverView" data-view data-view-fallback="type">      the region a filter changes (it needs an id)
//   <nav data-view-links="type"> <a href="/discover?type=tv">...    links that swap it ("named groups", for focus)
//
// - A plain click on a link inside a [data-view-links] group of a [data-view] region, to the same path, fetches that
//   URL and replaces the region with the same region from the answer, so every control, count and link matches the
//   server's view. The URL is replaced, not pushed (history.replaceState, keeping other scripts' state such as
//   back-nav.js's id): reload and sharing show the same view, and one Back leaves the page.
// - Focus goes back to the same control: a <details data-menu> menu's trigger, else the group's active (or first)
//   link, else the region's data-view-fallback group. Loader.start / stop (loader.js) show the thin bar if slow;
//   a newer click cancels an older one; the region gets `is-loading` and aria-busy while it loads.
// - `view:swapped` (detail: { url, regions }) lets page scripts start the new content (discover.js: rows' arrows and
//   lazy loading; browse.js: "Load more" pages).
// - <a data-keep-open data-option="name"> inside a <details data-menu>: a choice that is often refined right away
//   (a decade, then a year; one streaming service, then another). The menu stays open while it loads and is re-opened
//   on the fresh region, with focus on the same option and any [data-reveal] section scrolled into view; open
//   <details data-inner> parts of the menu stay open. Picks made while it loads are queued and replayed in order on
//   the fresh menu (its links carry the new state, so ticking two services quickly keeps both).
// - <a data-view-replace>: a page switch that still loads its page but replaces this history entry
//   (location.replace) instead of adding one (Ratings' Movies / TV / To rate switch).
// Without JS, on a modified click (new tab), or if anything fails, the link loads its page as before.
(() => {
    if (window.__ratelogViewSwap) return;
    window.__ratelogViewSwap = true;
    if (!window.fetch || !window.DOMParser || !window.history || !history.replaceState) return;

    let seq = 0;
    let inFlight = null;
    let inFlightKey = null;
    // Keep-open picks clicked while a swap of the same menu loads: { menu, option }, replayed one per swap.
    let queued = [];

    const plainClick = (event) =>
        !event.defaultPrevented && event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;

    function replaceUrl(url) {
        const state = history.state;
        const keep = state && typeof state === 'object' ? state : {};
        history.replaceState(Object.assign({}, keep, { view: true }), '', url);
    }

    const visible = (el) => !!el && el.getClientRects().length > 0;

    // What to focus once the region is swapped: described by name, since the clicked node is replaced.
    function focusKey(link) {
        const menu = link.closest('details[data-menu]');
        if (menu && link.hasAttribute('data-keep-open')) {
            const inner = [...menu.querySelectorAll('details[data-inner]')].filter((d) => d.open).map((d) => d.dataset.inner);
            return { menu: menu.dataset.menu, reopen: true, option: link.dataset.option || '', inner };
        }
        if (menu) return { menu: menu.dataset.menu };
        const group = link.closest('[data-view-links]');
        return { group: group ? group.dataset.viewLinks : '' };
    }

    function inGroup(group) {
        if (!group) return null;
        if (group.matches('a[href]')) return group;
        const active = [...group.querySelectorAll('a.active, a[aria-current="page"], a[aria-current="true"]')].find(visible);
        return active || [...group.querySelectorAll('a[href], summary')].find(visible) || null;
    }

    // Re-open a keep-open menu on the fresh region; true when focus has been placed.
    function reopen(key, region) {
        const menu = region.querySelector(`details[data-menu="${CSS.escape(key.menu)}"]`);
        if (!menu) return false;
        key.inner.forEach((name) => {
            const part = menu.querySelector(`details[data-inner="${CSS.escape(name)}"]`);
            if (part) part.open = true;
        });
        // Revealed once the menu has opened and been fitted on screen (browse-bar.js sizes it on the same event).
        const reveal = menu.querySelector('[data-reveal]');
        if (reveal) menu.addEventListener('toggle', () => reveal.scrollIntoView({ block: 'nearest' }), { once: true });
        menu.open = true;
        const option = key.option ? menu.querySelector(`a[data-option="${CSS.escape(key.option)}"]`) : null;
        const target = (visible(option) && option) || menu.querySelector(':scope > summary');
        if (target) target.focus({ preventScroll: true });
        return true;
    }

    function replayQueued(region) {
        if (!region.isConnected) { queued = []; return; }
        while (queued.length) {
            const next = queued.shift();
            const link = region.querySelector(
                `details[data-menu="${CSS.escape(next.menu)}"] a[data-keep-open][data-option="${CSS.escape(next.option)}"]`);
            if (!link) continue;
            link.click();
            if (inFlightKey) return; // A swap started; the rest waits for it.
        }
    }

    function focusAfter(key, region) {
        if (key.reopen && reopen(key, region)) return;
        const byName = (name) => (name ? region.querySelector(`[data-view-links="${CSS.escape(name)}"]`) : null);
        let target = key.menu ? region.querySelector(`details[data-menu="${CSS.escape(key.menu)}"] > summary`) : null;
        if (!target) target = inGroup(byName(key.group));
        if (!target) target = inGroup(byName(region.dataset.viewFallback));
        if (target) target.focus({ preventScroll: true });
    }

    // Stylesheets inside a region (the browse bar brings its own <link>) are kept loaded in <head>: a <link> swapped
    // in again is fetched again (static files are no-store) and the region showed unstyled for ~0.5 s each time.
    const headSheet = (href) => [...document.head.querySelectorAll('link[rel="stylesheet"]')].find((l) => l.href === href);
    function adoptStyles(root) {
        const pending = [];
        root.querySelectorAll('link[rel="stylesheet"][href]').forEach((link) => {
            let copy = headSheet(link.href);
            if (!copy) {
                copy = link.cloneNode();
                document.head.appendChild(copy);
            }
            if (!copy.sheet) {
                pending.push(new Promise((done) => {
                    copy.addEventListener('load', done, { once: true });
                    copy.addEventListener('error', done, { once: true });
                    setTimeout(done, 2000);
                }));
            }
        });
        return Promise.all(pending);
    }
    document.querySelectorAll('[data-view][id]').forEach(adoptStyles);

    function setBusy(region, busy) {
        if (busy) region.setAttribute('aria-busy', 'true'); else region.removeAttribute('aria-busy');
        region.classList.toggle('is-loading', busy);
    }

    async function show(region, url, key) {
        const id = region.id;
        const mine = ++seq;
        if (inFlight) inFlight.abort();
        const controller = window.AbortController ? new AbortController() : null;
        inFlight = controller;
        inFlightKey = key;
        setBusy(region, true);
        if (window.Loader) window.Loader.start();
        try {
            const response = await fetch(url, {
                credentials: 'same-origin',
                headers: { Accept: 'text/html' },
                signal: controller ? controller.signal : undefined,
            });
            const landed = new URL(response.url, window.location.href);
            if (!response.ok || landed.pathname !== window.location.pathname) throw new Error('not loaded');
            const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
            if (mine !== seq) return;
            const fresh = doc.getElementById(id);
            if (!fresh || !fresh.hasAttribute('data-view')) throw new Error('unexpected page');
            const node = document.importNode(fresh, true);
            await adoptStyles(node);
            if (mine !== seq) return;
            node.querySelectorAll('link[rel="stylesheet"][href]').forEach((link) => { if (headSheet(link.href)) link.remove(); });
            region.replaceWith(node);
            replaceUrl(url);
            if (window.lucide && window.lucide.createIcons) window.lucide.createIcons();
            document.dispatchEvent(new CustomEvent('view:swapped', { detail: { url, regions: [node] } }));
            focusAfter(key, node);
            if (!key.reopen) queued = [];
            else if (queued.length) setTimeout(() => replayQueued(node), 0);
        } catch (err) {
            if (err && err.name === 'AbortError') return;
            if (mine !== seq) return;
            window.location.assign(url);
        } finally {
            if (mine === seq) {
                inFlight = null;
                inFlightKey = null;
                if (region.isConnected) setBusy(region, false);
                if (window.Loader) window.Loader.stop();
            }
        }
    }

    document.addEventListener('click', (event) => {
        if (!event.target.closest || !plainClick(event)) return;
        const link = event.target.closest('a[href]');
        if (!link) return;
        const url = new URL(link.href, window.location.href);
        if (url.origin !== window.location.origin) return;

        if (link.hasAttribute('data-view-replace')) {
            event.preventDefault();
            if (url.href !== window.location.href) window.location.replace(url.href);
            return;
        }

        const region = link.closest('[data-view][id]');
        if (!region || !link.closest('[data-view-links]') || url.pathname !== window.location.pathname) return;
        event.preventDefault();
        const key = focusKey(link);
        if (key.reopen && inFlightKey && inFlightKey.reopen && inFlightKey.menu === key.menu && key.option) {
            queued.push({ menu: key.menu, option: key.option });
            return;
        }
        const menu = link.closest('details[data-menu]') || link.closest('details');
        if (menu && !key.reopen) menu.open = false;
        // The view already shown (a menu's active entry): nothing to load.
        if (url.pathname + url.search === window.location.pathname + window.location.search) return;
        queued = [];
        show(region, url.pathname + url.search, key);
    });
})();
