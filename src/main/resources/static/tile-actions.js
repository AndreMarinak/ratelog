// Fork: poster quick actions (templates/fragments/tile-actions.html). Without JS both are plain POST forms that redirect
// back with a flash message. With JS they post in the background (header `X-Requested-With: fetch`, JSON answer):
// - every stack of the same title on the page (`data-key="movie:603"`, the same key as home.js) takes the server's state,
//   so a title shown in two rows flips in both;
// - a short message shows (window.ratelogToast, also used by title-actions.js);
// - a `tile:changed` event bubbles from the tapped stack (list pages update "You've seen 9 of 25" with it).
// If anything goes wrong the form is submitted the normal way; on the home page the scroll position is saved under
// home.js's key first, so it comes back after the reload.
(() => {
    // --- Toast: Discover's own (#browseToast) when there is one, else a small one of our own ---
    let toastTimer = null;
    function toast(text) {
        if (!text) return;
        let el = document.getElementById('browseToast') || document.getElementById('tileToast');
        if (!el) {
            el = document.createElement('div');
            el.id = 'tileToast';
            el.className = 'tile-toast';
            el.setAttribute('role', 'status');
            el.setAttribute('aria-live', 'polite');
            document.body.appendChild(el);
        }
        el.textContent = text;
        el.classList.add('visible');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => el.classList.remove('visible'), 3200);
    }
    window.ratelogToast = window.ratelogToast || toast;

    if (!window.fetch || !window.URLSearchParams) return;

    const refreshIcons = () => { if (window.lucide) window.lucide.createIcons(); };
    const HOME_SCROLL_KEY = 'ratelog.home.scroll';

    function setIcon(button, name) {
        const icon = document.createElement('i');
        icon.setAttribute('data-lucide', name);
        icon.setAttribute('aria-hidden', 'true');
        button.replaceChildren(icon);
    }

    function label(button, text, title) {
        button.title = text;
        button.setAttribute('aria-label', title ? `${text}: ${title}` : text);
    }

    /** Brings one stack in line with the state the server answered. */
    function apply(stack, state) {
        const d = stack.dataset;
        const watch = stack.querySelector('.tile-watchlist');
        if (watch) {
            watch.hidden = !state.canToggleWatchlist;
            watch.classList.toggle('is-on', state.onWatchlist);
            const button = watch.querySelector('button');
            button.setAttribute('aria-pressed', state.onWatchlist ? 'true' : 'false');
            label(button, state.onWatchlist ? d.labelRemove : d.labelAdd, d.title);
            setIcon(button, state.onWatchlist ? 'bookmark-check' : 'bookmark');
        }
        const badge = stack.querySelector('.tile-badge');
        if (badge && state.status !== 'watching' && state.status !== 'dropped') badge.remove();

        const seen = stack.querySelector('.tile-seen');
        if (seen) {
            seen.classList.toggle('is-on', state.seen);
            seen.classList.toggle('is-locked', state.seenLocked);
            const button = seen.querySelector('button');
            button.setAttribute('aria-pressed', state.seen ? 'true' : 'false');
            label(button, state.seenLocked ? d.labelSeenLocked : (state.seen ? d.labelSeenOn : d.labelSeenOff), d.title);
        }
        const item = stack.closest('.list-item');
        if (item) item.classList.toggle('is-seen', state.seen);
    }

    function stacksFor(stack) {
        const key = stack.dataset.key;
        return key ? document.querySelectorAll(`[data-tile-actions][data-key="${CSS.escape(key)}"]`) : [stack];
    }

    // Fork (tile rating): tile-rate.js brings every copy of a title in line after a rating, the same way.
    window.ratelogTileActions = {
        apply(key, state) {
            document.querySelectorAll(`[data-tile-actions][data-key="${CSS.escape(key)}"]`).forEach((s) => apply(s, state));
            refreshIcons();
        },
    };

    function saveHomeScroll(form) {
        const row = form.closest('.home-row');
        if (!row || !form.closest('.home-dashboard')) return;
        const track = row.querySelector('.home-row-track');
        try {
            sessionStorage.setItem(HOME_SCROLL_KEY, JSON.stringify({ y: window.scrollY, row: row.id || null, x: track ? track.scrollLeft : 0 }));
        } catch (err) { /* storage unavailable: nothing to restore */ }
    }

    // ---------- Touch: one "⋯" button per poster opens a chooser (fork, tile menu) ----------
    // tile-actions.css shows "⋯" only on touch screens (hover: none) and hides the four buttons there. The chooser
    // (template #tileMenu, fragments/tile-rate.html) reads the poster's state from those hidden controls when it
    // opens, and each row hands over to one of them: the bookmark / eye forms are submitted (so the handler below
    // saves them in place), ★ and the list button are clicked (tile-rate.js opens the picker / the list sheet, a
    // show's ★ goes to its seasons). Tapping the Seen or score mark on a poster opens the chooser too.
    const shown = (el) => !!el && el.getClientRects().length > 0;
    const sheetQuery = window.matchMedia('(max-width: 600px)');
    let menu = null;
    let menuBackdrop = null;
    let menuFor = null; // { stack, button }
    let handingOver = false; // our own clicks on the hidden controls

    function buildMenu() {
        const t = document.getElementById('tileMenu');
        if (!t) return false;
        menu = t.content.firstElementChild.cloneNode(true);
        menuBackdrop = document.createElement('div');
        menuBackdrop.className = 'tile-menu-backdrop';
        menuBackdrop.hidden = true;
        document.body.append(menuBackdrop, menu);
        return true;
    }

    function icon(name) {
        const i = document.createElement('i');
        i.setAttribute('data-lucide', name);
        i.setAttribute('aria-hidden', 'true');
        return i;
    }

    /** One row: hidden when the poster doesn't offer it, else its icon, words, the end mark and the on state. */
    function row(act, cfg) {
        const el = menu.querySelector(`[data-menu-act="${act}"]`);
        el.hidden = !cfg;
        if (!cfg) return;
        el.classList.toggle('is-on', !!cfg.on);
        if (cfg.pressed == null) el.removeAttribute('aria-pressed');
        else el.setAttribute('aria-pressed', cfg.pressed ? 'true' : 'false');
        el.querySelector('[data-menu-icon]').replaceChildren(icon(cfg.icon));
        el.querySelector('[data-menu-label]').textContent = cfg.label || '';
        el.querySelector('[data-menu-hint]').textContent = cfg.hint || '';
        const end = el.querySelector('[data-menu-end]');
        end.replaceChildren(...(cfg.end || []));
    }

    function scoreEnd(text) {
        const score = document.createElement('span');
        score.className = 'tile-menu-score';
        score.textContent = text;
        return [icon('star'), score];
    }

    function fillMenu(stack) {
        const d = document.getElementById('tileMenu').dataset;
        const title = stack.dataset.title || '';
        // The watchlist page has its own Seen / Watching / Remove buttons under each poster.
        const ownButtons = !!stack.closest('.watchlist-item');
        menu.setAttribute('aria-label', (d.labelAria || '{0}').replace('{0}', title));
        menu.querySelector('[data-menu-title]').textContent = title;
        const badge = stack.querySelector('.tile-badge');
        const status = menu.querySelector('[data-menu-status]');
        status.hidden = !badge || ownButtons;
        status.textContent = badge ? badge.getAttribute('aria-label') || '' : '';

        const watch = stack.querySelector('form.tile-watchlist');
        const watchOn = !!watch && watch.classList.contains('is-on');
        row('watchlist', !ownButtons && watch && !watch.hidden ? {
            on: watchOn, pressed: watchOn, icon: watchOn ? 'bookmark-check' : 'bookmark',
            label: watchOn ? d.labelWatchOn : d.labelWatchAdd, hint: watchOn ? d.labelWatchOnHint : '',
            end: watchOn ? [icon('check')] : [],
        } : null);

        const seen = stack.querySelector('form.tile-seen');
        const seenOn = !!seen && seen.classList.contains('is-on');
        const locked = !!seen && seen.classList.contains('is-locked');
        row('seen', !ownButtons && seen ? {
            on: seenOn, pressed: seenOn, icon: 'eye',
            label: seenOn ? d.labelSeenOn : d.labelSeenMark,
            hint: locked ? d.labelSeenLockedHint : (seenOn ? d.labelSeenOnHint : ''),
            end: seenOn ? [icon('check')] : [],
        } : null);

        const star = stack.querySelector('a.tile-rate');
        if (star) {
            const rated = star.classList.contains('is-on');
            const scoreEl = star.querySelector('.tile-rate-score');
            const score = rated && scoreEl ? scoreEl.textContent.trim() : '';
            const movie = star.hasAttribute('data-tile-rate');
            const detailed = star.dataset.detailed === 'true';
            let label = d.labelRate;
            let hint = d.labelRateHint;
            if (!movie) { label = d.labelRateSeasons; hint = d.labelRateSeasonsHint; } else if (detailed) { label = d.labelRateDetailed; hint = d.labelRateDetailedHint; } else if (rated) { label = d.labelRateYours; hint = d.labelRateYoursHint; }
            const end = score ? scoreEnd(score) : [];
            if (!movie || !score) end.push(icon('chevron-right'));
            row('rate', { on: rated, icon: 'star', label, hint, end });
        } else {
            row('rate', null);
        }

        const lists = stack.querySelector('a.tile-list');
        const listed = !!lists && lists.classList.contains('is-on');
        row('lists', lists ? {
            on: listed, icon: listed ? 'list-checks' : 'list-plus', label: listed ? d.labelListsIn : d.labelListsAdd,
            hint: listed ? d.labelListsInHint : '', end: [icon('chevron-right')],
        } : null);
        refreshIcons();
    }

    function positionMenu(button) {
        const sheet = sheetQuery.matches;
        menu.classList.toggle('is-sheet', sheet);
        menuBackdrop.hidden = !sheet;
        if (sheet) {
            menu.style.left = '';
            menu.style.top = '';
            return;
        }
        const r = button.getBoundingClientRect();
        const width = menu.offsetWidth;
        const height = menu.offsetHeight;
        const viewport = document.documentElement.clientWidth;
        const left = Math.max(8, Math.min(r.right - width, viewport - width - 8));
        let top = r.bottom + 6;
        if (top + height > window.innerHeight - 8 && r.top - height - 6 > 8) top = r.top - height - 6;
        menu.style.left = `${left + window.scrollX}px`;
        menu.style.top = `${top + window.scrollY}px`;
    }

    function openMenu(button) {
        const stack = button.closest('[data-tile-actions]');
        if (!stack || (!menu && !buildMenu())) return false;
        if (menuFor) closeMenu(false);
        menuFor = { stack, button };
        fillMenu(stack);
        menu.style.visibility = 'hidden';
        menu.hidden = false;
        positionMenu(button);
        menu.style.visibility = '';
        button.setAttribute('aria-expanded', 'true');
        const first = [...menu.querySelectorAll('[data-menu-act]')].find((el) => !el.hidden);
        if (first) first.focus({ preventScroll: true });
        return true;
    }

    function closeMenu(restoreFocus = true) {
        if (!menuFor) return;
        const { button } = menuFor;
        menu.hidden = true;
        menuBackdrop.hidden = true;
        button.setAttribute('aria-expanded', 'false');
        menuFor = null;
        if (restoreFocus && button.isConnected) button.focus({ preventScroll: true });
    }

    function handOver(act) {
        const { stack } = menuFor;
        closeMenu();
        handingOver = true;
        try {
            if (act === 'watchlist' || act === 'seen') {
                const form = stack.querySelector(act === 'seen' ? 'form.tile-seen' : 'form.tile-watchlist');
                if (!form) return;
                if (typeof form.requestSubmit === 'function') form.requestSubmit();
                else form.querySelector('button').click();
            } else {
                const control = stack.querySelector(act === 'rate' ? 'a.tile-rate' : 'a.tile-list');
                if (control) control.click();
            }
        } finally {
            handingOver = false;
        }
    }

    // Capture phase: a tap on a mark must open the chooser before tile-rate.js (★) or the form (eye) act on it.
    document.addEventListener('click', (event) => {
        if (handingOver || !event.target.closest) return;
        const target = event.target;
        const button = target.closest('[data-tile-menu]');
        if (button) {
            event.preventDefault();
            if (menuFor && menuFor.button === button) closeMenu();
            else openMenu(button);
            return;
        }
        const mark = target.closest('[data-tile-actions] .tile-seen button, [data-tile-actions] a.tile-rate');
        if (mark && !mark.closest('.is-ticking')) {
            const opener = mark.closest('[data-tile-actions]').querySelector('[data-tile-menu]');
            if (shown(opener)) {
                event.preventDefault();
                event.stopPropagation();
                openMenu(opener);
                return;
            }
        }
        if (!menuFor) return;
        if (target === menuBackdrop || target.closest('[data-menu-close]')) {
            event.preventDefault();
            closeMenu();
            return;
        }
        const item = target.closest('[data-menu-act]');
        if (item && menu.contains(item)) {
            event.preventDefault();
            handOver(item.dataset.menuAct);
        }
    }, true);

    // A tap or click anywhere else closes it (the phone backdrop closes it on its own click, above, so the tap
    // doesn't land on the poster under it).
    document.addEventListener('pointerdown', (event) => {
        if (!menuFor || menu.contains(event.target) || event.target === menuBackdrop) return;
        if (menuFor.button.contains(event.target)) return;
        closeMenu(false);
    }, true);

    document.addEventListener('keydown', (event) => {
        if (!menuFor) return;
        if (event.key === 'Escape') {
            event.preventDefault();
            closeMenu();
        } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            const items = [...menu.querySelectorAll('[data-menu-act]')].filter((el) => !el.hidden);
            const at = items.indexOf(document.activeElement);
            if (at < 0 || !items.length) return;
            event.preventDefault();
            items[(at + (event.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length].focus();
        }
    });

    // A row scrolled sideways takes the button away from the popover (the sheet doesn't mind).
    document.addEventListener('scroll', (event) => {
        if (!menuFor || sheetQuery.matches) return;
        if (event.target !== document && event.target.contains && event.target.contains(menuFor.button)) closeMenu(false);
    }, true);

    window.addEventListener('resize', () => {
        if (menuFor) positionMenu(menuFor.button);
    });

    document.addEventListener('submit', async (event) => {
        const form = event.target.closest && event.target.closest('form[data-tile-act]');
        // confirm.js (capture phase) may have cancelled it already.
        if (!form || event.defaultPrevented) return;
        const stack = form.closest('[data-tile-actions]');
        if (!stack) return;
        event.preventDefault();

        const button = form.querySelector('button');
        if (button.getAttribute('aria-busy') === 'true') return;
        button.setAttribute('aria-busy', 'true');
        try {
            const response = await fetch(form.action, {
                method: 'POST',
                body: new URLSearchParams(new FormData(form)),
                credentials: 'same-origin',
                headers: { 'X-Requested-With': 'fetch', Accept: 'application/json' },
            });
            const type = response.headers.get('content-type') || '';
            // Logged out (redirected to the login page), a server error...: let the browser do it the normal way.
            if (!type.includes('application/json') || response.status >= 500) throw new Error('not json');
            const state = await response.json();
            if (!response.ok && !state.message) throw new Error('failed');
            if (state.ok) {
                stacksFor(stack).forEach((s) => apply(s, state));
                refreshIcons();
            }
            toast(state.message);
            stack.dispatchEvent(new CustomEvent('tile:changed', { bubbles: true, detail: { act: form.dataset.tileAct, state } }));
        } catch (error) {
            saveHomeScroll(form);
            form.submit();
        } finally {
            button.removeAttribute('aria-busy');
        }
    });
})();
