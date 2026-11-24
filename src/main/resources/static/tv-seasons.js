// Fork: the TV page's season cards and the Rate show modal. Rating, editing or deleting a season (or the whole show)
// saves in place (inplace.js swaps #seasons and the action panel in), so the cards' click handling is delegated:
// swapped-in cards work without re-binding, and a card that was open stays open after its season is saved.
(function () {
    var open = new Set();

    function seasonOf(card) {
        var button = card.querySelector('[data-season]');
        return button ? button.getAttribute('data-season') : null;
    }

    document.addEventListener('click', function (e) {
        var header = e.target.closest && e.target.closest('.season-card.expandable .season-card-header');
        if (!header || e.target.closest('form, button')) return;
        var card = header.closest('.season-card');
        var expanded = card.classList.toggle('expanded');
        var season = seasonOf(card);
        if (season == null) return;
        if (expanded) open.add(season); else open.delete(season);
    });

    document.addEventListener('inplace:swapped', function () {
        document.querySelectorAll('.season-card.expandable').forEach(function (card) {
            if (open.has(seasonOf(card))) card.classList.add('expanded');
        });
    });

    // Fork (2026-11-23): "Rate show" (fragments/rate-show-modal.html), one score for the whole show. The trigger says
    // create or edit and carries the current score; edit mode also offers "Delete rating" (asks first, confirm.js).
    var DEFAULT_SCORE = 5;
    var showModal = document.getElementById('rateShowModal');
    if (showModal) {
        showModal.addEventListener('modal:open', function (e) {
            var trigger = e.detail && e.detail.trigger;
            var editing = !!trigger && trigger.dataset.modalMode === 'edit';
            var score = editing && trigger.dataset.score ? Number(trigger.dataset.score) : DEFAULT_SCORE;
            var slider = showModal.querySelector('.rate-slider[data-name="score"]');
            if (slider && window.rateLogSliders) window.rateLogSliders.set(slider, Number.isNaN(score) ? DEFAULT_SCORE : score);
            var remove = showModal.querySelector('[data-rate-show-delete]');
            if (remove) remove.hidden = !editing;
        });
    }
})();
