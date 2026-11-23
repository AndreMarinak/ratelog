// Fork: list order on /list/{id} (templates/fragments/list-order.html, roadmap item 16).
// - Sort menu: a <details> of plain links (?sort=...), so it works without JS; here it only closes on an outside
//   click or Escape. The reorder rows' "more" menus (top / bottom on phones) close the same way.
// - Reorder mode (?reorder=1, the list's owner): thin rows, each a plain form (number field + top / up / down /
//   bottom) that posts and comes back at the row. With JS a move is shown at once and saved in the background
//   (header `X-Requested-With: fetch`, JSON answer with the saved order, which wins), with a toast. Rows can be
//   dragged straight up or down: with a mouse from anywhere on the row, with touch / pen from the grip (so the page
//   still scrolls). While dragging, the row follows the pointer, a dashed placeholder shows where it will land, the
//   numbers preview the new order, and the page scrolls near the header and the bottom bar (long lists).
//   Moves are sent one at a time, in order; "Done" waits for them. If a save fails, that row's form is posted the
//   normal way (the page comes back with the saved order and the error), so nothing is lost silently.
(() => {
    // --- Menus (Sort, and the rows' "more") ---
    const openMenus = () => document.querySelectorAll('details[data-list-sort][open], details[data-list-order-more][open]');
    document.addEventListener('click', (event) => {
        openMenus().forEach((menu) => { if (!menu.contains(event.target)) menu.removeAttribute('open'); });
    });
    document.addEventListener('keydown', (event) => {
        if (event.key !== 'Escape') return;
        openMenus().forEach((menu) => {
            menu.removeAttribute('open');
            const summary = menu.querySelector('summary');
            if (summary) summary.focus();
        });
    });

    // --- Reorder mode ---
    const editor = document.querySelector('[data-list-order]');
    const list = editor && editor.querySelector('[data-list-order-rows]');
    if (!list || !window.fetch || !window.URLSearchParams) return;

    const statusEl = editor.querySelector('[data-list-order-status]');
    const idleText = statusEl ? statusEl.textContent : '';
    const rows = () => [...list.querySelectorAll('.list-order-row')];
    const indexOf = (row) => rows().indexOf(row);
    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));

    editor.classList.add('is-enhanced');
    // Enter or leaving the field applies the number, so the "Move" buttons are only for pages without JS.
    editor.querySelectorAll('.list-order-go').forEach((button) => { button.hidden = true; });

    function toast(text) {
        if (!text) return;
        if (window.ratelogToast) window.ratelogToast(text);
        else if (statusEl) statusEl.textContent = text;
    }

    function setStatus(text) { if (statusEl) statusEl.textContent = text; }

    /** Numbers, field limits and which arrows are enabled, from the given order of rows (default: as shown). */
    function renumber(order) {
        const all = order || rows();
        all.forEach((row, index) => {
            const input = row.querySelector('.list-order-input');
            if (input) {
                input.value = String(index + 1);
                input.max = String(all.length);
            }
            row.querySelectorAll('[data-move]').forEach((button) => {
                const towardsTop = button.dataset.move === 'top' || button.dataset.move === 'up';
                button.disabled = towardsTop ? index === 0 : index === all.length - 1;
            });
        });
    }

    /** Puts the row at a 1-based place among the others. */
    function placeAt(row, position) {
        const others = rows().filter((other) => other !== row);
        list.insertBefore(row, others[clamp(position - 1, 0, others.length)] || null);
    }

    function highlight(row) {
        row.classList.remove('is-moved');
        void row.offsetWidth; // restart the animation
        row.classList.add('is-moved');
    }

    function closeMore(row) {
        row.querySelectorAll('details[data-list-order-more][open]').forEach((menu) => menu.removeAttribute('open'));
    }

    // --- Saving: one request at a time, in the order the moves were made ---
    let queue = Promise.resolve();
    let pending = 0;
    let failed = false;
    let deferredOrder = null;
    let drag = null;

    function save(row, position) {
        const form = row.querySelector('form.list-order-form');
        if (!form) return;
        pending += 1;
        setStatus(editor.dataset.savingText || idleText);
        queue = queue.then(async () => {
            if (failed) return;
            const body = new URLSearchParams();
            form.querySelectorAll('input[type=hidden]').forEach((input) => body.append(input.name, input.value));
            body.append('position', String(position));
            try {
                const response = await fetch(form.action, {
                    method: 'POST',
                    body,
                    credentials: 'same-origin',
                    headers: { 'X-Requested-With': 'fetch', Accept: 'application/json' },
                });
                const type = response.headers.get('content-type') || '';
                if (!type.includes('application/json')) throw new Error('not json');
                const state = await response.json();
                if (response.status === 429) {
                    // Fork (rate limit): say so instead of re-posting (that would be refused too), then show the saved order.
                    failed = true;
                    setStatus(editor.dataset.errorText || '');
                    toast(state.message);
                    setTimeout(() => window.location.reload(), 3000);
                    return;
                }
                if (!state.ok) throw new Error(state.message || 'not saved');
                pending -= 1;
                toast(state.message);
                if (pending === 0) {
                    setStatus(editor.dataset.savedText || idleText);
                    applyOrder(state.order);
                }
            } catch (error) {
                failed = true;
                setStatus(editor.dataset.errorText || '');
                // Post this move the normal way: the page comes back with the saved order and the server's message.
                const input = form.querySelector('.list-order-input');
                if (input) input.value = String(position);
                form.querySelectorAll('button[name=to]').forEach((button) => { button.disabled = true; });
                form.submit();
            }
        });
    }

    /** The server's order wins: rows follow it (a list changed somewhere else reloads). */
    function applyOrder(order) {
        if (!Array.isArray(order) || !order.length) return;
        if (drag) { deferredOrder = order; return; }
        const byId = new Map(rows().map((row) => [Number(row.dataset.itemId), row]));
        if (order.length !== byId.size || order.some((id) => !byId.has(id))) { window.location.reload(); return; }
        const shown = rows().map((row) => Number(row.dataset.itemId));
        if (shown.some((id, index) => id !== order[index])) {
            const focused = document.activeElement;
            order.forEach((id) => list.appendChild(byId.get(id)));
            if (focused && focused !== document.activeElement && list.contains(focused)) focused.focus({ preventScroll: true });
        }
        renumber();
    }

    /** Moves a row to a 1-based place, shows it at once and saves it. */
    function move(row, position, focusAfter) {
        closeMore(row);
        const from = indexOf(row) + 1;
        const to = clamp(position, 1, rows().length);
        if (to === from) { renumber(); return; }
        placeAt(row, to);
        renumber();
        highlight(row);
        if (focusAfter && !focusAfter.disabled && focusAfter.offsetParent) focusAfter.focus({ preventScroll: true });
        else { const input = row.querySelector('.list-order-input'); if (input) input.focus({ preventScroll: true }); }
        row.scrollIntoView({ block: 'nearest' });
        save(row, to);
    }

    // Arrows and Enter in the number field. `event.submitter` is missing on old Safari: remember the last button.
    let lastButton = null;
    list.addEventListener('click', (event) => {
        const button = event.target.closest && event.target.closest('button[data-move]');
        lastButton = button || null;
    });

    list.addEventListener('submit', (event) => {
        const form = event.target.closest && event.target.closest('form.list-order-form');
        if (!form) return;
        event.preventDefault();
        if (failed) return;
        const row = form.closest('.list-order-row');
        const button = (event.submitter && event.submitter.dataset && event.submitter.dataset.move) ? event.submitter : lastButton;
        lastButton = null;
        const current = indexOf(row) + 1;
        const total = rows().length;
        const how = button && form.contains(button) ? button.dataset.move : null;
        let target;
        if (how === 'top') target = 1;
        else if (how === 'bottom') target = total;
        else if (how === 'up') target = current - 1;
        else if (how === 'down') target = current + 1;
        else target = parseInt(form.querySelector('.list-order-input').value, 10);
        if (!Number.isFinite(target)) { renumber(); return; }
        // A button inside the closed "more" menu can't keep focus: the row's ⋮ button takes it.
        const inMenu = button && button.closest('details[data-list-order-more]');
        const focusAfter = how ? (inMenu ? inMenu.querySelector('summary') : button) : form.querySelector('.list-order-input');
        move(row, target, focusAfter);
    });

    // A number typed and then left (phones have no Enter on the number pad).
    list.addEventListener('change', (event) => {
        const input = event.target.closest && event.target.closest('.list-order-input');
        if (!input || failed) return;
        const row = input.closest('.list-order-row');
        const target = parseInt(input.value, 10);
        if (!Number.isFinite(target)) { renumber(); return; }
        if (clamp(target, 1, rows().length) === indexOf(row) + 1) { renumber(); return; }
        move(row, target, null);
    });

    list.addEventListener('keydown', (event) => {
        const input = event.target.closest && event.target.closest('.list-order-input');
        if (!input || event.key !== 'Enter') return;
        event.preventDefault();
        if (failed) return;
        const row = input.closest('.list-order-row');
        const target = parseInt(input.value, 10);
        if (!Number.isFinite(target)) { renumber(); return; }
        move(row, target, input);
    });

    // --- Drag to reorder (pointer events: mouse, touch, pen) ---
    // The row is lifted out of the flow (position: fixed, so it follows the pointer and stays under it while the page
    // scrolls) and a placeholder of the same height takes its slot. The placeholder moves past its neighbours as the
    // pointer crosses their middles; on release the row drops into the placeholder's place. The row never leaves the
    // DOM during the drag, so the pointer capture holds.

    /** The rows in the order they would be after dropping the dragged row on the placeholder. */
    function previewOrder() {
        return [...list.children].flatMap((child) => {
            if (child === drag.placeholder) return [drag.row];
            if (child === drag.row || !child.classList.contains('list-order-row')) return [];
            return [child];
        });
    }

    list.addEventListener('pointerdown', (event) => {
        if (failed || drag) return;
        if (event.pointerType === 'mouse' && event.button !== 0) return;
        const row = event.target.closest && event.target.closest('.list-order-row');
        if (!row || event.target.closest('input, button, a, label, select, textarea, summary, details')) return;
        const onHandle = !!event.target.closest('.list-order-handle');
        if (event.pointerType !== 'mouse' && !onHandle) return;
        event.preventDefault();
        openMenus().forEach((menu) => menu.removeAttribute('open'));

        const rect = row.getBoundingClientRect();
        const placeholder = document.createElement('li');
        placeholder.className = 'list-order-placeholder';
        placeholder.setAttribute('aria-hidden', 'true');
        placeholder.style.height = `${rect.height}px`;
        list.insertBefore(placeholder, row);

        Object.assign(row.style, { position: 'fixed', left: `${rect.left}px`, top: `${rect.top}px`, width: `${rect.width}px`, margin: '0' });
        row.classList.add('is-dragging');
        list.classList.add('is-sorting');
        drag = { row, placeholder, pointerId: event.pointerId, startIndex: rows().indexOf(row), grab: event.clientY - rect.top, y: event.clientY, frame: 0 };
        // generic.css makes page scrolling smooth; auto-scroll steps every frame, so it needs instant scrolling meanwhile.
        document.documentElement.style.scrollBehavior = 'auto';
        try { row.setPointerCapture(event.pointerId); } catch (err) { /* window listeners still follow it */ }
        drag.frame = requestAnimationFrame(autoScroll);
    });

    function follow() {
        const { row, placeholder } = drag;
        row.style.top = `${drag.y - drag.grab}px`;
        let moved = true;
        while (moved) {
            moved = false;
            let next = placeholder.nextElementSibling;
            if (next === row) next = next.nextElementSibling;
            if (next) {
                const box = next.getBoundingClientRect();
                if (drag.y > box.top + box.height / 2) { list.insertBefore(placeholder, next.nextElementSibling); moved = true; continue; }
            }
            let prev = placeholder.previousElementSibling;
            if (prev === row) prev = prev.previousElementSibling;
            if (prev) {
                const box = prev.getBoundingClientRect();
                if (drag.y < box.top + box.height / 2) { list.insertBefore(placeholder, prev); moved = true; }
            }
        }
        renumber(previewOrder());
    }

    /** Scrolls while the pointer is near the sticky header at the top or the fixed bar at the bottom. */
    function autoScroll() {
        if (!drag) return;
        const bar = editor.querySelector('.list-order-bar');
        const header = document.querySelector('.site-header');
        const bottom = bar ? bar.getBoundingClientRect().top : window.innerHeight;
        const top = header ? Math.max(0, header.getBoundingClientRect().bottom) : 0;
        const zone = 70;
        let speed = 0;
        if (drag.y < top + zone) speed = -Math.ceil((top + zone - drag.y) / 3);
        else if (drag.y > bottom - zone) speed = Math.ceil((drag.y - (bottom - zone)) / 3);
        if (speed) {
            window.scrollBy(0, clamp(speed, -28, 28));
            follow();
        }
        drag.frame = requestAnimationFrame(autoScroll);
    }

    window.addEventListener('pointermove', (event) => {
        if (!drag || event.pointerId !== drag.pointerId) return;
        event.preventDefault();
        drag.y = event.clientY;
        follow();
    }, { passive: false });

    function endDrag(cancelled) {
        if (!drag) return;
        const { row, placeholder, startIndex, frame } = drag;
        cancelAnimationFrame(frame);
        drag = null;
        document.documentElement.style.scrollBehavior = '';
        if (cancelled) {
            placeholder.remove();
            placeAt(row, startIndex + 1);
        } else {
            list.insertBefore(row, placeholder);
            placeholder.remove();
        }
        row.style.position = row.style.left = row.style.top = row.style.width = row.style.margin = '';
        row.classList.remove('is-dragging');
        list.classList.remove('is-sorting');
        renumber();
        const index = indexOf(row);
        if (!cancelled && index !== startIndex) {
            highlight(row);
            save(row, index + 1);
        } else if (deferredOrder) {
            const order = deferredOrder;
            deferredOrder = null;
            applyOrder(order);
        }
    }

    window.addEventListener('pointerup', (event) => { if (drag && event.pointerId === drag.pointerId) endDrag(false); });
    window.addEventListener('pointercancel', (event) => { if (drag && event.pointerId === drag.pointerId) endDrag(true); });
    window.addEventListener('keydown', (event) => { if (drag && event.key === 'Escape') endDrag(true); });
    // No native image / text drag on rows.
    list.addEventListener('dragstart', (event) => event.preventDefault());

    // "Done" waits for moves still being saved.
    const done = editor.querySelector('.list-order-done');
    if (done) {
        done.addEventListener('click', (event) => {
            if (!pending || failed) return;
            event.preventDefault();
            setStatus(editor.dataset.savingText || idleText);
            queue.then(() => { if (!failed) window.location.href = done.href; });
        });
    }
    window.addEventListener('beforeunload', (event) => {
        if (pending && !failed) { event.preventDefault(); event.returnValue = ''; }
    });
})();
