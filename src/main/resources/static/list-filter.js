// Fork: the All / Not seen / Seen tabs on /list/{id} switch in place instead of reloading the page
// (user report 2026-11-23). The tabs stay plain links (?show=unseen|seen), so without JS, or if a fetch fails,
// they navigate normally. The swapped region is the element that holds both the progress section and the grid.
(function () {
  if (!window.fetch || !window.DOMParser || !window.history || !history.pushState) return;

  function regionOf(root) {
    const progress = root.querySelector('section.list-progress');
    if (!progress) return null;
    let el = progress.parentElement;
    while (el && el !== root && !el.querySelector('.list-grid, .empty-state')) el = el.parentElement;
    return el && el !== root ? el : progress.parentElement;
  }

  async function show(url, push) {
    const region = regionOf(document);
    if (!region) { window.location.href = url; return; }
    region.setAttribute('aria-busy', 'true');
    try {
      const response = await fetch(url, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
      const landed = new URL(response.url, window.location.href);
      if (!response.ok || landed.pathname === '/login') throw new Error('not loaded');
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      const fresh = regionOf(doc);
      if (!fresh) throw new Error('unexpected page');
      region.innerHTML = fresh.innerHTML;
      if (push) history.pushState({ listFilter: true }, '', url);
      if (window.lucide && window.lucide.createIcons) window.lucide.createIcons();
      const active = region.querySelector('.list-filter-tabs .status-segment.active');
      if (active) active.focus({ preventScroll: true });
    } catch (err) {
      window.location.href = url;
    } finally {
      region.removeAttribute('aria-busy');
    }
  }

  document.addEventListener('click', (event) => {
    const tab = event.target.closest && event.target.closest('.list-filter-tabs a.status-segment');
    if (!tab || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    if (tab.classList.contains('active')) return;
    show(tab.href, true);
  });

  window.addEventListener('popstate', () => {
    if (regionOf(document)) show(window.location.href, false);
  });
})();
