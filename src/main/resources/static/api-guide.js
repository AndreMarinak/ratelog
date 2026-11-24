// Copy button of the "Use Ratelog with an AI assistant" box on /settings/api.
(() => {
    const button = document.getElementById('apiGuideCopy');
    const box = document.getElementById('apiGuideText');
    const status = document.getElementById('apiGuideStatus');
    if (!button || !box) return;
    const label = button.textContent;
    let timer;

    const confirm = () => {
        button.textContent = button.dataset.copied || 'Copied';
        if (status) { status.textContent = button.dataset.copied || 'Copied'; status.hidden = false; }
        clearTimeout(timer);
        timer = setTimeout(() => {
            button.textContent = label;
            if (status) status.hidden = true;
        }, 2000);
    };

    const fallback = () => {
        // Older browsers or plain http: select the text so Ctrl+C / Cmd+C works, and try the legacy command.
        box.focus();
        box.select();
        try { if (document.execCommand('copy')) confirm(); } catch (e) { /* the text stays selected */ }
    };

    button.addEventListener('click', () => {
        if (navigator.clipboard && window.isSecureContext) {
            navigator.clipboard.writeText(box.value).then(confirm, fallback);
        } else {
            fallback();
        }
    });
    box.addEventListener('focus', () => box.select());
})();
