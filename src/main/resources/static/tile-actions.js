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

    function saveHomeScroll(form) {
        const row = form.closest('.home-row');
        if (!row || !form.closest('.home-dashboard')) return;
        const track = row.querySelector('.home-row-track');
        try {
            sessionStorage.setItem(HOME_SCROLL_KEY, JSON.stringify({ y: window.scrollY, row: row.id || null, x: track ? track.scrollLeft : 0 }));
        } catch (err) { /* storage unavailable: nothing to restore */ }
    }

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
