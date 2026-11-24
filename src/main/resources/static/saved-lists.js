// Fork: saved lists (2026-11-24). The Save toggle on official list cards and list pages saves in place: a POST to
// /list/{id}/saved with `X-Requested-With: fetch` answers JSON, then every toggle of that list on the page flips, a card
// moves to the front of its Official section (saved) or back to its place (un-saved), and the Lists tab's "My lists"
// section is refreshed from the page so the saved card shows there with its progress. Without JS (or on any error before
// the save) the form posts normally and the page comes back with a flash message. Markup in fragments/saved-lists.html.
(function () {
  if (window.__ratelogSavedLists || !window.fetch) return;
  window.__ratelogSavedLists = true;

  let toast = null;
  let toastTimer = null;

  function showToast(text, isError) {
    if (!text) return;
    if (!toast) {
      toast = document.createElement('div');
      toast.className = 'inplace-toast';
      toast.setAttribute('role', 'status');
      toast.setAttribute('aria-live', 'polite');
      document.body.appendChild(toast);
    }
    toast.textContent = text;
    toast.classList.toggle('is-error', !!isError);
    toast.classList.add('visible');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('visible'), 3200);
  }

  const refreshIcons = () => { if (window.lucide && window.lucide.createIcons) window.lucide.createIcons(); };

  function setState(form, data) {
    form.classList.toggle('is-saved', data.saved);
    const next = form.querySelector('input[name="saved"]');
    if (next) next.value = String(!data.saved);
    const button = form.querySelector('.list-save-button');
    if (button) {
      button.setAttribute('aria-pressed', String(data.saved));
      if (data.title) { button.title = data.title; button.setAttribute('aria-label', data.title); }
    }
    const label = form.querySelector('.list-save-label');
    if (label && data.label) label.textContent = data.label;
    const wrap = form.closest('[data-saved-card]');
    if (wrap) wrap.classList.toggle('is-saved', data.saved);
  }

  // Saved: to the front (newest save first). Un-saved: after the saved cards, at its place in the section's own order.
  function reorder(key, saved) {
    document.querySelectorAll('[data-saved-first]').forEach((container) => {
      const card = [...container.children].find((el) => el.matches('[data-saved-card]') && el.dataset.listKey === key);
      if (!card) return;
      if (saved) {
        container.prepend(card);
      } else {
        const order = Number(card.dataset.baseOrder || 0);
        const rest = [...container.children].filter((el) => el !== card && !el.classList.contains('is-saved'));
        const before = rest.find((el) => Number(el.dataset.baseOrder || 0) > order);
        if (before) container.insertBefore(card, before); else container.appendChild(card);
      }
      card.classList.remove('is-moved');
      void card.offsetWidth; // restart the glow
      card.classList.add('is-moved');
      // A row scrolls sideways: bring the front back into view so the move is seen.
      if (saved && container.scrollLeft > 0 && container.scrollTo) container.scrollTo({ left: 0, behavior: 'smooth' });
    });
  }

  // The Lists tab's "My lists": swapped in from a fresh copy of the page (cards, empty state and progress stay right).
  async function refreshMine() {
    const mine = document.querySelector('[data-saved-mine]');
    if (!mine || !mine.id || !window.DOMParser) return;
    const response = await fetch(window.location.href, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
    if (!response.ok) return;
    const fresh = new DOMParser().parseFromString(await response.text(), 'text/html').getElementById(mine.id);
    if (fresh) { mine.replaceWith(document.importNode(fresh, true)); refreshIcons(); }
  }

  document.addEventListener('submit', async (event) => {
    const form = event.target.closest && event.target.closest('form[data-list-save]');
    if (!form || event.defaultPrevented) return;
    event.preventDefault();
    if (form.getAttribute('aria-busy') === 'true') return;
    const key = form.dataset.listKey;
    const forms = key ? [...document.querySelectorAll('form[data-list-save]')].filter((f) => f.dataset.listKey === key) : [form];
    forms.forEach((f) => { f.setAttribute('aria-busy', 'true'); const b = f.querySelector('button'); if (b) b.disabled = true; });
    let data = null;
    try {
      const response = await fetch(form.action, {
        method: 'POST',
        body: new URLSearchParams(new FormData(form)),
        credentials: 'same-origin',
        headers: { 'X-Requested-With': 'fetch', Accept: 'application/json' },
      });
      if (response.status === 429) {
        // Rate limited: say so; posting again would only be refused too.
        let text = 'Too many requests';
        try { const page = new DOMParser().parseFromString(await response.text(), 'text/html'); const e = page.querySelector('.feedback.error'); if (e) text = e.textContent.trim(); } catch (e) { /* keep the default */ }
        showToast(text, true);
        return;
      }
      const type = response.headers.get('content-type') || '';
      if (!type.includes('application/json')) throw new Error('not saved');
      data = await response.json();
      if (!data.ok) { showToast(data.message, true); return; }
    } catch (err) {
      forms.forEach((f) => f.removeAttribute('aria-busy'));
      form.submit();
      return;
    } finally {
      forms.forEach((f) => { f.removeAttribute('aria-busy'); const b = f.querySelector('button'); if (b) b.disabled = false; });
    }
    forms.forEach((f) => setState(f, data));
    if (key) reorder(key, data.saved);
    showToast(data.message, false);
    try { await refreshMine(); } catch (e) { /* the section catches up on the next visit */ }
  });
})();
