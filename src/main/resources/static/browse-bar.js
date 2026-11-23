// Fork: browse bar menus (templates/fragments/browse-bar.html). The menus are <details> elements full of
// plain links, so they work without this file; this only keeps one open at a time, closes them on an outside
// click or Escape, and nudges a panel back on screen when it would overflow the viewport.
(() => {
    if (window.__ratelogBrowseBar) return;
    window.__ratelogBrowseBar = true;

    const MARGIN = 12;
    const menus = () => document.querySelectorAll('.browse-bar details.bb-menu');

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
    }

    // "toggle" doesn't bubble: listen in the capture phase.
    document.addEventListener('toggle', (event) => {
        const details = event.target;
        if (!(details instanceof HTMLDetailsElement) || !details.classList.contains('bb-menu')) return;
        if (!details.open) return;
        menus().forEach((other) => { if (other !== details) other.open = false; });
        keepOnScreen(details);
    }, true);

    document.addEventListener('click', (event) => {
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
