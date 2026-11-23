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

// Watchlist bookmark on home tiles: saves in the background and flips the button in place, so the page doesn't
// reload or jump (user report 2026-11-23). Without fetch, or if the request fails, the form posts normally and the
// scroll position is put back after the reload. Discover rows have their own handler (discover.js).
(function () {
  const KEY = 'ratelog.home.scroll';
  let toast = null;
  let toastTimer = null;

  function showToast(text) {
    if (!text) return;
    if (!toast) {
      toast = document.createElement('div');
      toast.className = 'home-toast';
      toast.setAttribute('role', 'status');
      toast.setAttribute('aria-live', 'polite');
      document.body.appendChild(toast);
    }
    toast.textContent = text;
    toast.classList.add('visible');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('visible'), 3000);
  }

  function setBookmark(form, on) {
    form.classList.toggle('is-on', on);
    const button = form.querySelector('button');
    if (!button) return;
    const label = on ? form.dataset.labelRemove : form.dataset.labelAdd;
    if (label) {
      button.title = label;
      button.setAttribute('aria-label', form.dataset.title ? label + ': ' + form.dataset.title : label);
    }
    button.setAttribute('aria-pressed', on ? 'true' : 'false');
    const icon = document.createElement('i');
    icon.setAttribute('data-lucide', on ? 'bookmark-check' : 'bookmark');
    icon.setAttribute('aria-hidden', 'true');
    button.replaceChildren(icon);
  }

  function saveScroll(form) {
    const row = form.closest('.home-row');
    const track = row && row.querySelector('.home-row-track');
    try {
      sessionStorage.setItem(KEY, JSON.stringify({ y: window.scrollY, row: row ? row.id : null, x: track ? track.scrollLeft : 0 }));
    } catch (err) { /* storage unavailable: nothing to restore */ }
  }

  document.addEventListener('submit', async (e) => {
    const form = e.target.closest && e.target.closest('form.home-quick');
    if (!form || form.closest('.discover-rows')) return;
    if (!window.fetch || !window.DOMParser) { saveScroll(form); return; }
    e.preventDefault();
    const button = form.querySelector('button');
    if (button && button.getAttribute('aria-busy') === 'true') return;
    if (button) button.setAttribute('aria-busy', 'true');
    try {
      const response = await fetch(form.action, {
        method: 'POST',
        body: new URLSearchParams(new FormData(form)),
        credentials: 'same-origin',
      });
      // Fork: rate limited (429): a toast instead of re-posting the form.
      if (response.status === 429) {
        const limited = new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('.feedback.error');
        showToast(limited ? limited.textContent.trim() : 'Too many requests');
        return;
      }
      const landed = new URL(response.url, window.location.href);
      if (!response.ok || landed.pathname === '/login') throw new Error('not saved');
      // The endpoint redirects back with a flash message; read it from the page it rendered.
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      const flash = doc.querySelector('.feedback.success, .feedback.error');
      if (flash && flash.classList.contains('error')) throw new Error('rejected');
      const on = !form.classList.contains('is-on');
      // The same title can sit in several rows (e.g. Trending and In theaters): flip all of them.
      const key = form.dataset.key;
      const forms = key ? document.querySelectorAll('form.home-quick[data-key="' + CSS.escape(key) + '"]') : [form];
      forms.forEach((f) => setBookmark(f, on));
      if (window.lucide && window.lucide.createIcons) window.lucide.createIcons();
      showToast(flash ? flash.textContent.trim() : '');
    } catch (err) {
      saveScroll(form);
      form.submit();
    } finally {
      if (button) button.removeAttribute('aria-busy');
    }
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
