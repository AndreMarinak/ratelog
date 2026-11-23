// Fork: title pages. On phones the overview is clamped to three lines (title-page.css); the "More" toggle
// is only shown when the text is actually cut, and expands / collapses it.
// The trailer thumbnail starts as YouTube's 480px image and is upgraded to the 1280px one where it is shown large.
(function () {
    function initOverview() {
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

    // maxresdefault doesn't exist for every video; YouTube then answers with a 120x90 placeholder (not always an
    // error), so only swap once the big image has loaded at full size. Skipped where the thumbnail is small
    // (phones show a compact button).
    function initTrailerHd() {
        var img = document.querySelector('.title-trailer img[data-hd-src]');
        if (!img || img.getBoundingClientRect().width <= 320) return;
        var hd = new Image();
        hd.onload = function () {
            if (hd.naturalWidth >= 640) img.src = hd.src;
        };
        hd.src = img.getAttribute('data-hd-src');
    }

    // "Delete rating" inside the rate modals: only when editing. It closes the editor and opens the existing
    // confirmation modal; for TV the season travels on the button's data-season (read by that modal).
    function initRateDelete() {
        document.querySelectorAll('.rate-modal-delete').forEach(function (button) {
            var rateModal = button.closest('.modal');
            if (!rateModal) return;
            rateModal.addEventListener('modal:open', function (e) {
                var trigger = e.detail && e.detail.trigger;
                var editing = !!trigger && trigger.dataset.modalMode === 'edit';
                button.hidden = !editing;
                if (editing && trigger.dataset.season !== undefined) button.dataset.season = trigger.dataset.season;
                else delete button.dataset.season;
            });
            button.addEventListener('click', function () {
                if (!window.Modals) return;
                window.Modals.close(rateModal.id);
                window.Modals.open(button.dataset.deleteModal, button);
            });
        });
    }

    function init() {
        initOverview();
        initTrailerHd();
        initRateDelete();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
