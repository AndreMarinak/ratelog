// Fork (2026-11-24): "Show all 10" on the desktop Cast card (fragments/title-credits.html). The page ships every name;
// this hides the ones past the first six and reveals the toggle, so without JS the whole cast simply shows.
(function () {
    function init() {
        document.querySelectorAll('[data-cast-card]').forEach(function (card) {
            var button = card.querySelector('.cast-more');
            if (!button || card.classList.contains('is-collapsible')) return;
            var label = button.querySelector('span');
            card.classList.add('is-collapsible');
            button.hidden = false;
            button.addEventListener('click', function () {
                var open = card.classList.toggle('is-open');
                button.setAttribute('aria-expanded', open ? 'true' : 'false');
                if (label) label.textContent = button.getAttribute(open ? 'data-less' : 'data-more');
            });
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
