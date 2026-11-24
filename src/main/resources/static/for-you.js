// Fork: "Recommended for you" on Home (templates/fragments/for-you.html). Discover's copy of the row is loaded by
// discover.js like its other pending rows; this does the same for Home, which has no discover.js.
// - The page renders a skeleton; the row is fetched from its data-src (/for-you/row) once it comes within 800px of
//   the viewport, so Home never waits on TMDB. Nothing to show, or any error: the skeleton is removed.
// - Arrow buttons page the row (touch and trackpads scroll natively).
// - Quick actions on the tiles are tile-actions.js's (delegated, so they work on the swapped-in row).
(() => {
    const placeholders = [...document.querySelectorAll('.for-you-row.is-pending[data-src]')]
        .filter((row) => !row.closest('.discover-rows'));
    if (!placeholders.length) return;
    if (!window.fetch || !window.DOMParser) {
        placeholders.forEach((row) => row.remove());
        return;
    }

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
        update();
    }

    async function load(placeholder) {
        try {
            const response = await fetch(placeholder.dataset.src, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
            if (!response.ok) throw new Error('row failed');
            // Logged out (the login page) or nothing to show: no row in the answer.
            const fresh = new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('.discover-row');
            if (!fresh || fresh.classList.contains('is-pending')) {
                placeholder.remove();
                return;
            }
            const row = document.importNode(fresh, true);
            placeholder.replaceWith(row);
            if (window.lucide) window.lucide.createIcons();
            setupRow(row);
        } catch (error) {
            placeholder.remove();
        }
    }

    if ('IntersectionObserver' in window) {
        const observer = new IntersectionObserver((entries) => {
            entries.forEach((entry) => {
                if (!entry.isIntersecting) return;
                observer.unobserve(entry.target);
                load(entry.target);
            });
        }, { rootMargin: '800px 0px' });
        placeholders.forEach((row) => observer.observe(row));
    } else {
        placeholders.forEach(load);
    }
})();
