const SearchInfinite = {
    sentinel: null,
    results: null,
    loading: false,

    init() {
        this.sentinel = document.getElementById('scroll-sentinel');
        this.results = document.getElementById('results');

        if (!this.sentinel || !this.results) return;

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
        const page = parseInt(this.sentinel.dataset.page) + 1;

        try {
            const params = new URLSearchParams({ q: query, page });
            if (mediaType) params.set('mediaType', mediaType);

            const response = await fetch(`/api/search?${params}`);
            const data = await response.json();

            data.items.forEach(item => this.results.appendChild(this.createCard(item)));
            if (window.lucide) lucide.createIcons();

            if (data.hasMore) {
                this.sentinel.dataset.page = page;
            } else {
                this.sentinel.remove();
            }
        } catch (error) {
            console.error('Infinite scroll failed:', error);
        } finally {
            this.loading = false;
        }
    },

    // Fork: same poster tile as the server-rendered results in search.html (home.css + search-results.css).
    createCard(item) {
        const link = document.createElement('a');
        link.href = item.type === 'movie' ? `/movie/${item.tmdbId}` : `/tv/${item.tmdbId}`;
        link.className = 'home-tile search-tile';

        const poster = document.createElement('div');
        poster.className = 'home-poster';

        if (item.posterPath) {
            const img = document.createElement('img');
            img.src = `https://image.tmdb.org/t/p/w342${item.posterPath}`;
            img.srcset = `https://image.tmdb.org/t/p/w342${item.posterPath} 342w, https://image.tmdb.org/t/p/w500${item.posterPath} 500w`;
            img.sizes = '(max-width: 600px) 31vw, 170px';
            img.alt = `Poster of ${item.title}`;
            img.loading = 'lazy';
            img.decoding = 'async';
            poster.appendChild(img);
        } else {
            const fallback = document.createElement('div');
            fallback.className = 'poster-fallback';
            fallback.textContent = item.title.charAt(0).toUpperCase();
            poster.appendChild(fallback);
        }

        const typeTag = document.createElement('span');
        typeTag.className = 'home-type';
        typeTag.title = item.type === 'movie' ? 'Movie' : 'TV';
        const typeIcon = document.createElement('i');
        typeIcon.setAttribute('data-lucide', item.type === 'movie' ? 'film' : 'tv');
        typeIcon.setAttribute('aria-hidden', 'true');
        typeTag.appendChild(typeIcon);
        poster.appendChild(typeTag);

        const title = document.createElement('span');
        title.className = 'home-tile-title';
        title.textContent = item.title;
        title.title = item.title;

        const meta = document.createElement('span');
        meta.className = 'home-tile-meta';
        meta.textContent = item.year || 'Unknown year';

        link.appendChild(poster);
        link.appendChild(title);
        link.appendChild(meta);

        return link;
    }
};

document.addEventListener('DOMContentLoaded', () => SearchInfinite.init());
