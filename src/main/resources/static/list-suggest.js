// Fork: "Suggested for this list" under your own list (templates/fragments/list-suggest.html).
// - The page renders a skeleton; the row is fetched from its data-src (/list/{id}/suggestions) once it comes within
//   600px of the viewport, so the list page never waits on TMDB. Nothing to show, or any error: the skeleton goes.
// - "More suggestions" fetches another batch, sending every title this visit has shown (and the ones you said "Not
//   interested" to) in `skip`, and swaps the row. "Start over" forgets both.
// - "+ Add" posts in place (X-Requested-With: fetch, JSON answer): the tile turns "Added" and stays where it is, a
//   toast confirms, and the list above (#listRegion: grid, ranks, progress, tabs) is fetched again and swapped in,
//   with the new title's poster flashed. No JS or any error: the plain form post adds it and comes back to the list.
// - "Not interested" takes the tile out and keeps it out of this list's suggestions for the session (sessionStorage).
// - Seen / Watchlist on a suggestion are tile-actions.js's. Their `tile:changed` event is stopped here, so the list's
//   "You've seen 9 of 25" (list-tick.js) only counts titles that are in the list (an added one is).
(() => {
    const placeholder = document.querySelector('.list-suggest.is-pending[data-src]');
    if (!placeholder) return;
    if (!window.fetch || !window.DOMParser || !window.URLSearchParams) {
        placeholder.remove();
        return;
    }

    const src = placeholder.dataset.src;
    const HIDDEN_KEY = `ratelog.listSuggest.hidden.${placeholder.dataset.listId}`;
    const MAX_SKIP = 200;
    const reducedMotion = () => window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const refreshIcons = () => { if (window.lucide) window.lucide.createIcons(); };
    const toast = (text) => { if (text && window.ratelogToast) window.ratelogToast(text); };

    let section = placeholder;
    /** Keys (`m862`) shown during this visit, oldest first. */
    let shown = [];
    let listRefresh = Promise.resolve();

    // --- "Not interested", kept for the session (storage can be missing or full: then only this visit forgets) ---

    function readHidden() {
        try {
            const value = JSON.parse(sessionStorage.getItem(HIDDEN_KEY) || '[]');
            return Array.isArray(value) ? value.filter((key) => typeof key === 'string') : [];
        } catch (error) {
            return [];
        }
    }

    function writeHidden(keys) {
        try {
            if (keys.length) sessionStorage.setItem(HIDDEN_KEY, JSON.stringify(keys.slice(-MAX_SKIP)));
            else sessionStorage.removeItem(HIDDEN_KEY);
        } catch (error) { /* storage unavailable */ }
    }

    // --- Fetching the row ---

    async function fetchRow(includeShown) {
        const url = new URL(src, window.location.href);
        const skip = [...new Set([...readHidden(), ...(includeShown ? shown : [])])].slice(-MAX_SKIP);
        if (skip.length) url.searchParams.set('skip', skip.join('.'));
        const response = await fetch(url, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
        const landed = new URL(response.url, window.location.href);
        if (!response.ok || landed.pathname === '/login') throw new Error('row failed');
        // Nothing to show (or not your list): no [data-list-suggest] in the answer.
        return new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('[data-list-suggest]');
    }

    function remember(root) {
        root.querySelectorAll('[data-suggest-key]').forEach((tile) => {
            const key = tile.dataset.suggestKey;
            if (key && !shown.includes(key)) shown.push(key);
        });
        if (shown.length > MAX_SKIP) shown = shown.slice(-MAX_SKIP);
    }

    function updateArrows() {
        const row = section.querySelector('.home-row');
        const track = section.querySelector('[data-suggest-track]');
        const prev = section.querySelector('[data-row-prev]');
        const next = section.querySelector('[data-row-next]');
        if (!row || !track || !prev || !next) return;
        const max = track.scrollWidth - track.clientWidth;
        row.classList.toggle('is-scrollable', max > 4);
        prev.disabled = track.scrollLeft <= 4;
        next.disabled = track.scrollLeft >= max - 4;
    }

    function setupRow() {
        const track = section.querySelector('[data-suggest-track]');
        if (!track) return;
        const page = (direction) => {
            track.scrollBy({ left: direction * Math.max(track.clientWidth * 0.85, 160), behavior: reducedMotion() ? 'auto' : 'smooth' });
        };
        section.querySelector('[data-row-prev]')?.addEventListener('click', () => page(-1));
        section.querySelector('[data-row-next]')?.addEventListener('click', () => page(1));
        track.addEventListener('scroll', updateArrows, { passive: true });
        updateArrows();
    }
    window.addEventListener('resize', updateArrows);

    function show(fresh) {
        const next = document.importNode(fresh, true);
        section.replaceWith(next);
        section = next;
        refreshIcons();
        setupRow();
        remember(section);
    }

    async function load() {
        try {
            const fresh = await fetchRow(false);
            if (!fresh) {
                placeholder.remove();
                return;
            }
            show(fresh);
        } catch (error) {
            placeholder.remove();
        }
    }

    /** "More suggestions" (includeShown) and "Start over". The button that asked keeps the focus (its new copy). */
    async function reload(button, includeShown) {
        if (section.classList.contains('is-loading')) return;
        section.classList.add('is-loading');
        section.setAttribute('aria-busy', 'true');
        if (button) button.setAttribute('aria-busy', 'true');
        const hadFocus = button && document.activeElement === button;
        try {
            const fresh = await fetchRow(includeShown);
            if (!fresh) {
                section.remove();
                return;
            }
            show(fresh);
            if (hadFocus) {
                const again = section.querySelector('[data-suggest-more], [data-suggest-restart]');
                (again || section.querySelector('[data-suggest-track]'))?.focus({ preventScroll: true });
            }
        } catch (error) {
            section.classList.remove('is-loading');
            section.removeAttribute('aria-busy');
            if (button) button.removeAttribute('aria-busy');
            toast(section.dataset.labelError);
        }
    }

    // --- "Not interested" ---

    function dismiss(button) {
        const tile = button.closest('.list-suggest-tile');
        if (!tile) return;
        const key = tile.dataset.suggestKey;
        const hidden = readHidden();
        if (key && !hidden.includes(key)) {
            hidden.push(key);
            writeHidden(hidden);
        }
        // Keep the keyboard where it was: the next tile's link, else the previous one's.
        const neighbour = tile.nextElementSibling || tile.previousElementSibling;
        const hadFocus = tile.contains(document.activeElement);
        const done = () => {
            tile.remove();
            if (hadFocus && neighbour) neighbour.querySelector('a.home-tile')?.focus({ preventScroll: true });
            if (!section.querySelector('.list-suggest-tile')) reload(null, true);
            else updateArrows();
        };
        if (reducedMotion()) done();
        else {
            tile.classList.add('is-leaving');
            setTimeout(done, 180);
        }
    }

    // --- "+ Add" ---

    function markAdded(tile, button) {
        tile.classList.add('is-added');
        const name = tile.querySelector('.home-tile-title')?.textContent.trim() || '';
        const text = section.dataset.labelAdded || 'Added';
        const icon = document.createElement('i');
        icon.setAttribute('data-lucide', 'check');
        icon.setAttribute('aria-hidden', 'true');
        const label = document.createElement('span');
        label.textContent = text;
        button.replaceChildren(icon, label);
        button.setAttribute('aria-disabled', 'true');
        button.setAttribute('aria-label', name ? `${text}: ${name}` : text);
        refreshIcons();
    }

    /** Swaps the list above (grid, ranks, progress, tabs) for a fresh copy of this same view, keeping the row in place. */
    async function refreshList(itemId) {
        const region = document.getElementById('listRegion');
        if (!region) return;
        const response = await fetch(window.location.href, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
        const landed = new URL(response.url, window.location.href);
        if (!response.ok || landed.pathname === '/login') throw new Error('list failed');
        const fresh = new DOMParser().parseFromString(await response.text(), 'text/html').getElementById('listRegion');
        if (!fresh) throw new Error('no list');
        const before = section.getBoundingClientRect().top;
        document.getElementById('listRegion').replaceWith(document.importNode(fresh, true));
        refreshIcons();
        // The grid may have grown a row: keep the suggestions where they were on screen.
        const shift = section.getBoundingClientRect().top - before;
        if (Math.abs(shift) > 1) window.scrollBy(0, shift);
        const item = itemId != null ? document.getElementById(`list-item-${itemId}`) : null;
        if (item) {
            item.classList.add('is-just-added');
            setTimeout(() => item.classList.remove('is-just-added'), 2000);
        }
    }

    async function add(form) {
        const tile = form.closest('.list-suggest-tile');
        const button = form.querySelector('button');
        if (!tile || !button || tile.classList.contains('is-added') || button.getAttribute('aria-busy') === 'true') return;
        button.setAttribute('aria-busy', 'true');
        let state;
        try {
            const response = await fetch(form.action, {
                method: 'POST',
                body: new URLSearchParams(new FormData(form)),
                credentials: 'same-origin',
                headers: { 'X-Requested-With': 'fetch', Accept: 'application/json' },
            });
            // Logged out (the login page) or a server error page: let the browser post it the normal way.
            if (!(response.headers.get('content-type') || '').includes('application/json')) throw new Error('not json');
            state = await response.json();
        } catch (error) {
            button.removeAttribute('aria-busy');
            form.submit();
            return;
        }
        button.removeAttribute('aria-busy');
        if (!state.ok) {
            toast(state.message);
            return;
        }
        markAdded(tile, button);
        toast(state.message);
        // One refresh at a time, in the order the titles were added.
        listRefresh = listRefresh
            .then(() => refreshList(state.itemId))
            .catch(() => window.location.reload());
    }

    // --- Events (delegated: the row is swapped on every batch) ---

    document.addEventListener('submit', (event) => {
        const form = event.target.closest && event.target.closest('form[data-suggest-add]');
        if (!form || event.defaultPrevented) return;
        event.preventDefault();
        add(form);
    });

    document.addEventListener('click', (event) => {
        const target = event.target.closest && event.target.closest('[data-suggest-more], [data-suggest-restart], [data-suggest-dismiss]');
        if (!target || !target.closest('[data-list-suggest]')) return;
        event.preventDefault();
        if (target.matches('[data-suggest-dismiss]')) {
            dismiss(target);
        } else if (target.matches('[data-suggest-restart]')) {
            shown = [];
            writeHidden([]);
            reload(target, false);
        } else {
            reload(target, true);
        }
    });

    // A suggestion's Seen toggle isn't a list title's (until it is added): keep it out of the list's counts.
    document.addEventListener('tile:changed', (event) => {
        const tile = event.target.closest && event.target.closest('.list-suggest-tile');
        if (tile && !tile.classList.contains('is-added')) event.stopPropagation();
    }, true);

    if ('IntersectionObserver' in window) {
        const observer = new IntersectionObserver((entries) => {
            if (!entries.some((entry) => entry.isIntersecting)) return;
            observer.disconnect();
            load();
        }, { rootMargin: '600px 0px' });
        observer.observe(placeholder);
    } else {
        load();
    }
})();
