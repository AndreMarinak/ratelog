// Fork: small actions save without reloading the page (user request 2026-11-23: "search through what causes
// refreshes"). Two opt-in hooks, both progressive enhancement (no JS or any error -> normal form post / link):
//
// <form data-inplace="<selector>[, <selector>...]" [data-inplace-done="message"]>
//   POSTs in the background. When it succeeds, the current page is fetched again and each listed region is
//   swapped in from it, so counts, badges and button states stay right without per-feature JS. An empty
//   data-inplace swaps nothing. The server's flash message (or data-inplace-done) shows as a toast. A form inside
//   an open modal closes that modal first. After a swap, `inplace:swapped` fires on document.
//   Fork (2026-11-24, rewatch log): a flash can carry actions. Its [data-toast-text] is the message and every
//   [data-toast-action] (an Undo form with its own data-inplace, a link) is copied into the toast, which then stays
//   longer and takes clicks. A copied link with data-toast-click="<selector>" clicks that control on the page instead
//   of following its href (e.g. the panel's Rate button); with nothing matching, the href loads.
//
// <a data-append="<selector>">  ("Load more")
//   Fetches the link, appends the children of <selector> from that page to <selector> here, and replaces the
//   link with that page's own link (or removes it on the last page).
(function () {
  if (!window.fetch || !window.DOMParser) return;

  let toast = null;
  let toastTimer = null;

  function showToast(text, isError, actions) {
    if (!text) return;
    if (!toast) {
      toast = document.createElement('div');
      toast.className = 'inplace-toast';
      toast.setAttribute('role', 'status');
      toast.setAttribute('aria-live', 'polite');
      document.body.appendChild(toast);
    }
    const message = document.createElement('span');
    message.className = 'inplace-toast-text';
    message.textContent = text;
    const extra = actions || [];
    toast.replaceChildren(message, ...extra);
    toast.classList.toggle('is-error', !!isError);
    toast.classList.toggle('has-actions', extra.length > 0);
    toast.classList.add('visible');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('visible'), extra.length ? 7000 : 3200);
  }

  /** The flash's message, and copies of its [data-toast-action] controls for the toast. */
  function readFlash(flash) {
    if (!flash) return { text: '', actions: [] };
    const textEl = flash.querySelector('[data-toast-text]') || flash;
    const actions = [...flash.querySelectorAll('[data-toast-action]')].map((el) => document.importNode(el, true));
    return { text: textEl.textContent.trim(), actions };
  }

  const parse = (html) => new DOMParser().parseFromString(html, 'text/html');
  const refreshIcons = () => { if (window.lucide && window.lucide.createIcons) window.lucide.createIcons(); };

  async function getPage(url) {
    const response = await fetch(url, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
    const landed = new URL(response.url, window.location.href);
    if (!response.ok || landed.pathname === '/login') throw new Error('not loaded');
    return parse(await response.text());
  }

  function swapRegions(selectors, fresh) {
    for (const selector of selectors) {
      const current = [...document.querySelectorAll(selector)];
      const next = [...fresh.querySelectorAll(selector)];
      if (current.length !== next.length) return false;
      current.forEach((el, i) => el.replaceWith(document.importNode(next[i], true)));
    }
    return true;
  }

  document.addEventListener('submit', async (event) => {
    const form = event.target.closest && event.target.closest('form[data-inplace]');
    // confirm.js cancels in the capture phase when the user says no.
    if (!form || event.defaultPrevented) return;
    event.preventDefault();
    const submitter = event.submitter || form.querySelector('[type=submit]');
    if (form.getAttribute('aria-busy') === 'true') return;
    form.setAttribute('aria-busy', 'true');
    if (submitter) submitter.disabled = true;
    let posted = false;
    try {
      const body = new URLSearchParams(new FormData(form));
      if (submitter && submitter.name) body.append(submitter.name, submitter.value);
      const response = await fetch(form.action, { method: 'POST', body, credentials: 'same-origin' });
      // Fork: rate limited (429): say so in a toast; posting the form again would only be refused too.
      if (response.status === 429) {
        const limited = parse(await response.text()).querySelector('.feedback.error');
        showToast(limited ? limited.textContent.trim() : 'Too many requests', true);
        return;
      }
      const landed = new URL(response.url, window.location.href);
      if (!response.ok || landed.pathname === '/login') throw new Error('not saved');
      posted = true;
      const flash = parse(await response.text()).querySelector('.feedback.success, .feedback.error');
      const isError = !!(flash && flash.classList.contains('error'));

      const modal = form.closest('.modal.visible');
      if (modal) { const close = modal.querySelector('[data-close-modal]'); if (close) close.click(); }

      const selectors = (form.dataset.inplace || '').split(',').map((s) => s.trim()).filter(Boolean);
      if (selectors.length) {
        const fresh = await getPage(window.location.href);
        if (!swapRegions(selectors, fresh)) { window.location.reload(); return; }
        refreshIcons();
        // Scripts that draw into a region (breakdown.js's category bars) redraw the swapped-in copies.
        document.dispatchEvent(new CustomEvent('inplace:swapped', { detail: { selectors } }));
      }
      const read = readFlash(flash);
      showToast(flash ? read.text : (isError ? '' : form.dataset.inplaceDone || ''), isError, read.actions);
    } catch (err) {
      // Not saved: post the form the normal way. Saved but the refresh failed: reload, never post twice.
      form.removeAttribute('aria-busy');
      if (posted) window.location.reload(); else form.submit();
      return;
    } finally {
      if (submitter) submitter.disabled = false;
      form.removeAttribute('aria-busy');
    }
  });

  // A toast's "Update your rating?": click the page's own control (opens the rate modal) rather than load the href.
  document.addEventListener('click', (event) => {
    const action = event.target.closest && event.target.closest('.inplace-toast a[data-toast-click]');
    if (!action || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey) return;
    const target = document.querySelector(action.dataset.toastClick);
    if (!target) return;
    event.preventDefault();
    clearTimeout(toastTimer);
    if (toast) toast.classList.remove('visible');
    target.click();
  });

  document.addEventListener('click', async (event) => {
    const link = event.target.closest && event.target.closest('a[data-append]');
    if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey) return;
    const target = document.querySelector(link.dataset.append);
    if (!target) return;
    event.preventDefault();
    if (link.getAttribute('aria-busy') === 'true') return;
    link.setAttribute('aria-busy', 'true');
    try {
      const doc = await getPage(link.href);
      const more = doc.querySelector(link.dataset.append);
      if (more) [...more.children].forEach((child) => target.appendChild(document.importNode(child, true)));
      const nextLink = doc.querySelector('a[data-append="' + CSS.escape(link.dataset.append) + '"]');
      if (nextLink) link.replaceWith(document.importNode(nextLink, true)); else link.remove();
      refreshIcons();
    } catch (err) {
      window.location.href = link.href;
    } finally {
      link.removeAttribute('aria-busy');
    }
  });
})();
