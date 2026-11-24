// Fork (2026-11-24): the person page's "Show N appearances as himself" switch (templates/person.html) is a link with
// role="switch" that view-swap.js applies in place like the tabs; Space flips it as it would a real switch (Enter
// already follows the link). Delegated, since every swap brings a new switch.
document.addEventListener('keydown', (event) => {
    if (event.key !== ' ' || event.repeat) return;
    const link = event.target.closest && event.target.closest('a[data-appearances]');
    if (!link) return;
    event.preventDefault();
    link.click();
});
