// Infinite scroll on the search results page (templates/search.html).
// Fork: later pages are the server-rendered results page itself (/?q=...&page=N), so their tiles are exactly the
// first page's markup, quick actions (Seen / Watchlist) and Upcoming badges included. Without JS, nothing loads
// past the first page (as before).
// Fork (2026-11-23): coming back keeps your place, like Discover's grid (browse.js): the last page loaded and the
// scroll position are remembered per tab (sessionStorage, keyed by the results URL). Back / Forward, a reload or a
// title page's "← Search results" link to the same URL loads those pages again (fresh) and scrolls back.
// Fork (2026-11-24): the type filter and Year menu swap the results in place (view-swap.js); `view:swapped` starts the new
// grid at its first page. A decade reads several TMDB pages per results page, so the next page is the fresh page's
// sentinel's `data-page` + 1 (not just one more), and a page that adds little is followed at once while the sentinel is
// still in view (it stayed put, so the observer wouldn't fire again).
const SearchInfinite = {
    KEY: 'ratelog.search.pages',
    MAX_AGE_MS: 6 * 60 * 60 * 1000,
    MAX_PAGES: 10,
    sentinel: null,
    results: null,
    loading: false,
    restoring: false,
    observer: null,

    here() {
        return location.pathname + location.search;
    },

    read() {
        try { return JSON.parse(sessionStorage.getItem(this.KEY) || 'null'); } catch (err) { return null; }
    },

    save() {
        if (this.restoring || !this.results) return;
        const page = this.sentinel && this.sentinel.isConnected ? parseInt(this.sentinel.dataset.page, 10) : this.lastPage;
        try {
            sessionStorage.setItem(this.KEY, JSON.stringify({ url: this.here(), page, y: Math.round(window.scrollY), t: Date.now() }));
        } catch (err) { /* storage unavailable: page 1 only */ }
    },

    navigationType() {
        try {
            const entry = performance.getEntriesByType('navigation')[0];
            if (entry && entry.type) return entry.type;
        } catch (err) { /* old browser */ }
        return 'navigate';
    },

    init() {
        if (!window.fetch || !window.DOMParser || !window.IntersectionObserver) return;
        this.observer = new IntersectionObserver((entries) => {
            const entry = entries[entries.length - 1];
            if (entry.isIntersecting && !this.loading && !this.restoring) {
                this.loadMore().then((added) => { if (added) this.recheck(); });
            }
        }, { rootMargin: '200px' });
        document.addEventListener('view:swapped', () => this.attach(false));
        this.listen();
        this.attach(true);
    },

    // Follow the current grid: on load (maybe restoring the pages loaded before) and after an in-place filter change.
    attach(first) {
        this.observer.disconnect();
        this.sentinel = document.getElementById('scroll-sentinel');
        this.results = document.getElementById('results');
        this.loading = false;
        if (!this.results) return;
        this.lastPage = this.sentinel ? parseInt(this.sentinel.dataset.page, 10) : 1;
        if (!this.sentinel) { this.save(); return; }

        const record = this.read();
        const returned = first && (this.navigationType() !== 'navigate' || !!(window.RatelogBackNav && window.RatelogBackNav.returned));
        if (returned && record && record.url === this.here() && record.page > this.lastPage
            && Date.now() - (record.t || 0) < this.MAX_AGE_MS) {
            this.restore(record);
        } else {
            this.save();
            this.observer.observe(this.sentinel);
        }
    },

    // Observe again: the observer reports the sentinel at once if it is still in view after a short page.
    recheck() {
        if (this.restoring || !this.sentinel || !this.sentinel.isConnected) return;
        this.observer.unobserve(this.sentinel);
        this.observer.observe(this.sentinel);
    },

    listen() {
        let scrollTimer = 0;
        window.addEventListener('scroll', () => {
            clearTimeout(scrollTimer);
            scrollTimer = setTimeout(() => this.save(), 150);
        }, { passive: true });
        document.addEventListener('click', (event) => { if (event.target.closest && event.target.closest('a[href]')) this.save(); }, true);
        window.addEventListener('pagehide', () => this.save());
    },

    async restore(record) {
        let touched = false;
        const stop = () => { touched = true; };
        ['wheel', 'touchstart', 'mousedown', 'keydown'].forEach((type) =>
            window.addEventListener(type, stop, { passive: true, once: true }));
        this.restoring = true;
        if (window.Loader) window.Loader.start();
        const last = Math.min(record.page, this.lastPage + this.MAX_PAGES);
        try {
            while (this.sentinel.isConnected && parseInt(this.sentinel.dataset.page, 10) < last) {
                if (!(await this.loadMore())) break;
            }
        } finally {
            this.restoring = false;
            if (window.Loader) window.Loader.stop();
        }
        const scroll = () => {
            if (!touched && Math.abs(window.scrollY - record.y) > 2) window.scrollTo({ top: record.y, left: 0, behavior: 'instant' });
        };
        scroll();
        [300, 900].forEach((ms) => setTimeout(scroll, ms));
        this.save();
        if (this.sentinel.isConnected) this.observer.observe(this.sentinel);
    },

    // Returns true when a page was appended. A grid swapped away meanwhile (a filter change) is left alone.
    async loadMore() {
        const sentinel = this.sentinel;
        const results = this.results;
        this.loading = true;

        const query = sentinel.dataset.query;
        const mediaType = sentinel.dataset.mediaType;
        const page = parseInt(sentinel.dataset.page, 10) + 1;

        try {
            const params = new URLSearchParams({ q: query, page });
            if (mediaType) params.set('mediaType', mediaType);
            // Fork (2026-11-24): the Year menu applies to every page.
            if (sentinel.dataset.year) params.set('year', sentinel.dataset.year);
            else if (sentinel.dataset.decade) params.set('decade', sentinel.dataset.decade);

            const response = await fetch(`/?${params}`, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
            if (!results.isConnected) return false;
            const fresh = doc.getElementById('results');
            // Logged out (the login page) or an error page: stop quietly.
            if (!fresh) throw new Error('no results on the next page');

            const shown = new Set([...results.querySelectorAll('.search-tile-wrap')].map((tile) => tile.dataset.key));
            fresh.querySelectorAll('.search-tile-wrap').forEach((tile) => {
                if (shown.has(tile.dataset.key)) return;
                results.appendChild(document.importNode(tile, true));
            });
            if (window.lucide) lucide.createIcons();

            const next = doc.getElementById('scroll-sentinel');
            // A decade's page may have read further than the page asked for.
            const read = next ? Math.max(page, parseInt(next.dataset.page, 10) || page) : page;
            this.lastPage = read;
            if (next) {
                sentinel.dataset.page = read;
            } else {
                sentinel.remove();
            }
            this.save();
            return true;
        } catch (error) {
            if (!results.isConnected) return false;
            console.warn('Infinite scroll stopped:', error.message);
            sentinel.remove();
            return false;
        } finally {
            if (sentinel === this.sentinel) this.loading = false;
        }
    },
};

document.addEventListener('DOMContentLoaded', () => SearchInfinite.init());
