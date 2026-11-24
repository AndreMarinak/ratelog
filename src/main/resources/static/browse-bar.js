// Fork: browse bar menus (templates/fragments/browse-bar.html). The menus are <details> elements full of
// plain links, so they work without this file; this only keeps one open at a time, closes them on an outside
// click or Escape, and nudges a panel back on screen when it would overflow the viewport.
// Fork (2026-11-24): the Service menu's "Show all services" search box (hidden without JS) filters that list as you
// type, and keeps its text when the menu comes back open after a pick (view-swap.js re-opens keep-open menus); a
// checkbox option ticks the moment it is clicked, before the in-place swap lands.
(() => {
    if (window.__ratelogBrowseBar) return;
    window.__ratelogBrowseBar = true;

    const MARGIN = 12;
    const menus = () => document.querySelectorAll('.browse-bar details.bb-menu');
    const queries = {}; // search text per menu id, while that menu is open

    const fold = (text) => (text || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '')
        .replace(/[^a-z0-9]+/g, ' ').trim();

    function applySearch(input) {
        const list = input.closest('.bb-more');
        if (!list) return;
        const query = fold(input.value);
        let shown = 0;
        list.querySelectorAll('.bb-options > li').forEach((item) => {
            const label = item.querySelector('.bb-option-label');
            const match = !query || fold(label && label.textContent).includes(query);
            item.hidden = !match;
            if (match) shown += 1;
        });
        const none = list.querySelector('[data-bb-no-match]');
        if (none) none.hidden = shown > 0;
    }

    function enableSearch() {
        document.querySelectorAll('.browse-bar [data-bb-search]').forEach((box) => {
            box.hidden = false;
            const menu = box.closest('details[data-menu]');
            const input = box.querySelector('input');
            const query = menu ? queries[menu.dataset.menu] : '';
            if (input && query) {
                input.value = query;
                applySearch(input);
            }
        });
    }
    enableSearch();
    document.addEventListener('view:swapped', enableSearch);

    document.addEventListener('input', (event) => {
        const input = event.target;
        if (!(input instanceof HTMLInputElement) || !input.classList.contains('bb-search-input')) return;
        applySearch(input);
        const menu = input.closest('details[data-menu]');
        if (menu) queries[menu.dataset.menu] = input.value;
    });

    function keepOnScreen(details) {
        const panel = details.querySelector(':scope > .bb-panel');
        if (!panel) return;
        panel.style.transform = '';
        const rect = panel.getBoundingClientRect();
        const width = document.documentElement.clientWidth;
        let dx = 0;
        if (rect.right > width - MARGIN) dx = (width - MARGIN) - rect.right;
        if (rect.left + dx < MARGIN) dx = MARGIN - rect.left;
        if (dx) panel.style.transform = `translateX(${Math.round(dx)}px)`;
        fitHeight(panel);
    }

    // Fork (2026-11-24): a panel ends above the phone's fixed bottom nav (it used to run under it, so its last rows,
    // e.g. the full service list, couldn't be tapped). Short of room, the page scrolls up a little first.
    const MIN_PANEL = 240;
    function fitHeight(panel) {
        panel.style.maxHeight = '';
        const nav = document.querySelector('.bottom-nav');
        const navTop = nav && nav.getClientRects().length && getComputedStyle(nav).position === 'fixed'
            ? nav.getBoundingClientRect().top : window.innerHeight;
        const limit = navTop - MARGIN;
        let rect = panel.getBoundingClientRect();
        if (rect.bottom <= limit) return;
        const short = MIN_PANEL - (limit - rect.top);
        if (short > 0) {
            window.scrollBy(0, Math.max(0, Math.min(short, rect.top - 120)));
            rect = panel.getBoundingClientRect();
        }
        panel.style.maxHeight = `${Math.max(Math.min(MIN_PANEL, rect.height), Math.floor(limit - rect.top))}px`;
    }

    // "toggle" doesn't bubble: listen in the capture phase.
    document.addEventListener('toggle', (event) => {
        const details = event.target;
        if (!(details instanceof HTMLDetailsElement)) return;
        // "Show all services" changes the panel's height: fit it again.
        if (details.classList.contains('bb-more')) {
            const menu = details.closest('details.bb-menu');
            if (menu && menu.open) keepOnScreen(menu);
            return;
        }
        if (!details.classList.contains('bb-menu')) return;
        if (!details.open) {
            delete queries[details.dataset.menu];
            return;
        }
        menus().forEach((other) => { if (other !== details) other.open = false; });
        keepOnScreen(details);
    }, true);

    document.addEventListener('click', (event) => {
        const option = event.target.closest && event.target.closest('.browse-bar a.bb-option[data-keep-open]');
        if (option && option.querySelector('.bb-tick') && event.button === 0 &&
            !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
            option.classList.toggle('active');
        }
        menus().forEach((details) => {
            if (details.open && !details.contains(event.target)) details.open = false;
        });
    });

    document.addEventListener('keydown', (event) => {
        if (event.key !== 'Escape') return;
        menus().forEach((details) => {
            if (!details.open) return;
            details.open = false;
            details.querySelector(':scope > summary')?.focus();
        });
    });

    window.addEventListener('resize', () => menus().forEach((details) => details.open && keepOnScreen(details)));
})();
