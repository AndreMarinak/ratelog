// Infinite scroll on the search results page (templates/search.html).
// Fork: later pages are the server-rendered results page itself (/?q=...&page=N), so their tiles are exactly the
// first page's markup, quick actions (Seen / Watchlist) and Upcoming badges included. Without JS, nothing loads
// past the first page (as before).
const SearchInfinite = {
    sentinel: null,
    results: null,
    loading: false,

    init() {
        this.sentinel = document.getElementById('scroll-sentinel');
        this.results = document.getElementById('results');

        if (!this.sentinel || !this.results || !window.fetch || !window.DOMParser) return;

        const observer = new IntersectionObserver((entries) => {
            if (entries[0].isIntersecting && !this.loading) {
                this.loadMore();
            }
        }, { rootMargin: '200px' });

        observer.observe(this.sentinel);
    },

    async loadMore() {
        this.loading = true;

        const query = this.sentinel.dataset.query;
        const mediaType = this.sentinel.dataset.mediaType;
        const page = parseInt(this.sentinel.dataset.page, 10) + 1;

        try {
            const params = new URLSearchParams({ q: query, page });
            if (mediaType) params.set('mediaType', mediaType);

            const response = await fetch(`/?${params}`, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
            const fresh = doc.getElementById('results');
            // Logged out (the login page) or an error page: stop quietly.
            if (!fresh) throw new Error('no results on the next page');

            const shown = new Set([...this.results.querySelectorAll('.search-tile-wrap')].map((tile) => tile.dataset.key));
            fresh.querySelectorAll('.search-tile-wrap').forEach((tile) => {
                if (shown.has(tile.dataset.key)) return;
                this.results.appendChild(document.importNode(tile, true));
            });
            if (window.lucide) lucide.createIcons();

            if (doc.getElementById('scroll-sentinel')) {
                this.sentinel.dataset.page = page;
            } else {
                this.sentinel.remove();
            }
        } catch (error) {
            console.warn('Infinite scroll stopped:', error.message);
            this.sentinel.remove();
        } finally {
            this.loading = false;
        }
    },
};

document.addEventListener('DOMContentLoaded', () => SearchInfinite.init());
