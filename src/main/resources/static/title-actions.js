// Fork: title page actions (templates/fragments/title-actions.html). Seen, Watchlist and a show's Watching / Dropped
// save in the background (header `X-Requested-With: fetch`, JSON answer) and every toggle is updated from the server's
// state, so the page never reloads. After Seen on a movie with no rating, a small "Rate it?" offers a quick score
// (optional; Skip closes it). Without JS these are plain POST forms that come back to the page, with no prompt.
// The panel is looked up on every event: the bell (inplace.js) swaps it for a fresh copy.
// Fork (2026-11-24, rewatch log): on a seen title the Seen button opens a small menu instead of un-seeing at once
// (user: "maybe be able to click the same button instead of another one?"): Watched again, then Mark as not seen (or,
// on a rated movie, why it stays seen). Built from the panel's <template data-seen-menu> each time it opens, so it
// always matches the panel; a popover by the button, a bottom sheet at 600px and below (the poster chooser's styles).
(() => {
    if (!window.fetch || !window.URLSearchParams) return;

    const toast = (text) => { if (window.ratelogToast) window.ratelogToast(text); };
    const panelOf = () => document.querySelector('.title-panel');
    const nudgeOf = () => document.querySelector('.title-panel [data-rate-nudge]');

    function setIcon(button, name) {
        const old = button.querySelector('svg, i');
        const icon = document.createElement('i');
        icon.setAttribute('data-lucide', name);
        icon.setAttribute('aria-hidden', 'true');
        if (old) old.replaceWith(icon); else button.prepend(icon);
    }

    function setLabel(button, on) {
        const label = button.querySelector('.title-toggle-label');
        if (label) label.textContent = on ? button.dataset.labelOn : button.dataset.labelOff;
    }

    /** Brings every toggle on the page in line with the state the server answered. */
    function apply(state) {
        const panel = panelOf();
        if (!panel) return;
        const nudge = nudgeOf();
        const seen = panel.querySelector('[data-title-seen]');
        if (seen) {
            seen.classList.toggle('is-on', state.seen);
            seen.classList.toggle('is-locked', state.seenLocked);
            seen.setAttribute('aria-pressed', state.seen ? 'true' : 'false');
            seen.title = state.seenLocked ? seen.dataset.titleLocked : (state.seen ? seen.dataset.titleOn : seen.dataset.titleOff);
            setLabel(seen, state.seen);
            const note = seen.querySelector('.title-toggle-note');
            if (note) note.hidden = !state.seenLocked;
        }

        const watch = panel.querySelector('[data-title-watchlist]');
        if (watch) {
            const form = watch.closest('form');
            watch.classList.toggle('is-on', state.onWatchlist);
            watch.setAttribute('aria-pressed', state.onWatchlist ? 'true' : 'false');
            watch.title = state.onWatchlist ? watch.dataset.titleOn : watch.dataset.titleOff;
            setLabel(watch, state.onWatchlist);
            setIcon(watch, state.onWatchlist ? 'bookmark-check' : 'bookmark');
            // Fork (2026-11-24): independent of Seen (POST /watchlist/set), so no confirm: a seen title stays seen.
            form.querySelector('input[name="on"]').value = state.onWatchlist ? '0' : '1';
        }

        panel.querySelectorAll('[data-progress-value]').forEach((button) => {
            const value = button.dataset.progressValue;
            const on = state.status === value;
            button.classList.toggle('is-on', on);
            button.classList.toggle('is-dropped', on && value === 'dropped');
            button.setAttribute('aria-pressed', on ? 'true' : 'false');
            button.value = on ? '' : value;
        });

        const extra = panel.querySelector('[data-extra-status]');
        if (extra && state.status !== 'watching' && state.status !== 'dropped') extra.remove();

        // Seen's menu only while it is seen.
        if (seen) {
            if (state.seen) {
                seen.setAttribute('aria-haspopup', 'menu');
                seen.setAttribute('aria-expanded', 'false');
            } else {
                seen.removeAttribute('aria-haspopup');
                seen.removeAttribute('aria-expanded');
            }
        }
        closeSeenMenu(false);

        if (nudge && !state.seen) nudge.hidden = true;
        // Fork (2026-11-24, rewatch log): "Watched again" only while it is seen. Its count stays as rendered: the log
        // is kept when a title is un-seen, so seeing it again shows the same count.
        const rewatch = panel.querySelector('[data-rewatch]');
        if (rewatch) rewatch.hidden = !state.seen;
        if (window.lucide) window.lucide.createIcons();
    }

    function showNudge() {
        const nudge = nudgeOf();
        if (!nudge) return;
        nudge.hidden = false;
        const first = nudge.querySelector('.title-nudge-score');
        if (first) first.focus({ preventScroll: true });
    }

    document.addEventListener('click', (event) => {
        const skip = event.target.closest && event.target.closest('[data-nudge-skip]');
        if (skip) skip.closest('[data-rate-nudge]').hidden = true;
    });

    // ---------- Seen's menu (fork, 2026-11-24, rewatch log) ----------
    const sheetQuery = window.matchMedia('(max-width: 600px)');
    let seenMenu = null; // { menu, backdrop, button }
    let bypassMenu = false; // "Mark as not seen" posts the Seen form itself
    let refocusSeen = false;

    const seenButtonOf = () => document.querySelector('.title-panel [data-title-seen]');
    const menuItems = () => (seenMenu ? [...seenMenu.menu.querySelectorAll('[data-seen-menu-item]')] : []);

    function placeSeenMenu() {
        if (!seenMenu) return;
        const { menu, backdrop, button } = seenMenu;
        const sheet = sheetQuery.matches;
        menu.classList.toggle('is-sheet', sheet);
        backdrop.hidden = !sheet;
        if (sheet) {
            menu.style.left = '';
            menu.style.top = '';
            return;
        }
        const r = button.getBoundingClientRect();
        const width = menu.offsetWidth;
        const height = menu.offsetHeight;
        const viewport = document.documentElement.clientWidth;
        const left = Math.max(8, Math.min(r.left, viewport - width - 8));
        let top = r.bottom + 6;
        if (top + height > window.innerHeight - 8 && r.top - height - 6 > 8) top = r.top - height - 6;
        menu.style.left = `${left + window.scrollX}px`;
        menu.style.top = `${top + window.scrollY}px`;
    }

    function openSeenMenu(button) {
        const template = document.querySelector('.title-panel template[data-seen-menu]');
        if (!template || !template.content.firstElementChild) return false;
        closeSeenMenu(false);
        const menu = template.content.firstElementChild.cloneNode(true);
        menu.id = 'seenMenu';
        const backdrop = document.createElement('div');
        backdrop.className = 'tile-menu-backdrop';
        backdrop.hidden = true;
        menu.style.visibility = 'hidden';
        document.body.append(backdrop, menu);
        seenMenu = { menu, backdrop, button };
        if (window.lucide) window.lucide.createIcons();
        placeSeenMenu();
        menu.style.visibility = '';
        button.setAttribute('aria-expanded', 'true');
        button.setAttribute('aria-controls', 'seenMenu');
        const first = menuItems()[0];
        if (first) first.focus({ preventScroll: true });
        return true;
    }

    function closeSeenMenu(restoreFocus = true) {
        if (!seenMenu) return;
        const { menu, backdrop, button } = seenMenu;
        seenMenu = null;
        menu.remove();
        backdrop.remove();
        const current = button.isConnected ? button : seenButtonOf();
        if (current) {
            current.removeAttribute('aria-controls');
            if (current.hasAttribute('aria-haspopup')) current.setAttribute('aria-expanded', 'false');
            if (restoreFocus) current.focus({ preventScroll: true });
        }
    }

    document.addEventListener('click', (event) => {
        if (!seenMenu || !event.target.closest) return;
        const target = event.target;
        if (target === seenMenu.backdrop || target.closest('[data-seen-menu-close]')) {
            event.preventDefault();
            closeSeenMenu();
            return;
        }
        if (target.closest('[data-seen-unmark]') && seenMenu.menu.contains(target)) {
            event.preventDefault();
            const form = seenButtonOf()?.closest('form');
            closeSeenMenu();
            if (!form) return;
            bypassMenu = true;
            try {
                if (typeof form.requestSubmit === 'function') form.requestSubmit(); else form.submit();
            } finally {
                bypassMenu = false;
            }
        }
    });

    // A tap or click anywhere else closes it (the phone backdrop closes it on its own click, above).
    document.addEventListener('pointerdown', (event) => {
        if (!seenMenu || seenMenu.menu.contains(event.target) || event.target === seenMenu.backdrop) return;
        if (seenMenu.button.contains(event.target)) return;
        closeSeenMenu(false);
    }, true);

    document.addEventListener('keydown', (event) => {
        if (!seenMenu) return;
        if (event.key === 'Escape') {
            event.preventDefault();
            closeSeenMenu();
        } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp' || event.key === 'Home' || event.key === 'End') {
            const items = menuItems();
            if (!items.length) return;
            event.preventDefault();
            const at = items.indexOf(document.activeElement);
            let next = 0;
            if (event.key === 'End') next = items.length - 1;
            else if (event.key === 'ArrowDown') next = at < 0 ? 0 : (at + 1) % items.length;
            else if (event.key === 'ArrowUp') next = at < 0 ? items.length - 1 : (at + items.length - 1) % items.length;
            items[next].focus();
        } else if (event.key === 'Tab') {
            // A menu is left with Tab: close it, and the focus goes on from the button.
            closeSeenMenu(true);
        }
    });

    window.addEventListener('resize', placeSeenMenu);
    window.addEventListener('scroll', () => { if (seenMenu && !sheetQuery.matches) placeSeenMenu(); }, { passive: true });

    // "Watched again" is saved by inplace.js (data-inplace=".title-panel"); the menu goes away at once and the focus
    // comes back to the (swapped) Seen button when the panel is in.
    document.addEventListener('submit', (event) => {
        const form = event.target.closest && event.target.closest('form[data-seen-rewatch]');
        if (!form || !seenMenu || !seenMenu.menu.contains(form)) return;
        seenMenu.menu.hidden = true;
        seenMenu.backdrop.hidden = true;
        refocusSeen = true;
        // Removed once inplace.js has read the form (it may still post it the normal way if the save fails).
        const done = seenMenu;
        seenMenu = null;
        setTimeout(() => { if (!done.menu.hidden) return; done.backdrop.remove(); }, 0);
        document.addEventListener('inplace:swapped', () => done.menu.remove(), { once: true });
    });

    document.addEventListener('inplace:swapped', () => {
        if (!refocusSeen) return;
        refocusSeen = false;
        const button = seenButtonOf();
        if (button) button.focus({ preventScroll: true });
    });

    document.addEventListener('submit', async (event) => {
        const form = event.target.closest && event.target.closest('form[data-title-act]');
        // confirm.js (capture phase) may have cancelled it already.
        if (!form || event.defaultPrevented) return;
        event.preventDefault();

        // Fork (2026-11-24): a seen title opens Seen's menu instead (its "Mark as not seen" posts through here).
        if (form.dataset.titleAct === 'seen' && !bypassMenu) {
            const button = form.querySelector('[data-title-seen]');
            if (button && button.getAttribute('aria-pressed') === 'true') {
                if (seenMenu && seenMenu.button === button) { closeSeenMenu(); return; }
                if (openSeenMenu(button)) return;
            }
        }

        const submitter = event.submitter && event.submitter.form === form ? event.submitter : null;
        const body = new URLSearchParams(new FormData(form));
        if (submitter && submitter.name) body.set(submitter.name, submitter.value);
        const busy = submitter || form.querySelector('button');
        if (busy.getAttribute('aria-busy') === 'true') return;
        busy.setAttribute('aria-busy', 'true');
        try {
            const response = await fetch(form.action, {
                method: 'POST',
                body,
                credentials: 'same-origin',
                headers: { 'X-Requested-With': 'fetch', Accept: 'application/json' },
            });
            const type = response.headers.get('content-type') || '';
            if (!type.includes('application/json') || response.status >= 500) throw new Error('not json');
            const state = await response.json();
            if (!response.ok && !state.message) throw new Error('failed');
            if (state.ok) apply(state);
            toast(state.message);
            if (form.dataset.titleAct === 'seen' && state.ok && state.seenDelta === 1 && !state.seenLocked) showNudge();
        } catch (error) {
            // Post it the normal way, keeping which button was pressed.
            if (submitter && submitter.name) {
                const field = document.createElement('input');
                field.type = 'hidden';
                field.name = submitter.name;
                field.value = submitter.value;
                form.appendChild(field);
            }
            form.submit();
        } finally {
            busy.removeAttribute('aria-busy');
        }
    });
})();
