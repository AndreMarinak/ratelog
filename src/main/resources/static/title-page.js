// Fork: title pages. On phones the overview is clamped to three lines (title-page.css); the "More" toggle
// is only shown when the text is actually cut, and expands / collapses it.
(function () {
    function init() {
        var text = document.getElementById('title-overview');
        var toggle = document.querySelector('.title-overview-toggle');
        if (!text || !toggle) return;

        function refresh() {
            var expanded = text.classList.contains('is-expanded');
            toggle.hidden = !expanded && text.scrollHeight <= text.clientHeight + 1;
        }

        toggle.addEventListener('click', function () {
            var expanded = text.classList.toggle('is-expanded');
            toggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
            toggle.textContent = expanded ? toggle.getAttribute('data-less') : toggle.getAttribute('data-more');
            refresh();
        });

        window.addEventListener('resize', refresh);
        window.addEventListener('load', refresh);
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(refresh);
        refresh();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
