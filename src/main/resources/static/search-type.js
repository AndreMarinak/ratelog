// Fork: on search results the header search holds the query; the page keeps only the Any / Movies / TV filter,
// which applies as soon as it changes. Without JS the filter's search button submits it.
// Fork (2026-11-24): the Year box applies the same way once a full year (or nothing) is entered: on change (Enter, leaving
// the box, the arrows), after a short pause so a few arrow clicks make one search. An empty box leaves the URL.
(function () {
    function init() {
        var form = document.querySelector('form.search-type-filter');
        if (!form) return;
        var select = form.querySelector('select[name="mediaType"]');
        var year = form.querySelector('input[name="year"]');
        var button = form.querySelector('[type="submit"]');
        if (button) button.hidden = true;
        var timer = 0;
        var submitted = (year && year.value) || '';

        function submit() {
            clearTimeout(timer);
            if (year && !year.value.trim()) year.disabled = true;
            form.submit();
        }

        if (select) select.addEventListener('change', submit);
        if (year) {
            var valid = function () {
                var v = year.value.trim();
                return v === '' || /^\d{4}$/.test(v);
            };
            year.addEventListener('change', function () {
                clearTimeout(timer);
                if (!valid() || year.value.trim() === submitted) return;
                timer = setTimeout(submit, 450);
            });
            year.addEventListener('input', function () { clearTimeout(timer); });
            form.addEventListener('submit', function () {
                if (!year.value.trim()) year.disabled = true;
            });
        }
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
