// Fork: ask before destructive one-click actions.
// Any <form data-confirm="Question?"> asks for confirmation before it submits.
// Uses the browser's native dialog so it works everywhere, including inside modals.
document.addEventListener('submit', (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement)) return;
    const question = form.dataset.confirm;
    if (question && !window.confirm(question)) {
        event.preventDefault();
        event.stopPropagation();
    }
}, true);
