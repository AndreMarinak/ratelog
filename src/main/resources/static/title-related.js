// Fork: rows under a title page (templates/fragments/title-related.html): the movie's series and "More like this".
// - The page renders a skeleton; the rows are fetched from its data-src (/movie/{id}/related) once it comes within
//   800px of the viewport, so the page never waits on TMDB. Nothing to show, or any error: the skeleton is removed.
// - Arrow buttons page a row (touch and trackpads scroll natively); the series row starts scrolled to "You're here".
// - Quick actions on the tiles are tile-actions.js's (delegated, so they work on the swapped-in rows).
// - The series progress line ("You've seen 2 of 5 · Next: Toy Story 3") and this title's own Seen mark follow
//   changes: after a tile in the series row is toggled, or the page's Seen button changes, the rows are fetched
//   again (TMDB is cached by then) and only those two parts are updated, so nothing jumps.
(() => {
    const placeholder = document.querySelector('.title-related.is-pending[data-src]');
    if (!placeholder) return;
    if (!window.fetch || !window.DOMParser) {
        placeholder.remove();
        return;
    }

    const src = placeholder.dataset.src;
    const refreshIcons = () => { if (window.lucide) window.lucide.createIcons(); };
    let section = placeholder;

    async function fetchRows() {
        const response = await fetch(src, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
        if (!response.ok) throw new Error('rows failed');
        // Logged out (the login page) or nothing to show: no .title-related in the answer.
        return new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('.title-related');
    }

    // --- Rows ---

    function setupRow(row) {
        const track = row.querySelector('.home-row-track');
        const prev = row.querySelector('[data-row-prev]');
        const next = row.querySelector('[data-row-next]');
        if (!track || !prev || !next) return;

        const update = () => {
            const max = track.scrollWidth - track.clientWidth;
            row.classList.toggle('is-scrollable', max > 4);
            prev.disabled = track.scrollLeft <= 4;
            next.disabled = track.scrollLeft >= max - 4;
        };
        const page = (direction) => {
            const smooth = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
            track.scrollBy({ left: direction * Math.max(track.clientWidth * 0.85, 160), behavior: smooth ? 'smooth' : 'auto' });
        };
        prev.addEventListener('click', () => page(-1));
        next.addEventListener('click', () => page(1));
        track.addEventListener('scroll', update, { passive: true });
        window.addEventListener('resize', update);

        // Long series (Bond, Fast & Furious): start with this title in view, one entry before it peeking in.
        const current = track.querySelector('.home-tile-wrap.is-current');
        if (current) {
            const left = current.offsetLeft - track.offsetLeft;
            if (left + current.offsetWidth > track.clientWidth) {
                const before = current.previousElementSibling;
                track.scrollLeft = Math.max(0, left - (before ? before.offsetWidth * 0.6 : 0) - 4);
            }
        }
        update();
    }

    async function load() {
        try {
            const fresh = await fetchRows();
            if (!fresh || !fresh.querySelector('.related-row')) {
                placeholder.remove();
                return;
            }
            section = document.importNode(fresh, true);
            placeholder.replaceWith(section);
            refreshIcons();
            section.querySelectorAll('.related-row').forEach(setupRow);
            watchSeen();
        } catch (error) {
            placeholder.remove();
        }
    }

    if ('IntersectionObserver' in window) {
        const observer = new IntersectionObserver((entries) => {
            if (!entries.some((entry) => entry.isIntersecting)) return;
            observer.disconnect();
            load();
        }, { rootMargin: '800px 0px' });
        observer.observe(placeholder);
    } else {
        load();
    }

    // --- Keeping the series progress and this title's Seen mark current ---

    let refreshTimer = null;
    let refreshing = false;

    function scheduleRefresh() {
        if (!section.querySelector('.related-series')) return;
        clearTimeout(refreshTimer);
        refreshTimer = setTimeout(refresh, 250);
    }

    async function refresh() {
        if (refreshing) {
            scheduleRefresh();
            return;
        }
        refreshing = true;
        try {
            const fresh = await fetchRows();
            const progress = section.querySelector('[data-related-progress]');
            const freshProgress = fresh && fresh.querySelector('[data-related-progress]');
            if (progress && freshProgress) progress.textContent = freshProgress.textContent;
            const mark = section.querySelector('[data-related-current-seen]');
            const freshMark = fresh && fresh.querySelector('[data-related-current-seen]');
            if (mark && freshMark) mark.hidden = freshMark.hidden;
        } catch (error) {
            // Keep what is shown; the next change tries again.
        } finally {
            refreshing = false;
        }
    }

    function watchSeen() {
        if (!section.querySelector('.related-series')) return;

        // A Seen eye toggled on a series tile (tile-actions.js).
        section.addEventListener('tile:changed', (event) => {
            if (event.target.closest('.related-series')) scheduleRefresh();
        });

        // The page's own Seen button (title-actions.js updates aria-pressed; the bell can swap the whole panel).
        const layout = document.querySelector('.title-layout');
        if (!layout || !('MutationObserver' in window)) return;
        const seenNow = () => {
            const button = document.querySelector('[data-title-seen]');
            return button ? button.getAttribute('aria-pressed') : null;
        };
        let last = seenNow();
        new MutationObserver(() => {
            const now = seenNow();
            if (now === last) return;
            last = now;
            scheduleRefresh();
        }).observe(layout, { subtree: true, childList: true, attributes: true, attributeFilter: ['aria-pressed'] });
    }
})();
