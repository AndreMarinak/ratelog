// Fork: ask before destructive one-click actions.
// Any <form data-confirm="Question?"> asks for confirmation before it submits.
// <form data-confirm-touch="Question?"> asks only on touch screens (no hover), where the button can't first show what
// a tap will do (the Following button reads "Unfollow" on hover with a mouse; see fragments/follow-button.html).
// Uses the browser's native dialog so it works everywhere, including inside modals.
document.addEventListener('submit', (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement)) return;
    const touchOnly = form.dataset.confirmTouch && window.matchMedia && window.matchMedia('(hover: none)').matches;
    const question = form.dataset.confirm || (touchOnly ? form.dataset.confirmTouch : '');
    if (question && !window.confirm(question)) {
        event.preventDefault();
        event.stopPropagation();
    }
}, true);
