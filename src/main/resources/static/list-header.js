// Fork: the "⋯" List actions menu on /list/{id} (templates/fragments/list-header.html). It is a <details> of plain
// forms and links, so it works without JS; here it only closes on an outside click, on Escape (focus back on the
// "⋯"), and once an item is chosen (Edit / Delete open their modal, the forms ask first and save in place).
// Delegated from `document`, so it keeps working after list-filter.js / inplace.js swap #listRegion.
(() => {
    const openMenus = () => document.querySelectorAll('details[data-list-actions][open]');

    document.addEventListener('click', (event) => {
        const item = event.target.closest && event.target.closest('details[data-list-actions] .list-actions-item');
        openMenus().forEach((menu) => {
            // A form item closes after confirm.js has asked (the submit runs first), so a "Cancel" leaves it closed too.
            if (!menu.contains(event.target) || (item && menu.contains(item))) {
                setTimeout(() => menu.removeAttribute('open'), 0);
            }
        });
    });

    document.addEventListener('keydown', (event) => {
        if (event.key !== 'Escape') return;
        openMenus().forEach((menu) => {
            menu.removeAttribute('open');
            const summary = menu.querySelector('summary');
            if (summary) summary.focus();
        });
    });
})();
