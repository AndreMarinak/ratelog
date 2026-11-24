// Fork: Follow / Following buttons (templates/fragments/follow-button.html, static/follow.css).
// inplace.js saves the follow and swaps in the new button, which lands right under the pointer (and keyboard focus),
// where a "Following" button reads "Unfollow". Right after you follow someone that would look like the follow failed,
// so the new button keeps reading "Following" (.follow-hold) until the pointer leaves it or focus moves on. Focus is
// put back on the new button so keyboard users don't lose their place. Unfollow confirmation on touch: confirm.js.
(function () {
  if (!window.MutationObserver) return;

  let pending = null;
  let observer = null;
  let timer = null;

  function stop() {
    pending = null;
    if (observer) observer.disconnect();
    clearTimeout(timer);
  }

  function check() {
    if (!pending) return;
    const fresh = document.getElementById(pending.id);
    if (!fresh || fresh === pending.form) return;
    const button = fresh.querySelector('.follow-btn');
    const refocus = pending.focused;
    stop();
    if (!button) return;
    button.classList.add('follow-hold');
    if (refocus) button.focus({ preventScroll: true });
    const release = () => button.classList.remove('follow-hold');
    button.addEventListener('pointerleave', release, { once: true });
    button.addEventListener('blur', release, { once: true });
  }

  // Bubble phase: a cancelled confirm (confirm.js, capture phase) never gets here.
  document.addEventListener('submit', (event) => {
    const form = event.target.closest && event.target.closest('form.follow-form[data-inplace]');
    // getAttribute: the form has an <input name="id">, which shadows form.id.
    const id = form && form.getAttribute('id');
    if (!id) return;
    stop();
    pending = { id, form, focused: form.contains(document.activeElement) };
    observer = observer || new MutationObserver(check);
    observer.observe(document.body, { childList: true, subtree: true });
    timer = setTimeout(stop, 15000);
  });
})();
