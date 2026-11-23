// Fork: navigation progress (replaces upstream's full-screen loader, user request 2026-11-23). Nothing covers the
// page: a thin bar along the top edge (#loader, fragments/loader.html) appears only when a navigation or an
// in-place load takes longer than DELAY_MS, so ordinary page loads show nothing at all.
//
// Loader.start() / Loader.stop() are also used by in-place loads (ratings-filters.js).
(function () {
  const DELAY_MS = 400;
  // A link that starts a download, or a navigation the browser cancels, never unloads the page: stop the bar.
  const GIVE_UP_MS = 15000;
  let showTimer = null;
  let giveUpTimer = null;

  function bar() {
    return document.getElementById('loader');
  }

  function stop() {
    clearTimeout(showTimer);
    clearTimeout(giveUpTimer);
    showTimer = null;
    giveUpTimer = null;
    const el = bar();
    if (el) el.classList.remove('visible');
  }

  function start() {
    stop();
    showTimer = setTimeout(() => {
      const el = bar();
      if (el) el.classList.add('visible');
      giveUpTimer = setTimeout(stop, GIVE_UP_MS);
    }, DELAY_MS);
  }

  window.Loader = { start, stop };

  window.addEventListener('beforeunload', start);
  // Back / Forward can restore this page from the bfcache with the bar still running.
  window.addEventListener('pageshow', stop);
  window.addEventListener('load', stop);
})();
