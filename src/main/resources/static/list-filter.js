// Fork: the All / Not seen / Seen tabs and the Sort menu on /list/{id} switch in place instead of reloading the page
// (user reports 2026-11-23). They stay plain links (?show=unseen|seen, ?sort=...), so without JS, or if a fetch fails,
// they navigate normally. The swapped region is the element that holds both the progress section and the grid.
//
// History (user feedback 2026-11-23: "the back button thing is REAL annoying on the phone"): filters replace,
// navigation pushes. A tab or sort replaces this entry's URL (history.replaceState, keeping other scripts' state such
// as back-nav.js's id), so the URL still describes the view for reload and sharing, and one Back leaves the list.
// The modes (tick-off "Mark what you've seen" / Done, owner "Edit order" / Done) still load their page, but replace
// this entry too (location.replace) instead of adding one per mode switch.
(function () {
  if (!window.fetch || !window.DOMParser || !window.history || !history.replaceState) return;

  const VIEW_LINKS = '.list-filter-tabs a.status-segment, .list-sort a.list-sort-option';
  const MODE_LINKS = 'a.list-tick-start, a.list-tick-done, a.list-order-start, a.list-order-done';

  function regionOf(root) {
    const progress = root.querySelector('section.list-progress');
    if (!progress) return null;
    let el = progress.parentElement;
    while (el && el !== root && !el.querySelector('.list-grid, .empty-state')) el = el.parentElement;
    return el && el !== root ? el : progress.parentElement;
  }

  function replaceUrl(url) {
    const state = history.state;
    const keep = state && typeof state === 'object' ? state : {};
    history.replaceState(Object.assign({}, keep, { listFilter: true }), '', url);
  }

  // After the swap, focus the same control again: the active tab, or the Sort menu's trigger.
  function focusAfter(region, fromSort) {
    const target = fromSort
      ? region.querySelector('details[data-list-sort] > summary')
      : region.querySelector('.list-filter-tabs .status-segment.active');
    if (target) target.focus({ preventScroll: true });
  }

  let seq = 0;
  let inFlight = null;

  async function show(url, fromSort) {
    const region = regionOf(document);
    if (!region) { window.location.href = url; return; }
    const mine = ++seq;
    if (inFlight) inFlight.abort();
    const controller = window.AbortController ? new AbortController() : null;
    inFlight = controller;
    region.setAttribute('aria-busy', 'true');
    if (window.Loader) window.Loader.start();
    try {
      const response = await fetch(url, {
        credentials: 'same-origin',
        headers: { Accept: 'text/html' },
        signal: controller ? controller.signal : undefined,
      });
      const landed = new URL(response.url, window.location.href);
      if (!response.ok || landed.pathname !== new URL(url, window.location.href).pathname) throw new Error('not loaded');
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      if (mine !== seq) return;
      const fresh = regionOf(doc);
      if (!fresh) throw new Error('unexpected page');
      region.innerHTML = fresh.innerHTML;
      // #listRegion's own classes say how the list is shown (is-plain-list...).
      region.className = fresh.className;
      replaceUrl(url);
      if (window.lucide && window.lucide.createIcons) window.lucide.createIcons();
      focusAfter(region, fromSort);
      document.dispatchEvent(new CustomEvent('view:swapped', { detail: { url, regions: [region] } }));
    } catch (err) {
      if (err && err.name === 'AbortError') return;
      if (mine !== seq) return;
      window.location.href = url;
    } finally {
      if (mine === seq) {
        inFlight = null;
        region.removeAttribute('aria-busy');
        if (window.Loader) window.Loader.stop();
      }
    }
  }

  const plainClick = (event) =>
    !event.defaultPrevented && event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;

  document.addEventListener('click', (event) => {
    if (!event.target.closest || !plainClick(event)) return;
    const link = event.target.closest(VIEW_LINKS);
    if (link) {
      event.preventDefault();
      const menu = link.closest('details');
      if (menu) menu.removeAttribute('open');
      if (link.classList.contains('active')) return;
      show(link.href, !!menu);
      return;
    }
    const mode = event.target.closest(MODE_LINKS);
    if (mode) {
      event.preventDefault();
      window.location.replace(mode.href);
    }
  });
})();
