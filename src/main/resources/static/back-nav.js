// Fork: back links on title pages (fragments/back-nav.html, back-nav.css; docs/design-decisions.md "Back links on title pages").
// Runs on every page (included from fragments/nav.html) and remembers, per browser tab (sessionStorage), the page you
// are on, so a title page can link back to where you came from:
// - A named page (data-label on this script: Discover, Home, Search results, a list, a profile...) is an "origin".
//   Its record keeps its URL as it is when you leave (a list tab switched in place, Discover filters), the scroll
//   position, and the row you scrolled sideways if you opened a title from a row.
// - A title page links back to its origin. A title opened from another title keeps that title's origin, so the link
//   never walks back through a chain of titles; the title right before is offered as one extra step
//   ("← Discover › Toy Story 2"), never more. Each history entry keeps its own origin (history.state), so Back and
//   Forward show the right link.
// - Going back: when the origin is the previous entry in this tab's history, history.back() (the browser restores the
//   page, all of it when it kept it in its back/forward cache); otherwise the link opens the origin's URL and this
//   script scrolls it back to where you were.
// Without JS, or without storage, the link stays the server's: the page in the Referer, else Home.
(() => {
    const LAST = 'ratelog.backnav.last';
    const RESTORE = 'ratelog.backnav.restore';
    const RESTORE_MAX_AGE_MS = 60 * 1000;

    const read = (key) => {
        try { return JSON.parse(sessionStorage.getItem(key) || 'null'); } catch (err) { return null; }
    };
    const write = (key, value) => {
        try { sessionStorage.setItem(key, JSON.stringify(value)); } catch (err) { /* storage unavailable: server links only */ }
    };
    const remove = (key) => {
        try { sessionStorage.removeItem(key); } catch (err) { /* nothing to clean */ }
    };

    const script = document.querySelector('script[data-back-nav-page]');
    const nav = document.querySelector('[data-back-nav]');
    const kind = nav ? 'title' : (script && script.dataset.label ? 'page' : 'other');
    const label = script && script.dataset.label ? script.dataset.label : '';

    const sameOrigin = (url) => {
        try { return new URL(url, location.href).origin === location.origin; } catch (err) { return false; }
    };
    const withoutHash = (url) => {
        try { const u = new URL(url, location.href); u.hash = ''; return u.href; } catch (err) { return ''; }
    };
    const navigationType = () => {
        try {
            const entry = performance.getEntriesByType('navigation')[0];
            if (entry && entry.type) return entry.type;
        } catch (err) { /* old browser */ }
        return 'navigate';
    };

    // --- This history entry: an id (and, on title pages, its back context) kept in history.state ---

    const ownState = () => {
        const state = history.state;
        return state && typeof state === 'object' && state.backNav && state.backNav.id ? state.backNav : null;
    };
    const saveState = (data) => {
        const state = history.state;
        if (state != null && typeof state !== 'object') return; // someone else's non-object state: leave it alone
        try { history.replaceState(Object.assign({}, state, { backNav: data }), ''); } catch (err) { /* ignore */ }
    };
    const newId = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    // An entry pushed in place (list tabs) has no id yet: give it one.
    const entryId = () => {
        const own = ownState();
        if (own) return own.id;
        const id = newId();
        saveState({ id });
        return id;
    };

    // --- Where you are: the record the next page reads ---

    let clickedRow = null; // { row, x } of the row a link was clicked in

    function record() {
        const base = { kind, id: entryId(), url: location.href, y: Math.round(window.scrollY), t: Date.now() };
        if (clickedRow) Object.assign(base, clickedRow);
        if (kind === 'page') base.label = label;
        if (kind === 'title') {
            base.key = location.pathname;
            base.title = nav.dataset.title || document.title;
            base.origin = context ? context.origin : null;
            base.prev = context ? context.prev : null;
        }
        return base;
    }
    const remember = () => write(LAST, record());

    // --- Title pages: work out where "back" goes ---

    let context = null; // { id, origin, direct, prev, prevDirect }

    function originOf(entry) {
        if (!entry || entry.kind !== 'page' || !entry.label || !sameOrigin(entry.url)) return null;
        return { id: entry.id, url: entry.url, label: entry.label, y: entry.y || 0, row: entry.row || null, x: entry.x || 0 };
    }

    function buildContext() {
        const saved = ownState();
        const last = read(LAST);
        if (saved && saved.origin !== undefined) {
            // This entry again (reload, Back / Forward): its own origin, refreshed if you just came from that page.
            const fresher = originOf(last);
            if (saved.origin && fresher && fresher.id === saved.origin.id) saved.origin = fresher;
            return saved;
        }
        const id = saved ? saved.id : newId();
        const fresh = navigationType() === 'navigate' && history.length > 1;
        if (last && last.kind === 'page') {
            return { id, origin: originOf(last), direct: fresh && !!originOf(last), prev: null, prevDirect: false };
        }
        if (last && last.kind === 'title') {
            const origin = last.origin && sameOrigin(last.origin.url) ? last.origin : null;
            if (last.key === location.pathname) {
                // The same title again (after a save): keep its links, but history.back() would only reach itself.
                return { id, origin, direct: false, prev: last.prev || null, prevDirect: false };
            }
            const prev = sameOrigin(last.url)
                ? { id: last.id, url: last.url, title: last.title, y: last.y || 0, row: last.row || null, x: last.x || 0 }
                : null;
            return { id, origin, direct: false, prev, prevDirect: fresh && !!prev };
        }
        return { id, origin: null, direct: false, prev: null, prevDirect: false };
    }

    function fill(link, text, url) {
        link.href = url;
        link.querySelector('[data-back-text]').textContent = text;
        const to = nav.dataset.to || '{0}';
        link.setAttribute('aria-label', to.replace('{0}', text));
    }

    function restoreLater(target) {
        write(RESTORE, { url: target.url, y: target.y || 0, row: target.row || null, x: target.x || 0, t: Date.now() });
    }

    // history.back() when the target is the previous entry, else open its URL (and scroll it back into place).
    function go(target, direct) {
        const stillThisEntry = (ownState() || {}).id === context.id;
        if (!direct || !stillThisEntry) {
            restoreLater(target);
            location.assign(target.url);
            return;
        }
        // Safety net: if history.back() goes nowhere (or only within this page), open the URL instead.
        let leaving = false;
        const onLeave = () => { leaving = true; };
        window.addEventListener('beforeunload', onLeave, { once: true });
        window.addEventListener('pagehide', onLeave, { once: true });
        const fallback = () => {
            if (leaving) return;
            restoreLater(target);
            location.assign(target.url);
        };
        window.addEventListener('popstate', fallback, { once: true });
        history.back();
        setTimeout(fallback, 1500);
    }

    const plainClick = (event) =>
        !event.defaultPrevented && event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;

    function setUpTitle() {
        try {
            context = buildContext();
            showLinks();
        } finally {
            nav.removeAttribute('data-pending');
        }
    }

    function showLinks() {
        saveState(context);
        const originLink = nav.querySelector('[data-back-origin]');
        const step = nav.querySelector('[data-back-step]');
        const prevLink = nav.querySelector('[data-back-prev]');

        if (context.origin && originLink) fill(originLink, context.origin.label, context.origin.url);
        if (context.prev && step && prevLink) {
            fill(prevLink, context.prev.title, context.prev.url);
            step.hidden = false;
            nav.classList.add('has-step');
        }

        if (originLink) {
            originLink.addEventListener('click', (event) => {
                if (!context.origin || !plainClick(event)) return;
                event.preventDefault();
                go(context.origin, context.direct);
            });
        }
        if (prevLink) {
            prevLink.addEventListener('click', (event) => {
                if (!context.prev || !plainClick(event)) return;
                event.preventDefault();
                go(context.prev, context.prevDirect);
            });
        }
    }

    // --- Coming back: scroll to where you were (and the row you had scrolled sideways) ---

    function restore(target) {
        let touched = false;
        const stop = () => { touched = true; };
        ['wheel', 'touchstart', 'mousedown', 'keydown'].forEach((type) =>
            window.addEventListener(type, stop, { passive: true, once: true }));
        const apply = () => {
            if (touched) return;
            if (target.row) {
                const track = document.querySelector('#' + CSS.escape(target.row) + ':not(.is-pending) .home-row-track');
                if (track && Math.abs(track.scrollLeft - target.x) > 2) {
                    track.style.scrollBehavior = 'auto';
                    track.scrollLeft = target.x;
                    track.style.scrollBehavior = '';
                }
            }
            if (typeof target.y === 'number' && Math.abs(window.scrollY - target.y) > 2) {
                window.scrollTo({ top: target.y, left: 0, behavior: 'instant' });
            }
        };
        apply();
        if (document.readyState !== 'complete') window.addEventListener('load', apply, { once: true });
        // Rows and posters below may still be loading; stop as soon as you scroll yourself.
        [300, 900, 2000].forEach((ms) => setTimeout(apply, ms));
    }

    function restoreIfAsked() {
        const want = read(RESTORE);
        if (want) {
            remove(RESTORE);
            if (Date.now() - (want.t || 0) < RESTORE_MAX_AGE_MS && withoutHash(want.url) === withoutHash(location.href)) {
                restore(want);
                return;
            }
        }
        // Back / Forward from a title to its origin (or the title before it): the browser restores the page's scroll
        // (not always: entries a list tab pushed in place came back at the top), never a row's; put both back.
        if (navigationType() === 'back_forward') {
            const last = read(LAST);
            const id = entryId();
            const mine = last && last.kind === 'title'
                ? [last.origin, last.prev].find((entry) => entry && entry.id === id)
                : null;
            if (mine) restore({ row: mine.row || null, x: mine.x || 0, y: mine.y || 0 });
        }
    }

    // --- Wire up ---

    if (kind === 'title') setUpTitle();
    if (kind !== 'other') restoreIfAsked();
    remember();

    if (kind === 'other') return;

    // Keep the record current: right before a link or form leaves the page, on scroll, and when the page is hidden.
    document.addEventListener('click', (event) => {
        const link = event.target.closest && event.target.closest('a[href]');
        if (!link) return;
        const row = link.closest('.home-row[id]');
        const track = row && row.querySelector('.home-row-track');
        clickedRow = row && track ? { row: row.id, x: Math.round(track.scrollLeft) } : null;
        remember();
    }, true);
    document.addEventListener('submit', remember, true);
    let scrollTimer = 0;
    window.addEventListener('scroll', () => {
        clearTimeout(scrollTimer);
        scrollTimer = setTimeout(remember, 150);
    }, { passive: true });
    window.addEventListener('pagehide', remember);
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') remember(); });
    // Shown again from the back/forward cache: this is the current page again.
    window.addEventListener('pageshow', (event) => { if (event.persisted) remember(); });
    window.addEventListener('popstate', remember);
})();
