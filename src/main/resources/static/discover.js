// Fork: the Discover landing (templates/discover.html + fragments/discover-rows.html). Without JS the rows below the
// fold stay as headings with a "More" link, and bookmarks / the "Hide titles I've seen" switch work as plain requests.
// - Rows: arrow buttons page a row left/right (touch and trackpads scroll natively).
// - Pending rows: fetched from /discover/row as they near the viewport, at most 3 at a time; a row that fails is removed.
// - Bookmark: posts /watchlist/toggle in the background and flips the button, keeping the page as it is.
// - The seen switch is a link; Space toggles it like a real switch.
(() => {
    const parse = (html) => new DOMParser().parseFromString(html, 'text/html');
    const refreshIcons = () => { if (window.lucide) window.lucide.createIcons(); };

    // --- Row arrows ---

    function setupRow(row) {
        if (row.dataset.rowReady) return;
        const track = row.querySelector('.home-row-track');
        const prev = row.querySelector('[data-row-prev]');
        const next = row.querySelector('[data-row-next]');
        if (!track || !prev || !next) return;
        row.dataset.rowReady = '1';

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
        row.discoverUpdate = update;
        update();
    }

    const readyRows = () => document.querySelectorAll('.discover-row:not(.is-pending)');
    readyRows().forEach(setupRow);
    window.addEventListener('resize', () => readyRows().forEach((row) => row.discoverUpdate && row.discoverUpdate()));
    window.addEventListener('load', () => readyRows().forEach((row) => row.discoverUpdate && row.discoverUpdate()));

    // --- Pending rows ---

    const MAX_LOADS = 3;
    const queue = [];
    let loading = 0;

    function enqueue(row) {
        if (row.dataset.queued) return;
        row.dataset.queued = '1';
        queue.push(row);
        pump();
    }

    function pump() {
        while (loading < MAX_LOADS && queue.length) {
            const row = queue.shift();
            loading++;
            loadRow(row).finally(() => {
                loading--;
                pump();
            });
        }
    }

    async function loadRow(row) {
        try {
            const response = await fetch(row.dataset.src, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
            if (!response.ok) throw new Error('row failed');
            const fresh = parse(await response.text()).querySelector('.discover-row');
            // Nothing to show (TMDB failed, every title hidden) or logged out (the login page): drop the row.
            if (!fresh || fresh.classList.contains('is-pending')) {
                row.remove();
                return;
            }
            const node = document.importNode(fresh, true);
            row.replaceWith(node);
            refreshIcons();
            setupRow(node);
        } catch (error) {
            row.remove();
        }
    }

    const pending = [...document.querySelectorAll('.discover-row.is-pending[data-src]')];
    if (pending.length && window.fetch && window.DOMParser) {
        if ('IntersectionObserver' in window) {
            const observer = new IntersectionObserver((entries) => {
                entries.forEach((entry) => {
                    if (!entry.isIntersecting) return;
                    observer.unobserve(entry.target);
                    enqueue(entry.target);
                });
            }, { rootMargin: '800px 0px' });
            pending.forEach((row) => observer.observe(row));
        } else {
            pending.forEach(enqueue);
        }
    }

    // --- Bookmark (Want to watch) ---

    const toast = document.getElementById('browseToast');
    let toastTimer = null;
    function showToast(text) {
        if (!toast || !text) return;
        toast.textContent = text;
        toast.classList.add('visible');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => toast.classList.remove('visible'), 3200);
    }

    function setBookmark(form, on) {
        form.classList.toggle('is-on', on);
        form.dataset.status = on ? 'want_to_watch' : '';
        const button = form.querySelector('button');
        const label = on ? form.dataset.labelRemove : form.dataset.labelAdd;
        button.title = label;
        button.setAttribute('aria-label', form.dataset.title ? `${label}: ${form.dataset.title}` : label);
        button.setAttribute('aria-pressed', on ? 'true' : 'false');
        const icon = document.createElement('i');
        icon.setAttribute('data-lucide', on ? 'bookmark-check' : 'bookmark');
        icon.setAttribute('aria-hidden', 'true');
        button.replaceChildren(icon);
        refreshIcons();
    }

    document.addEventListener('submit', async (event) => {
        const form = event.target.closest && event.target.closest('.discover-rows form.home-quick');
        if (!form || !window.fetch) return;
        event.preventDefault();
        const button = form.querySelector('button');
        if (button.getAttribute('aria-busy') === 'true') return;
        button.setAttribute('aria-busy', 'true');
        try {
            const response = await fetch(form.action, {
                method: 'POST',
                body: new URLSearchParams(new FormData(form)),
                credentials: 'same-origin',
            });
            // Fork: rate limited (429): a toast instead of re-posting the form.
            if (response.status === 429) {
                const limited = parse(await response.text()).querySelector('.feedback.error');
                showToast(limited ? limited.textContent.trim() : 'Too many requests');
                return;
            }
            const landed = new URL(response.url, window.location.href);
            if (!response.ok || landed.pathname === '/login') throw new Error('not saved');
            // The endpoint redirects back here with a flash message; read it from the page it rendered.
            const flash = parse(await response.text()).querySelector('.feedback.success, .feedback.error');
            if (!flash || flash.classList.contains('success')) {
                // Only "Want to watch" toggles; Watching / Watched / Dropped are left as they are.
                const status = form.dataset.status || '';
                if (status === 'want_to_watch') setBookmark(form, false);
                else if (status === '') setBookmark(form, true);
            }
            showToast(flash ? flash.textContent.trim() : '');
        } catch (error) {
            form.submit();
        } finally {
            button.removeAttribute('aria-busy');
        }
    });

    // --- "Hide titles I've seen" switch ---

    document.querySelectorAll('[data-seen-toggle]').forEach((link) => {
        link.addEventListener('keydown', (event) => {
            if (event.key !== ' ') return;
            event.preventDefault();
            link.click();
        });
    });
})();
