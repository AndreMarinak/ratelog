// Fork: list pages (templates/list-detail.html, fragments/list-progress.html).
// - Seen toggles on posters (tile-actions.js) update "You've seen 9 of 25", the bar and the tab counts in place.
// - Tick-off mode (?mark=1, the grid has `is-ticking`): tapping a poster toggles Seen instead of opening the title.
//   Without JS the posters stay links and their Seen buttons are the per-poster form.
// Everything is delegated from `document` and looked up when used, so it keeps working after list-filter.js swaps
// the progress section and the grid in place. The mode lives in the URL (`mark=1`), which every tab link keeps.
(() => {
    function update(delta) {
        const progress = document.querySelector('[data-list-progress]');
        if (!progress || !delta) return;
        const total = Number(progress.dataset.total) || 0;
        const seen = Math.max(0, Math.min(total, (Number(progress.dataset.seen) || 0) + delta));
        progress.dataset.seen = String(seen);
        const text = (progress.dataset.text || '').replace('{seen}', seen).replace('{total}', total);
        document.querySelectorAll('[data-progress-text]').forEach((el) => { el.textContent = text; });
        const bar = progress.querySelector('.list-progress-bar');
        if (bar) {
            bar.setAttribute('aria-valuenow', String(seen));
            bar.setAttribute('aria-label', text);
            const fill = bar.querySelector('span');
            if (fill) fill.style.width = `${total ? Math.floor((seen * 100) / total) : 0}%`;
        }
        const counts = {
            'list.progress.filter.seen': seen,
            'list.progress.filter.unseen': total - seen,
        };
        progress.querySelectorAll('[data-tab-count]').forEach((el) => {
            const value = counts[el.dataset.tabCount];
            if (value !== undefined) el.textContent = String(value);
        });
    }

    document.addEventListener('tile:changed', (event) => {
        const detail = event.detail || {};
        if (detail.act === 'seen' && detail.state && detail.state.ok) update(detail.state.seenDelta);
    });

    document.addEventListener('click', (event) => {
        const card = event.target.closest && event.target.closest('.list-grid.is-ticking a.list-card');
        // The owner's remove button (inside the card) keeps working; so do new-tab clicks.
        if (!card || event.target.closest('form, button')) return;
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.button > 0) return;
        event.preventDefault();
        const form = card.closest('.list-item')?.querySelector('form.tile-seen');
        // Upcoming titles have no Seen toggle: the Upcoming badge on the poster says why.
        if (!form) return;
        if (typeof form.requestSubmit === 'function') form.requestSubmit();
        else form.querySelector('button').click();
    });
})();
