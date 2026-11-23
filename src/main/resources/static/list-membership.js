// Fork: the add-to-list sheet on title pages (templates/fragments/add-to-list-modal.html). Every list shows whether
// the title is in it; a tap adds it or takes it out (POST /list/{id}/membership, header `X-Requested-With: fetch`,
// JSON answer). The sheet stays open, so several lists can be ticked in a row, and the row, the "In 2 of your lists"
// line and the page's "In 2 lists" button all take the server's state. Removing asks first (data-confirm, read by
// confirm.js in the capture phase). Taps are sent one at a time, so the counts can't arrive out of order.
// Lists with the title are listed first: the server renders them that way, and this re-sorts when the sheet opens
// (never while it is open, so nothing moves under your finger). Without JS, or on any error, the form posts normally
// and comes back to the title page. The sheet itself can be swapped for a fresh copy (inplace.js, after "New list"),
// so everything is looked up on each event.
(() => {
    if (!window.fetch || !window.URLSearchParams) return;

    const toast = (text) => { if (window.ratelogToast) window.ratelogToast(text); };
    let queue = Promise.resolve();

    function setIcon(button, name) {
        const old = button.querySelector('svg, i');
        const icon = document.createElement('i');
        icon.setAttribute('data-lucide', name);
        icon.setAttribute('aria-hidden', 'true');
        if (old) old.replaceWith(icon); else button.prepend(icon);
    }

    function applyRow(form, state) {
        const button = form.querySelector('.list-member-toggle');
        button.classList.toggle('is-in', state.inList);
        button.setAttribute('aria-pressed', state.inList ? 'true' : 'false');
        button.title = state.inList ? button.dataset.titleRemove : button.dataset.titleAdd;
        form.querySelector('input[name="inList"]').value = state.inList ? 'false' : 'true';
        if (state.inList && state.confirm) form.dataset.confirm = state.confirm;
        else delete form.dataset.confirm;
        const rank = form.querySelector('.list-member-rank');
        if (rank) {
            rank.textContent = state.rankLabel || '';
            rank.hidden = !state.inList;
        }
    }

    function applyTitle(form, state) {
        const summary = document.querySelector('#addToListModal [data-list-summary]');
        if (summary) {
            summary.textContent = state.summary;
            summary.classList.toggle('is-in', state.count > 0);
        }
        // Fork (tile rating): a poster tile's sheet (tile-rate.js) is for another title than the page's button.
        form.dispatchEvent(new CustomEvent('list:membership', { bubbles: true, detail: state }));
        if (form.closest('[data-tile-sheet]')) return;
        document.querySelectorAll('[data-list-button]').forEach((button) => {
            button.classList.toggle('is-on', state.count > 0);
            button.title = state.buttonTitle;
            const label = button.querySelector('.title-toggle-label');
            if (label) label.textContent = state.buttonLabel;
            setIcon(button, state.count > 0 ? 'list-checks' : 'list-plus');
        });
        if (window.lucide) window.lucide.createIcons();
    }

    async function send(form, body, button) {
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
            if (state.ok) {
                applyRow(form, state);
                applyTitle(form, state);
            }
            toast(state.message);
        } catch (error) {
            form.submit(); // the normal post (confirm.js already asked); comes back to the title page
        } finally {
            button.removeAttribute('aria-busy');
        }
    }

    document.addEventListener('submit', (event) => {
        const form = event.target.closest && event.target.closest('form[data-list-member]');
        // confirm.js (capture phase) may have cancelled it already.
        if (!form || event.defaultPrevented) return;
        event.preventDefault();
        const button = form.querySelector('.list-member-toggle');
        if (!button || button.getAttribute('aria-busy') === 'true') return;
        button.setAttribute('aria-busy', 'true');
        // Read the form now: what the user saw when tapping is what gets sent.
        const body = new URLSearchParams(new FormData(form));
        queue = queue.then(() => send(form, body, button));
    });

    // Lists with the title first, each group in the usual order (newest list first), whenever the sheet opens.
    // modal:open doesn't bubble, so listen in the capture phase.
    document.addEventListener('modal:open', (event) => {
        const modal = event.target;
        if (!modal || modal.id !== 'addToListModal') return;
        const group = modal.querySelector('.list-selection');
        if (!group) return;
        const isIn = (form) => (form.querySelector('.list-member-toggle.is-in') ? 0 : 1);
        const order = (form) => Number(form.dataset.order) || 0;
        [...group.querySelectorAll('form[data-list-member]')]
            .sort((a, b) => isIn(a) - isIn(b) || order(a) - order(b))
            .forEach((form) => group.appendChild(form));
    }, true);
})();
