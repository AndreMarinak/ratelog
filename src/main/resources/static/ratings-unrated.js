// Fork: the Ratings tab's "To rate" view (templates/ratings-unrated.html). A title rated from its ★ (tile-rate.js), or
// un-seen with its eye (tile-actions.js), no longer belongs here: its tile fades out and the counts (the tabs and the
// switch's "To rate 12") go down by one. The toast comes from the action itself. Nothing reloads.
(() => {
    const section = document.querySelector('[data-unrated]');
    if (!section) return;

    function bump(el, delta) {
        if (!el) return;
        const next = Math.max(0, (Number(el.textContent) || 0) + delta);
        el.textContent = String(next);
        return next;
    }

    function leave(tile) {
        if (!tile || tile.dataset.leaving) return;
        tile.dataset.leaving = '1';
        const type = tile.dataset.unratedType;
        bump(section.querySelector('[data-unrated-tab="all"] [data-unrated-count]'), -1);
        bump(section.querySelector(`[data-unrated-tab="${type}"] [data-unrated-count]`), -1);
        const total = bump(document.querySelector('[data-ratings-unrated-count]'), -1);
        if (total === 0) document.querySelector('[data-ratings-unrated-count]').hidden = true;
        tile.classList.add('is-leaving');
        setTimeout(() => {
            tile.remove();
            const grid = section.querySelector('.unrated-grid');
            if (!grid || !grid.querySelector('.home-tile-wrap')) {
                if (grid) grid.remove();
                const empty = section.querySelector('[data-unrated-empty]');
                if (empty) empty.hidden = false;
            }
        }, 250);
    }

    document.addEventListener('tile:changed', (event) => {
        const detail = event.detail || {};
        const state = detail.state;
        if (!state || !state.ok) return;
        const rated = detail.act === 'rate' && state.rated;
        const unseen = detail.act === 'seen' && !state.seen;
        if (!rated && !unseen) return;
        // Every copy of the title (there is one per title here).
        const key = event.target.dataset && event.target.dataset.key;
        if (!key) return;
        section.querySelectorAll(`[data-tile-actions][data-key="${CSS.escape(key)}"]`).forEach((stack) => leave(stack.closest('.home-tile-wrap')));
    });
})();
