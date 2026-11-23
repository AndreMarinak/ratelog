// Fork: the /watchlist page's quick actions (templates/watchlist.html). Without JS each button is a plain POST that
// comes back to the same tab and filter with an Undo notice. With JS it saves in the background (header
// `X-Requested-With: fetch`, JSON answer): the tile leaves the tab, the tab counts move with it, and a toast offers
// Undo, which puts the title back where it was. The page never reloads.
(() => {
    if (!window.fetch || !window.URLSearchParams) return;

    const csrfOf = (form) => form.querySelector('input[type="hidden"][name]:not([name="mediaType"]):not([name="mediaId"]):not([name="to"]):not([name="back"])');
    let toastEl = null;
    let toastTimer = null;

    function count(status, delta) {
        if (!status) return;
        const el = document.querySelector(`.watchlist-tabs [data-status="${status}"] .status-count`);
        if (el) el.textContent = String(Math.max(0, (Number(el.textContent) || 0) + delta));
    }

    function refreshEmpty() {
        const grid = document.querySelector('.watchlist-grid');
        const empty = document.querySelector('.watchlist-empty');
        const hint = document.querySelector('.watchlist-hint');
        const visible = grid ? grid.querySelectorAll('.watchlist-item:not([hidden])').length : 0;
        if (empty) empty.hidden = visible > 0;
        if (hint) hint.hidden = visible === 0;
    }

    /** "Removed "Dune" from your watchlist. [Undo]": fixed at the bottom, gone after a few seconds. */
    function toast(message, undo) {
        if (!toastEl) {
            toastEl = document.createElement('div');
            toastEl.className = 'watchlist-toast';
            toastEl.setAttribute('role', 'status');
            toastEl.setAttribute('aria-live', 'polite');
            document.body.appendChild(toastEl);
        }
        const text = document.createElement('span');
        text.textContent = message || '';
        toastEl.replaceChildren(text);
        if (undo) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'watchlist-toast-undo';
            button.textContent = undo.label;
            button.addEventListener('click', () => undo.run(button));
            toastEl.appendChild(button);
        }
        toastEl.classList.add('visible');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => toastEl.classList.remove('visible'), undo ? 7000 : 3200);
    }

    async function post(fields) {
        const response = await fetch('/watchlist/item', {
            method: 'POST',
            body: new URLSearchParams(fields),
            credentials: 'same-origin',
            headers: { 'X-Requested-With': 'fetch', Accept: 'application/json' },
        });
        const type = response.headers.get('content-type') || '';
        if (!type.includes('application/json') || response.status >= 500) throw new Error('not json');
        const result = await response.json();
        if (!response.ok && !result.message) throw new Error('failed');
        return result;
    }

    document.addEventListener('submit', async (event) => {
        const form = event.target.closest && event.target.closest('.watchlist-actions form');
        if (!form || event.defaultPrevented) return;
        event.preventDefault();
        const item = form.closest('.watchlist-item');
        const button = form.querySelector('button');
        if (button.getAttribute('aria-busy') === 'true') return;
        button.setAttribute('aria-busy', 'true');

        const fields = Object.fromEntries(new FormData(form));
        try {
            const result = await post(fields);
            if (!result.ok) { toast(result.message); return; }
            // Every move changes the status, so the title leaves this tab.
            item.hidden = true;
            count(result.previous, -1);
            count(result.current, 1);
            refreshEmpty();
            const csrf = csrfOf(form);
            toast(result.message, result.undoMove && {
                label: result.undoLabel,
                run: async (undoButton) => {
                    undoButton.disabled = true;
                    try {
                        const back = await post({
                            [csrf.name]: csrf.value,
                            mediaType: fields.mediaType,
                            mediaId: fields.mediaId,
                            to: result.undoMove,
                            undo: '1',
                        });
                        if (!back.ok) { toast(back.message); return; }
                        item.hidden = false;
                        count(back.previous, -1);
                        count(back.current, 1);
                        refreshEmpty();
                        toast(back.message);
                        item.querySelector('a, button')?.focus({ preventScroll: true });
                    } catch (error) {
                        window.location.reload();
                    }
                },
            });
        } catch (error) {
            form.submit();
        } finally {
            button.removeAttribute('aria-busy');
        }
    });
})();
