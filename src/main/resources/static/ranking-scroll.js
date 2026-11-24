// Fork: the Ratings rankings (/movies, /tvshows) load the next 30 as you scroll (user request 2026-11-23: "the ratings
// page only natively loads 10 the default but can we just have it load more as u scroll?"). Markup:
// templates/fragments/ranking-scroll.html; server: org.ratelog.ratings.scroll.RankingScroll.
//
// - The footer under the ranking ([data-ranking-more]) carries the next page's URL (data-next: ?offset=30&limit=30&
//   partial=1, the cards alone). When it comes within about a screen of view (IntersectionObserver), or its
//   "Show more" link is clicked, those cards are appended before it (any already shown are skipped) and the new
//   footer replaces it: "Show more" again, or "That's all 45" after the last title.
// - The URL is replaced with the view and how many are shown (?limit=60, data-address; history.replaceState, keeping
//   other scripts' history.state): no history entry per page, so one Back leaves the page (docs/decisions/navigation-and-reloads.md
//   "One Back leaves the page"), and a reload or Back shows as many again (the browser puts the scroll back).
// - The poster quick actions on appended cards just work (tile-actions.js and tile-rate.js are delegated from
//   document); only their icons need creating.
// - A filter applied in place (ratings-filters.js fires `ratings:swapped`) brings a new first page and footer.
// - If a page fails to load, the footer says so and its link reads "Try again"; nothing loads by itself until it is
//   used. 429 (rate limit): the server's message as a toast. A login page or anything unexpected: the link's own URL
//   is opened instead.
// Without JS (or fetch) the link is a plain link to the same view with one more page, opened at the first new card.
(() => {
    if (window.__ratelogRankingScroll) return;
    window.__ratelogRankingScroll = true;
    if (!window.fetch || !window.DOMParser) return;

    // Start loading while the footer is still this far below the screen.
    const MARGIN = '0px 0px 900px 0px';

    const footer = () => document.querySelector('[data-ranking-more]');
    const parse = (html) => new DOMParser().parseFromString(html, 'text/html');
    // A card's identity: its title link (/movie/603, /tv/1396).
    const keyOf = (item) => {
        const link = item.querySelector('a.card[href]');
        return link ? link.getAttribute('href') : '';
    };

    let observer = null;
    let busy = false;
    let paused = false; // after a failed load, until the link is used

    function replaceUrl(url) {
        if (!url || !window.history || !history.replaceState) return;
        if (url === location.pathname + location.search) return;
        const state = history.state;
        const keep = state && typeof state === 'object' ? state : {};
        history.replaceState(Object.assign({}, keep, { rankingScroll: true }), '', url);
    }

    function watch() {
        if (observer) observer.disconnect();
        observer = null;
        const box = footer();
        if (!box || !box.dataset.next || paused || !('IntersectionObserver' in window)) return;
        observer = new IntersectionObserver((entries) => {
            if (entries.some((entry) => entry.isIntersecting)) load(box, false);
        }, { rootMargin: MARGIN });
        // Fires once at once: a footer already near (a tall screen) loads the next page right away.
        observer.observe(box);
    }

    function showError(box) {
        paused = true;
        let note = box.querySelector('.ranking-more-error');
        if (!note) {
            note = document.createElement('p');
            note.className = 'ranking-more-error';
            note.setAttribute('role', 'alert');
            box.prepend(note);
        }
        note.textContent = box.dataset.error || 'Something went wrong.';
    }

    async function load(box, byHand) {
        if (busy || !box || !box.isConnected || !box.dataset.next) return;
        busy = true;
        const link = box.querySelector('[data-ranking-more-link]');
        const label = link && link.querySelector('span');
        let idle = label ? label.textContent : '';
        if (link) link.setAttribute('aria-busy', 'true');
        if (label && box.dataset.loading) label.textContent = box.dataset.loading;
        try {
            const response = await fetch(box.dataset.next, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
            if (response.status === 429) {
                const limited = parse(await response.text()).querySelector('.feedback.error');
                if (window.ratelogToast) window.ratelogToast(limited ? limited.textContent.trim() : 'Too many requests');
                paused = true;
                return;
            }
            const landed = new URL(response.url, location.href);
            const doc = parse(await response.text());
            if (!box.isConnected) return; // a filter brought a new ranking meanwhile
            const fresh = doc.querySelector('[data-ranking-more]');
            if (!response.ok || landed.pathname !== location.pathname || !fresh) {
                // Logged out (the login page) or something unexpected: the plain link shows what's wrong.
                if (link) location.assign(link.href); else showError(box);
                return;
            }
            const shown = new Set([...document.querySelectorAll('.rank-item')].map(keyOf));
            const cards = [...doc.querySelectorAll('.rank-item')]
                .filter((item) => !shown.has(keyOf(item)))
                .map((item) => document.importNode(item, true));
            box.before(...cards);
            const next = document.importNode(fresh, true);
            box.replaceWith(next);
            replaceUrl(next.dataset.address);
            if (window.lucide && window.lucide.createIcons) window.lucide.createIcons();
            // A click (keyboard or not) moves on to the first new card; scrolling leaves focus alone.
            if (byHand && cards.length) {
                const first = cards[0].querySelector('a.card');
                if (first) first.focus({ preventScroll: true });
            }
        } catch (err) {
            if (box.isConnected) {
                showError(box);
                idle = box.dataset.retry || idle;
            }
        } finally {
            busy = false;
            if (link && link.isConnected) {
                link.removeAttribute('aria-busy');
                if (label) label.textContent = idle;
            }
            watch();
        }
    }

    document.addEventListener('click', (event) => {
        const link = event.target.closest && event.target.closest('[data-ranking-more] [data-ranking-more-link]');
        if (!link || event.defaultPrevented || event.button > 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        paused = false;
        load(link.closest('[data-ranking-more]'), true);
    });

    // A filter applied in place: a new first page and footer.
    document.addEventListener('ratings:swapped', () => {
        paused = false;
        watch();
    });

    watch();
})();
