// Fork: the TV page's season cards. Rating, editing or deleting a season saves in place (inplace.js swaps #seasons and
// the action panel in), so the cards' click handling is delegated: swapped-in cards work without re-binding, and a card
// that was open stays open after its season is saved.
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
})();
