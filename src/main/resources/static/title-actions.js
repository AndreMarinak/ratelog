// Fork: title page actions (templates/fragments/title-actions.html). Seen, Watchlist and a show's Watching / Dropped
// save in the background (header `X-Requested-With: fetch`, JSON answer) and every toggle is updated from the server's
// state, so the page never reloads. After Seen on a movie with no rating, a small "Rate it?" offers a quick score
// (optional; Skip closes it). Without JS these are plain POST forms that come back to the page, with no prompt.
// The panel is looked up on every event: the bell (inplace.js) swaps it for a fresh copy.
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

    document.addEventListener('submit', async (event) => {
        const form = event.target.closest && event.target.closest('form[data-title-act]');
        // confirm.js (capture phase) may have cancelled it already.
        if (!form || event.defaultPrevented) return;
        event.preventDefault();

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
