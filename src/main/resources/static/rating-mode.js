// Quick rating (fork): switches a rate modal between "quick" (one score slider) and "detailed"
// (the five category sliders). The inactive panel is hidden and its inputs disabled, so the form
// only submits `score` or the five categories, never both.
(function () {
  if (window.rateLogRatingMode) return;

  const STORAGE_KEY = 'ratelog.ratingMode';
  const MODES = ['quick', 'detailed'];
  const STEP = 0.25;

  function valueOf(slider) {
    return parseFloat(slider.dataset.value) || 0;
  }

  function setSlider(slider, value) {
    if (window.rateLogSliders) window.rateLogSliders.set(slider, value);
  }

  function currentMode(modal) {
    return modal.dataset.ratingMode || 'detailed';
  }

  function apply(modal, mode) {
    modal.dataset.ratingMode = mode;
    modal.querySelectorAll('[data-rating-mode-panel]').forEach((panel) => {
      const active = panel.dataset.ratingModePanel === mode;
      panel.hidden = !active;
      panel.querySelectorAll('input').forEach((input) => { input.disabled = !active; });
    });
    modal.querySelectorAll('[data-rating-mode-option]').forEach((button) => {
      const active = button.dataset.ratingModeOption === mode;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
  }

  // Keep the overall score when switching: quick starts from the categories' average, and the
  // categories all start from the quick score unless they already average to it.
  function carryOver(modal, toMode) {
    const quick = modal.querySelector('[data-rating-mode-panel="quick"] .rate-slider');
    const categories = Array.from(modal.querySelectorAll('[data-rating-mode-panel="detailed"] .rate-slider'));
    if (!quick || !categories.length) return;
    const average = categories.reduce((sum, slider) => sum + valueOf(slider), 0) / categories.length;
    if (toMode === 'quick') {
      setSlider(quick, average);
    } else if (Math.abs(average - valueOf(quick)) >= STEP / 2) {
      categories.forEach((slider) => setSlider(slider, valueOf(quick)));
    }
  }

  function rememberedMode() {
    try {
      return localStorage.getItem(STORAGE_KEY) === 'quick' ? 'quick' : 'detailed';
    } catch (e) {
      return 'detailed';
    }
  }

  function remember(mode) {
    try {
      localStorage.setItem(STORAGE_KEY, mode);
    } catch (e) { /* storage unavailable: nothing to remember */ }
  }

  document.addEventListener('click', (e) => {
    const button = e.target.closest('[data-rating-mode-option]');
    if (!button) return;
    const modal = button.closest('.modal');
    const mode = button.dataset.ratingModeOption;
    if (!modal || mode === currentMode(modal)) return;
    carryOver(modal, mode);
    apply(modal, mode);
    remember(mode);
  });

  window.rateLogRatingMode = {
    // mode: 'quick' | 'detailed'; anything else means the mode last picked on this device.
    set: function (modal, mode) {
      if (modal) apply(modal, MODES.includes(mode) ? mode : rememberedMode());
    },
  };

  function init() {
    document.querySelectorAll('.modal .rating-mode-toggle').forEach((toggle) => {
      window.rateLogRatingMode.set(toggle.closest('.modal'));
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
