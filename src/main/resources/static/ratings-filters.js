// Fork: the Ratings tab's filters (/movies, /tvshows) apply as soon as they change, and each filter with a value gets
// an ✕ that clears just that one (user request 2026-11-23: "should just auto do it not require me to click search ...
// x on each dropdown when something selected").
//
// - A dropdown applies on change; the name field after a short pause in typing (DEBOUNCE_MS) or on Enter.
// - The ranking is swapped in place (the page is fetched and the part after the form replaced) and the URL is
//   replaced (history.replaceState, keeping the entry's state such as back-nav.js's id), so it still describes the
//   view for reload and sharing, but filters add no history entries: one Back leaves Ratings (user feedback
//   2026-11-23: "the back button thing is REAL annoying on the phone"). Rule: filters replace, navigation pushes.
// - The URL only carries filters that are set (no "?category=&name=&limit=10").
// - Progressive enhancement: without JS the form and its Search button work as before; with JS the button is hidden
//   (.is-live) and any failed fetch falls back to loading the URL.
(function () {
  if (!window.fetch || !window.DOMParser || !window.history || !history.replaceState || !window.URLSearchParams) return;

  const FORM = '.ratings-switch ~ .grid form.filter';
  const DEBOUNCE_MS = 450;
  // What the server uses when a parameter is missing; a control at its default is "not set" (no ✕, not in the URL).
  const DEFAULTS = { limit: '10' };

  const form = document.querySelector(FORM);
  if (!form) return;

  const script = document.currentScript || document.querySelector('script[data-clear-label]');
  const clearLabel = (script && script.dataset.clearLabel) || 'Remove filter: {0}';
  const path = new URL(form.getAttribute('action') || window.location.pathname, window.location.href).pathname;
  const controls = [...form.querySelectorAll('select[name], input[name][type="text"]')];
  const isText = (control) => control.tagName === 'INPUT';
  const defaultOf = (control) => DEFAULTS[control.name] || '';
  const valueOf = (control) => (isText(control) ? control.value.trim() : control.value);
  const isSet = (control) => valueOf(control) !== '' && valueOf(control) !== defaultOf(control);

  let seq = 0;
  let inFlight = null;
  let typingTimer = null;

  function shownValue(control) {
    if (isText(control)) return control.value.trim();
    const option = control.options[control.selectedIndex];
    return option ? option.textContent.trim() : control.value;
  }

  function refresh(control) {
    const field = control.parentElement;
    const clear = field.querySelector('.filter-clear');
    const set = isSet(control);
    field.classList.toggle('has-value', set);
    clear.hidden = !set;
    if (set) {
      const label = clearLabel.replace('{0}', shownValue(control));
      clear.setAttribute('aria-label', label);
      clear.title = label;
    }
  }

  function urlFor() {
    const params = new URLSearchParams();
    controls.forEach((control) => { if (isSet(control)) params.set(control.name, valueOf(control)); });
    const query = params.toString();
    return path + (query ? '?' + query : '');
  }

  // Everything after the form in its column: the ranking, or the "no rated titles" message.
  function resultsOf(formEl) {
    const out = [];
    for (let el = formEl.nextElementSibling; el; el = el.nextElementSibling) out.push(el);
    return out;
  }

  function setBusy(busy) {
    resultsOf(form).forEach((el) => {
      el.classList.toggle('ratings-loading', busy);
      if (busy) el.setAttribute('aria-busy', 'true'); else el.removeAttribute('aria-busy');
    });
    if (window.Loader) (busy ? window.Loader.start : window.Loader.stop)();
  }

  // Replace this entry's URL; other scripts' keys in history.state (back-nav.js's backNav) are kept.
  function replaceUrl(url) {
    const state = history.state;
    const keep = state && typeof state === 'object' ? state : {};
    history.replaceState(Object.assign({}, keep, { ratingsFilters: true }), '', url);
  }

  async function load(url) {
    const mine = ++seq;
    if (inFlight) inFlight.abort();
    const controller = window.AbortController ? new AbortController() : null;
    inFlight = controller;
    setBusy(true);
    try {
      const response = await fetch(url, {
        credentials: 'same-origin',
        headers: { Accept: 'text/html' },
        signal: controller ? controller.signal : undefined,
      });
      const landed = new URL(response.url, window.location.href);
      if (!response.ok || landed.pathname !== path) throw new Error('not loaded');
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      if (mine !== seq) return;
      const fresh = doc.querySelector(FORM);
      if (!fresh) throw new Error('unexpected page');

      const current = resultsOf(form);
      const parent = form.parentNode;
      current.forEach((el) => el.remove());
      resultsOf(fresh).forEach((el) => parent.appendChild(document.importNode(el, true)));

      replaceUrl(url);
      if (window.lucide && window.lucide.createIcons) window.lucide.createIcons();
    } catch (err) {
      if (err && err.name === 'AbortError') return;
      if (mine !== seq) return;
      window.location.assign(url);
    } finally {
      if (mine === seq) {
        inFlight = null;
        setBusy(false);
      }
    }
  }

  function apply() {
    clearTimeout(typingTimer);
    typingTimer = null;
    const url = urlFor();
    if (url === window.location.pathname + window.location.search) {
      // Back to what is shown (e.g. a filter picked and put back quickly): drop a load still on its way.
      if (inFlight) {
        seq += 1;
        inFlight.abort();
        inFlight = null;
        setBusy(false);
      }
      return;
    }
    load(url);
  }

  // Wrap each control so its ✕ can sit inside it (ratings.css). The no-JS markup stays untouched.
  controls.forEach((control) => {
    const field = document.createElement('span');
    field.className = 'filter-field';
    field.dataset.filter = control.name;
    control.parentNode.insertBefore(field, control);
    field.appendChild(control);

    const clear = document.createElement('button');
    clear.type = 'button';
    clear.className = 'filter-clear';
    clear.hidden = true;
    const icon = document.createElement('i');
    icon.setAttribute('data-lucide', 'x');
    icon.setAttribute('aria-hidden', 'true');
    clear.appendChild(icon);
    clear.addEventListener('click', () => {
      control.value = defaultOf(control);
      refresh(control);
      control.focus({ preventScroll: true });
      apply();
    });
    field.appendChild(clear);
    refresh(control);

    if (isText(control)) {
      control.addEventListener('input', () => {
        refresh(control);
        clearTimeout(typingTimer);
        typingTimer = setTimeout(apply, DEBOUNCE_MS);
      });
      // Leaving the field applies what was typed at once.
      control.addEventListener('blur', () => {
        if (typingTimer) apply();
      });
      control.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter' || event.isComposing) return;
        event.preventDefault();
        apply();
      });
    } else {
      control.addEventListener('change', () => {
        refresh(control);
        apply();
      });
    }
  });

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    apply();
  });
  form.classList.add('is-live');
  if (window.lucide && window.lucide.createIcons && document.readyState !== 'loading') window.lucide.createIcons();
})();
