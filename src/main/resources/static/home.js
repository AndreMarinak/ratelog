// Home dashboard rows (fork): arrow buttons page a row left/right; touch and trackpads scroll natively.
(function () {
  function setup(row) {
    const track = row.querySelector('.home-row-track');
    const prev = row.querySelector('[data-row-prev]');
    const next = row.querySelector('[data-row-next]');
    if (!track || !prev || !next) return;

    function update() {
      const max = track.scrollWidth - track.clientWidth;
      row.classList.toggle('is-scrollable', max > 4);
      prev.disabled = track.scrollLeft <= 4;
      next.disabled = track.scrollLeft >= max - 4;
    }

    function page(direction) {
      const smooth = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      track.scrollBy({ left: direction * Math.max(track.clientWidth * 0.85, 160), behavior: smooth ? 'smooth' : 'auto' });
    }

    prev.addEventListener('click', () => page(-1));
    next.addEventListener('click', () => page(1));
    track.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update);
    window.addEventListener('load', update);
    update();
  }

  document.querySelectorAll('.home-row').forEach(setup);
})();

// The watchlist button posts and comes back to the top of the page; put the page and the row back where they were.
(function () {
  const KEY = 'ratelog.home.scroll';

  document.addEventListener('submit', (e) => {
    const form = e.target.closest && e.target.closest('.home-quick');
    if (!form) return;
    const row = form.closest('.home-row');
    const track = row && row.querySelector('.home-row-track');
    try {
      sessionStorage.setItem(KEY, JSON.stringify({ y: window.scrollY, row: row ? row.id : null, x: track ? track.scrollLeft : 0 }));
    } catch (err) { /* storage unavailable: nothing to restore */ }
  });

  let saved = null;
  try {
    saved = JSON.parse(sessionStorage.getItem(KEY) || 'null');
    sessionStorage.removeItem(KEY);
  } catch (err) { saved = null; }
  if (!saved) return;

  const track = saved.row && document.querySelector('#' + CSS.escape(saved.row) + ' .home-row-track');
  if (track) {
    track.style.scrollBehavior = 'auto';
    track.scrollLeft = saved.x || 0;
    track.style.scrollBehavior = '';
  }
  window.scrollTo({ top: saved.y || 0, behavior: 'instant' });
})();
