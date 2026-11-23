// Fork: /browse enhancements. Without JS, "Load more" opens the next page and the bookmark buttons post normally.
// - Load more: fetches the next page's results fragment (?partial=1) and appends its tiles.
// - Bookmark: posts /watchlist/toggle in the background and flips the button, keeping scroll and loaded pages.
(() => {
    const grid = document.getElementById('browseGrid');
    const more = document.getElementById('browseMore');
    const toast = document.getElementById('browseToast');
    if (!grid || !more || !window.fetch || !window.DOMParser) return;

    const parse = (html) => new DOMParser().parseFromString(html, 'text/html');
    const refreshIcons = () => { if (window.lucide) window.lucide.createIcons(); };

    // --- Load more ---

    more.addEventListener('click', (event) => {
        const trigger = event.target.closest('a[data-load-more], button[data-retry-href]');
        if (!trigger) return;
        event.preventDefault();
        loadMore(trigger.dataset.retryHref || trigger.getAttribute('href'), trigger);
    });

    async function loadMore(href, trigger) {
        trigger.setAttribute('aria-busy', 'true');
        const label = trigger.querySelector('span') || trigger;
        const original = label.textContent;
        label.textContent = more.dataset.loading || original;
        try {
            const url = new URL(href, window.location.href);
            url.searchParams.set('partial', '1');
            const response = await fetch(url, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
            const doc = parse(await response.text());
            const results = doc.getElementById('browseResults');
            // Logged out (login page) or something unexpected: let the browser open the page itself.
            if (!response.ok || !results) {
                window.location.assign(href);
                return;
            }
            if (results.querySelector('.browse-message')) {
                showLoadError(href);
                return;
            }
            const seen = new Set([...grid.querySelectorAll('.browse-tile')].map((tile) => tile.dataset.key));
            let firstNew = null;
            results.querySelectorAll('#browseGrid > .browse-tile').forEach((tile) => {
                if (seen.has(tile.dataset.key)) return;
                const node = document.importNode(tile, true);
                grid.appendChild(node);
                firstNew = firstNew || node;
            });
            const nextMore = results.querySelector('#browseMore');
            const nextLink = nextMore && nextMore.querySelector('a[data-load-more]');
            more.replaceChildren(...(nextLink ? [document.importNode(nextLink, true)] : []));
            refreshIcons();
            if (firstNew) firstNew.querySelector('a')?.focus({ preventScroll: true });
        } catch (error) {
            showLoadError(href);
        } finally {
            trigger.removeAttribute('aria-busy');
            if (trigger.isConnected) label.textContent = original;
        }
    }

    function showLoadError(href) {
        const message = document.createElement('p');
        message.className = 'browse-message';
        message.setAttribute('role', 'alert');
        const text = document.createElement('span');
        text.textContent = more.dataset.error || 'Something went wrong.';
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'secondary';
        retry.dataset.retryHref = href;
        retry.textContent = more.dataset.retry || 'Try again';
        message.append(text, retry);
        more.replaceChildren(message);
    }

    // --- Bookmark (Want to watch) ---

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
        button.setAttribute('aria-label', label);
        button.title = label;
        const icon = document.createElement('i');
        icon.setAttribute('data-lucide', on ? 'bookmark-check' : 'bookmark');
        icon.setAttribute('aria-hidden', 'true');
        button.replaceChildren(icon);
        refreshIcons();
    }

    grid.addEventListener('submit', async (event) => {
        const form = event.target.closest('form.browse-bookmark');
        if (!form) return;
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
            const landed = new URL(response.url, window.location.href);
            if (!response.ok || landed.pathname === '/login') throw new Error('not saved');
            // The endpoint redirects back here with a flash message; read it from the page it rendered.
            const doc = parse(await response.text());
            const flash = doc.querySelector('.feedback.success, .feedback.error');
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
})();
