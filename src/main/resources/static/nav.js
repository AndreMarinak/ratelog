(function () {
  const btn = document.getElementById('btnNavSettings');
  const sheet = document.getElementById('navSettingsSheet');
  const backdrop = document.getElementById('navSheetBackdrop');
  const btnClose = document.getElementById('btnNavSettingsClose');
  if (!btn || !sheet || !backdrop) return;

  function isOpen() {
    return btn.getAttribute('aria-expanded') === 'true';
  }

  function open() {
    backdrop.hidden = false;
    sheet.hidden = false;
    void sheet.offsetWidth;
    backdrop.classList.add('open');
    sheet.classList.add('open');
    btn.setAttribute('aria-expanded', 'true');
  }

  function close() {
    if (!isOpen()) return;
    function onTransitionEnd(e) {
      if (e.propertyName !== 'transform') return;
      sheet.removeEventListener('transitionend', onTransitionEnd);
      sheet.hidden = true;
      backdrop.hidden = true;
    }
    sheet.addEventListener('transitionend', onTransitionEnd);
    backdrop.classList.remove('open');
    sheet.classList.remove('open');
    btn.setAttribute('aria-expanded', 'false');
  }

  btn.addEventListener('click', () => isOpen() ? close() : open());
  if (btnClose) btnClose.addEventListener('click', close);
  backdrop.addEventListener('click', close);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
})();

// Fork: "/" focuses the header search (unless you're typing somewhere); Escape leaves it.
(function () {
  const input = document.getElementById('navSearchInput');
  if (!input) return;

  function isTyping(el) {
    if (!el) return false;
    const tag = el.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
  }

  document.addEventListener('keydown', (e) => {
    if (e.key === '/' && !e.ctrlKey && !e.metaKey && !e.altKey && !isTyping(e.target)) {
      if (document.querySelector('.modal.visible')) return;
      e.preventDefault();
      input.focus();
      input.select();
    } else if (e.key === 'Escape' && document.activeElement === input) {
      input.blur();
    }
  });

  input.form.addEventListener('submit', (e) => {
    if (!input.value.trim()) e.preventDefault();
  });
})();
