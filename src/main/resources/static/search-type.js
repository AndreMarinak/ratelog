// Fork: on search results the header search holds the query; the page keeps only the Any / Movies / TV filter,
// which applies as soon as it changes. Without JS the filter's search button submits it.
(function () {
    function init() {
        var form = document.querySelector('form.search-type-filter');
        if (!form) return;
        var select = form.querySelector('select[name="mediaType"]');
        var button = form.querySelector('[type="submit"]');
        if (button) button.hidden = true;
        if (select) select.addEventListener('change', function () { form.submit(); });
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
