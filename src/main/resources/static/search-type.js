// Fork: on search results the header search holds the query; the page keeps the Any / Movies / TV filter and the Year menu.
// Without JS the type filter's search button submits it, the Year menu's options are links and "Type a year" is a form.
// Fork (2026-11-24): the filters apply in place (view-swap.js swaps #searchView and replaces the URL, "One Back leaves the
// page"): the type select as soon as it changes; "Type a year" on Enter / Go once it holds a year in range, else it says
// so and nothing loads. Delegated on document, since every swap brings new controls.
(function () {
    function hideButtons(root) {
        root.querySelectorAll('form.search-type-filter [type="submit"]').forEach(function (button) { button.hidden = true; });
    }

    function yearError(form, show) {
        var input = form.querySelector('input[name="year"]');
        var error = form.querySelector('.bb-year-error');
        if (error) error.hidden = !show;
        if (input) {
            if (show) input.setAttribute('aria-invalid', 'true');
            else input.removeAttribute('aria-invalid');
        }
    }

    function validYear(input) {
        var value = (input.value || '').trim();
        if (!/^\d{4}$/.test(value)) return false;
        var year = parseInt(value, 10);
        var min = parseInt(input.dataset.min, 10) || 1874;
        var max = parseInt(input.dataset.max, 10) || 2100;
        return year >= min && year <= max;
    }

    document.addEventListener('change', function (event) {
        var select = event.target;
        if (!(select instanceof HTMLSelectElement) || select.name !== 'mediaType') return;
        var form = select.closest('form.search-type-filter');
        if (!form) return;
        if (form.requestSubmit) form.requestSubmit();
        else form.submit();
    });

    // Capture phase: runs before view-swap.js's submit handler, which leaves a cancelled submit alone.
    document.addEventListener('submit', function (event) {
        var form = event.target;
        if (!(form instanceof HTMLFormElement) || !form.hasAttribute('data-year-box')) return;
        var input = form.querySelector('input[name="year"]');
        if (!input || validYear(input)) {
            yearError(form, false);
            return;
        }
        event.preventDefault();
        yearError(form, true);
        input.focus();
        if (input.select) input.select();
    }, true);

    document.addEventListener('input', function (event) {
        var input = event.target;
        if (!(input instanceof HTMLInputElement) || !input.classList.contains('bb-year-input')) return;
        var form = input.closest('form[data-year-box]');
        if (form) yearError(form, false);
    });

    function init() { hideButtons(document); }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
    document.addEventListener('view:swapped', function () { hideButtons(document); });
})();
