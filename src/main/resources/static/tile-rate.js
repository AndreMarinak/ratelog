// Fork: rate and add to list from poster tiles (fragments/tile-actions.html, fragments/tile-rate.html).
// - ★ on a movie opens a small picker (a popover by the ★ on desktop, a bottom sheet on phones): one tap on 1-10 saves
//   a quick rating (POST /movie/rate/tile, header `X-Requested-With: fetch`, JSON answer). Every copy of the title on
//   the page takes the new state: the "★ 8.5" chip, and the eye turns on (rating marks a movie seen). A quick rating
//   is shown prefilled and can be changed or deleted (a second tap confirms); a detailed one is only shown, with
//   "Edit rating" to the title page's rate modal (?rate=edit), so its five categories are never flattened.
//   ★ on a show is a plain link to its seasons (shows are rated per season).
// - The list button loads the title page's add-to-list sheet for that title (GET /tile/lists) and opens it in place;
//   its toggles are list-membership.js's own. Once the title is in any of your lists the button shows ticked.
// - After Seen from a tile's eye (any tile, list tick-off mode too) on an unrated movie, the same picker asks
//   "Rate it?" (optional): no backdrop and no focus grab, so the next poster can be ticked right away (the prompt
//   closes and the next one opens). Skip, or tapping anywhere else, leaves the title seen and unrated. A switch in it
//   turns the prompt off for this browser (localStorage).
// - On title pages, ?rate=1 / ?rate=edit / ?rate=detailed open the rate modal and ?lists=1 the add-to-list sheet:
//   that is where ★ and the list button go without JS (and on a middle-click).
// Without fetch, or on any error, the links navigate and the forms post normally (and come back with a message).
(() => {
    if (window.ratelogTileRate) return;
    window.ratelogTileRate = true;
    if (!window.fetch || !window.URLSearchParams || !window.DOMParser) return;

    const sheetQuery = window.matchMedia('(max-width: 600px)');
    const toast = (text) => { if (text && window.ratelogToast) window.ratelogToast(text); };
    const refreshIcons = () => { if (window.lucide) window.lucide.createIcons(); };
    const fill = (text, value) => (text || '').replace('{0}', value == null ? '' : value);
    const here = () => window.location.pathname + window.location.search;
    const template = () => document.getElementById('tileRatePicker');
    const labels = () => (template() ? template().dataset : {});
    const stacksFor = (key) => document.querySelectorAll(`[data-tile-actions][data-key="${CSS.escape(key)}"]`);
    const ASK_KEY = 'ratelog.rateAfterSeen';
    const askAfterSeen = () => { try { return window.localStorage.getItem(ASK_KEY) !== 'off'; } catch (e) { return true; } };
    const setAskAfterSeen = (on) => { try { window.localStorage.setItem(ASK_KEY, on ? 'on' : 'off'); } catch (e) { /* not remembered */ } };
    const plainClick = (event) => !(event.defaultPrevented || event.button > 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey);

    /**
     * Where the focus goes back to: the ★ / list button, or on touch screens the poster's "⋯", which opened the picker
     * or the sheet through its chooser (tile-actions.js; the ★ and list button are hidden there, the score chip a mark).
     */
    function returnTarget(el) {
        if (!el || !el.isConnected) return el;
        const stack = el.closest('[data-tile-actions]');
        const menu = stack && stack.querySelector('[data-tile-menu]');
        return menu && menu.getClientRects().length ? menu : el;
    }

    function setIcon(el, name) {
        const old = el.querySelector('svg, i');
        const icon = document.createElement('i');
        icon.setAttribute('data-lucide', name);
        icon.setAttribute('aria-hidden', 'true');
        if (old) old.replaceWith(icon); else el.prepend(icon);
    }

    // ---------- Keeping every copy of a title in sync ----------

    /** The ★ of every tile with this key: chip and score, or the empty star. */
    function applyRating(key, state) {
        const d = labels();
        stacksFor(key).forEach((stack) => {
            const star = stack.querySelector('.tile-rate');
            if (!star) return;
            star.classList.toggle('is-on', !!state.rated);
            star.classList.toggle('is-detailed', !!state.detailed);
            if (state.rated && state.score != null) star.dataset.score = String(state.score); else delete star.dataset.score;
            if (state.detailed) star.dataset.detailed = 'true'; else delete star.dataset.detailed;
            let score = star.querySelector('.tile-rate-score');
            if (state.rated) {
                if (!score) {
                    score = document.createElement('span');
                    score.className = 'tile-rate-score';
                    star.appendChild(score);
                }
                score.textContent = state.scoreLabel || '';
            } else if (score) {
                score.remove();
            }
            const text = state.rated ? fill(state.detailed ? d.labelOnDetailed : d.labelOn, state.scoreLabel) : d.labelMark;
            if (text) {
                star.title = text;
                star.setAttribute('aria-label', stack.dataset.title ? `${text}: ${stack.dataset.title}` : text);
            }
        });
    }

    /** The list button of every tile with this key. */
    function applyLists(key, inList) {
        const d = labels();
        stacksFor(key).forEach((stack) => {
            const button = stack.querySelector('.tile-list');
            if (!button) return;
            button.classList.toggle('is-on', inList);
            setIcon(button, inList ? 'list-checks' : 'list-plus');
            const text = inList ? d.labelListsIn : d.labelListsAdd;
            if (text) {
                button.title = text;
                button.setAttribute('aria-label', stack.dataset.title ? `${text}: ${stack.dataset.title}` : text);
            }
        });
        refreshIcons();
    }

    /** The watchlist page: a rating can move the title to another tab (To watch -> Seen). */
    function applyWatchlistPage(stack, state) {
        const item = stack.closest('.watchlist-item');
        if (!item) return;
        if (state.previousStatus && state.status && state.previousStatus !== state.status && window.ratelogWatchlist) {
            window.ratelogWatchlist.moved(item, state.previousStatus, state.status);
        } else if (state.rated && state.status === 'watched') {
            // A rated title stays seen through its rating: the Seen tab offers no Remove for it.
            const remove = item.querySelector('.watchlist-act.is-remove');
            if (remove) remove.closest('form').remove();
        }
    }

    // ---------- The rating picker ----------

    let pop = null;
    let backdrop = null;
    let current = null; // { star, stack, key, title, titleHref, prompt }

    function build() {
        const t = template();
        if (!t) return false;
        pop = t.content.firstElementChild.cloneNode(true);
        backdrop = document.createElement('div');
        backdrop.className = 'tile-rate-backdrop';
        backdrop.hidden = true;
        document.body.append(backdrop, pop);
        refreshIcons();
        return true;
    }

    function close(restoreFocus = true) {
        if (!pop || pop.hidden) return;
        const star = current && current.star;
        // A tap doesn't always focus the score button (touch), so a focus left on nothing comes back too.
        const hadFocus = pop.contains(document.activeElement) || document.activeElement === document.body;
        pop.hidden = true;
        backdrop.hidden = true;
        if (star) star.setAttribute('aria-expanded', 'false');
        current = null;
        const back = returnTarget(star);
        if (restoreFocus && hadFocus && back && back.isConnected) back.focus({ preventScroll: true });
    }

    /** What the popover points at: the ★, or (touch screens hide it) the "⋯", or (tick-off mode) the eye, or the tile. */
    function anchorOf(star) {
        if (star.offsetParent) return star;
        const stack = star.closest('[data-tile-actions]');
        const menu = stack && stack.querySelector('[data-tile-menu]');
        if (menu && menu.offsetParent) return menu;
        const eye = stack && stack.querySelector('.tile-seen button');
        return eye && eye.offsetParent ? eye : (stack || star);
    }

    function position(star) {
        const sheet = sheetQuery.matches;
        const prompt = !!(current && current.prompt);
        pop.classList.toggle('is-sheet', sheet);
        pop.classList.toggle('is-prompt', prompt);
        // The "Rate it?" prompt never blocks the page: the next poster can be tapped straight away.
        backdrop.hidden = !sheet || prompt;
        if (sheet) {
            pop.style.left = '';
            pop.style.top = '';
            // The prompt goes to the other half of the screen from the poster just tapped, so it stays in view and the
            // posters around it can still be tapped (tick-off mode).
            const r = anchorOf(star).getBoundingClientRect();
            pop.classList.toggle('is-top', prompt && r.top + r.height / 2 > window.innerHeight / 2);
            return;
        }
        const r = anchorOf(star).getBoundingClientRect();
        const width = pop.offsetWidth;
        const height = pop.offsetHeight;
        const viewport = document.documentElement.clientWidth;
        const left = Math.max(8, Math.min(r.right - width, viewport - width - 8));
        let top = r.bottom + 8;
        if (top + height > window.innerHeight - 8 && r.top - height - 8 > 8) top = r.top - height - 8;
        pop.style.left = `${left + window.scrollX}px`;
        pop.style.top = `${top + window.scrollY}px`;
    }

    function fillPicker(star, prompt) {
        const d = labels();
        const stack = star.closest('[data-tile-actions]');
        const key = stack.dataset.key;
        const title = stack.dataset.title || '';
        const titleHref = star.dataset.titleHref || star.getAttribute('href').split('?')[0];
        const score = star.dataset.score != null ? Number(star.dataset.score) : null;
        const rated = score != null && !Number.isNaN(score);
        const detailed = rated && star.dataset.detailed === 'true';
        const label = star.querySelector('.tile-rate-score');
        current = { star, stack, key, title, titleHref, prompt };

        pop.setAttribute('aria-label', fill(d.labelAria, title));
        pop.querySelector('[data-pop-title]').textContent = title;
        pop.querySelector('[data-pop-sub-text]').textContent = prompt ? d.labelPrompt : (rated ? d.labelYours : d.labelNew);
        pop.querySelector('[data-pop-current]').hidden = !rated;
        pop.querySelector('[data-pop-current-score]').textContent = label ? label.textContent : (rated ? String(score) : '');
        pop.querySelector('[data-pop-kind]').textContent = rated ? (detailed ? d.labelDetailed : d.labelQuick) : '';

        const form = pop.querySelector('[data-pop-form]');
        form.hidden = detailed;
        form.querySelector('[data-pop-hint]').hidden = rated || prompt;
        form.querySelector('[data-pop-prompt-hint]').hidden = !prompt;
        pop.querySelector('[data-pop-skip]').hidden = !prompt;
        const ask = askAfterSeen();
        pop.querySelector('[data-pop-ask]').checked = ask;
        pop.querySelector('[data-pop-ask-row]').hidden = !(prompt || !ask);
        form.querySelectorAll('.tile-rate-score-btn').forEach((button) => {
            button.setAttribute('aria-pressed', rated && Number(button.value) === score ? 'true' : 'false');
            button.setAttribute('aria-label', fill(button.dataset.label, button.value));
            button.removeAttribute('aria-busy');
        });
        pop.querySelector('[data-pop-detailed]').hidden = !detailed;
        pop.querySelector('[data-pop-edit]').href = `${titleHref}?rate=edit`;
        const more = pop.querySelector('[data-pop-more]');
        more.hidden = detailed;
        more.href = `${titleHref}?rate=detailed`;

        const remove = pop.querySelector('[data-pop-delete-form]');
        remove.hidden = !rated || detailed;
        remove.querySelector('[data-pop-delete]').hidden = false;
        remove.querySelector('[data-pop-confirm]').hidden = true;

        pop.querySelectorAll('input[name="tmdbId"]').forEach((input) => { input.value = key.split(':')[1]; });
        pop.querySelectorAll('input[name="back"]').forEach((input) => { input.value = here(); });
    }

    function open(star, prompt = false) {
        if (!pop && !build()) return false;
        if (current) current.star.setAttribute('aria-expanded', 'false');
        fillPicker(star, prompt);
        pop.style.visibility = 'hidden';
        pop.hidden = false;
        position(star);
        pop.style.visibility = '';
        star.setAttribute('aria-expanded', 'true');
        if (prompt) return true; // a prompt doesn't take the focus away from the poster just tapped
        const focus = pop.querySelector('.tile-rate-score-btn[aria-pressed="true"]')
            || (pop.querySelector('[data-pop-form]').hidden ? pop.querySelector('[data-pop-edit]') : pop.querySelector('.tile-rate-score-btn'));
        if (focus) focus.focus({ preventScroll: true });
        return true;
    }

    async function post(form, fields) {
        const body = new URLSearchParams(new FormData(form));
        Object.entries(fields || {}).forEach(([name, value]) => body.set(name, value));
        const response = await fetch(form.action, {
            method: 'POST',
            body,
            credentials: 'same-origin',
            headers: { 'X-Requested-With': 'fetch', Accept: 'application/json' },
        });
        const type = response.headers.get('content-type') || '';
        // Logged out (redirected to the login page), a server error...: let the browser do it the normal way.
        if (!type.includes('application/json') || response.status >= 500) throw new Error('not json');
        const state = await response.json();
        if (!response.ok && !state.message) throw new Error('failed');
        return state;
    }

    async function save(form, fields, busy) {
        const target = current;
        if (!target || busy.getAttribute('aria-busy') === 'true') return;
        busy.setAttribute('aria-busy', 'true');
        try {
            const state = await post(form, fields);
            if (state.ok) {
                applyRating(target.key, state);
                if (window.ratelogTileActions) window.ratelogTileActions.apply(target.key, state);
                applyWatchlistPage(target.stack, state);
                close();
                toast(state.message);
                target.stack.dispatchEvent(new CustomEvent('tile:changed', { bubbles: true, detail: { act: 'rate', state } }));
            } else {
                toast(state.message);
                // Rated in detail meanwhile (another tab): show it as such, with "Edit rating".
                if (state.detailed) {
                    applyRating(target.key, state);
                    open(target.star);
                }
            }
        } catch (error) {
            // The normal post, keeping the score that was tapped; it comes back to this page with a message.
            Object.entries(fields || {}).forEach(([name, value]) => {
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = name;
                input.value = value;
                form.appendChild(input);
            });
            form.submit();
        } finally {
            busy.removeAttribute('aria-busy');
        }
    }

    // ---------- The add-to-list sheet ----------

    let parked = null; // the title page's own sheet, set aside while a tile's sheet is open

    function loadOnce(tag, attr, url) {
        if (document.querySelector(`${tag}[${attr}="${url}"]`)) return Promise.resolve();
        return new Promise((resolve) => {
            const el = document.createElement(tag);
            el.onload = resolve;
            el.onerror = resolve;
            if (tag === 'link') { el.rel = 'stylesheet'; el.href = url; } else { el.src = url; }
            document.head.appendChild(el);
        });
    }

    function removeTileSheet() {
        document.querySelectorAll('[data-tile-sheet]').forEach((sheet) => sheet.remove());
        if (parked) {
            parked.marker.replaceWith(parked.node);
            parked = null;
        }
    }

    async function openSheet(button) {
        if (button.getAttribute('aria-busy') === 'true') return;
        button.setAttribute('aria-busy', 'true');
        try {
            await Promise.all([
                loadOnce('link', 'href', '/list-membership.css'),
                loadOnce('script', 'src', '/confirm.js'),
                loadOnce('script', 'src', '/list-membership.js'),
            ]);
            const params = new URLSearchParams({ tmdbId: button.dataset.tmdbId, type: button.dataset.type, back: here() });
            const response = await fetch(`/tile/lists?${params}`, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
            const landed = new URL(response.url, window.location.href);
            if (!response.ok || landed.pathname === '/login') throw new Error('not loaded');
            const fresh = new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('[data-tile-sheet]');
            if (!fresh || !window.Modals) throw new Error('no sheet');

            removeTileSheet();
            // Title pages have their own sheet with the same id: set it aside until this one closes.
            const own = document.getElementById('addToListModal');
            if (own) {
                parked = { node: own, marker: document.createComment('addToListModal') };
                own.replaceWith(parked.marker);
            }
            const sheet = document.importNode(fresh, true);
            document.body.appendChild(sheet);
            refreshIcons();
            const modal = sheet.querySelector('#addToListModal');
            const back = returnTarget(button);
            modal.addEventListener('modal:close', () => {
                setTimeout(() => { if (!modal.classList.contains('visible')) removeTileSheet(); }, 250);
                if (back && back.isConnected) back.focus({ preventScroll: true });
            }, { once: true });
            window.Modals.open('addToListModal', back);
        } catch (error) {
            window.location.href = button.href;
        } finally {
            button.removeAttribute('aria-busy');
        }
    }

    /** "New list" inside a tile's sheet: create it, add the title, stay on this page. */
    async function createList(form) {
        const sheet = form.closest('[data-tile-sheet]');
        const submit = form.querySelector('[type="submit"]');
        if (form.getAttribute('aria-busy') === 'true') return;
        form.setAttribute('aria-busy', 'true');
        if (submit) submit.disabled = true;
        let posted = false;
        try {
            const response = await fetch(form.action, { method: 'POST', body: new URLSearchParams(new FormData(form)), credentials: 'same-origin' });
            // Rate limited (429): say so; posting the form again would only be refused too.
            if (response.status === 429) {
                posted = true;
                const limited = new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('.feedback.error');
                toast(limited ? limited.textContent.trim() : 'Too many requests');
                return;
            }
            const landed = new URL(response.url, window.location.href);
            if (!response.ok || landed.pathname === '/login') throw new Error('not saved');
            posted = true;
            const flash = new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('.feedback.success, .feedback.error');
            const failed = !!(flash && flash.classList.contains('error'));
            if (!failed) {
                applyLists(sheet.dataset.key, true);
                window.Modals.close('addToListModal');
            }
            toast(flash ? flash.textContent.trim() : '');
        } catch (error) {
            if (!posted) form.submit();
        } finally {
            form.removeAttribute('aria-busy');
            if (submit) submit.disabled = false;
        }
    }

    // ---------- Title pages: ?rate=... and ?lists=1 ----------

    function openFromUrl() {
        const params = new URLSearchParams(window.location.search);
        const rate = params.get('rate');
        const lists = params.get('lists');
        if (rate == null && lists == null) return;
        if (window.Modals) {
            const rateTrigger = document.querySelector('.title-panel [data-open-modal="rateMovieModal"]');
            const listTrigger = document.querySelector('[data-list-button]');
            if (rate != null && rateTrigger) {
                window.Modals.open('rateMovieModal', rateTrigger);
                const modal = document.getElementById('rateMovieModal');
                // "Detailed rating": the five categories, starting from the quick score if there is one.
                if (rate === 'detailed' && modal && modal.dataset.ratingMode !== 'detailed') {
                    const option = modal.querySelector('[data-rating-mode-option="detailed"]');
                    if (option) option.click();
                }
            } else if (lists != null && listTrigger) {
                window.Modals.open('addToListModal', listTrigger);
            }
        }
        // Once: a reload or a shared link shouldn't open it again.
        params.delete('rate');
        params.delete('lists');
        const query = params.toString();
        window.history.replaceState(window.history.state, '', window.location.pathname + (query ? `?${query}` : '') + window.location.hash);
    }

    // ---------- Events (delegated: rows and grids are added and swapped in place) ----------

    document.addEventListener('click', (event) => {
        const target = event.target;
        if (!target.closest) return;

        const star = target.closest('a[data-tile-rate]');
        if (star && plainClick(event)) {
            event.preventDefault();
            if (current && current.star === star && !pop.hidden) close();
            else if (!open(star)) window.location.href = star.href;
            return;
        }

        const lists = target.closest('a[data-tile-lists]');
        if (lists && plainClick(event)) {
            event.preventDefault();
            close(false);
            openSheet(lists);
            return;
        }

        if (!pop || pop.hidden) return;
        if (target === backdrop) { event.preventDefault(); close(false); return; }
        if (target.closest('[data-pop-close]') || target.closest('[data-pop-skip]')) { close(); return; }
        const remove = target.closest('[data-pop-delete]');
        if (remove) {
            remove.hidden = true;
            const confirm = pop.querySelector('[data-pop-confirm]');
            confirm.hidden = false;
            confirm.querySelector('button[type="submit"]').focus({ preventScroll: true });
            return;
        }
        if (target.closest('[data-pop-cancel]')) {
            pop.querySelector('[data-pop-confirm]').hidden = true;
            const again = pop.querySelector('[data-pop-delete]');
            again.hidden = false;
            again.focus({ preventScroll: true });
        }
    });

    // Outside the picker closes it. The phone backdrop closes it on its own click (above): hiding it on pointerdown let
    // the tap's click land on the poster under it, which opened that title.
    document.addEventListener('pointerdown', (event) => {
        if (!pop || pop.hidden || pop.contains(event.target) || event.target === backdrop) return;
        if (current && current.star.contains(event.target)) return;
        close(false);
    }, true);

    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && pop && !pop.hidden) close();
    });

    // A row scrolled sideways takes the ★ away from the popover; the page itself scrolling is fine (it follows).
    document.addEventListener('scroll', (event) => {
        if (!pop || pop.hidden || !current || sheetQuery.matches) return;
        if (event.target !== document && event.target.contains && event.target.contains(current.star)) close(false);
    }, true);

    window.addEventListener('resize', () => {
        if (pop && !pop.hidden && current) position(current.star);
    });

    document.addEventListener('submit', (event) => {
        const form = event.target;
        if (!form.closest) return;
        if (pop && pop.contains(form)) {
            event.preventDefault();
            if (form.matches('[data-pop-form]')) {
                const submitter = event.submitter && event.submitter.name === 'score' ? event.submitter : null;
                if (submitter) save(form, { score: submitter.value }, submitter);
            } else if (form.matches('[data-pop-delete-form]')) {
                save(form, {}, form.querySelector('button[type="submit"]'));
            }
            return;
        }
        // Capture phase, before inplace.js (it would refresh regions this page doesn't have).
        if (form.matches('[data-tile-sheet] form.list-create-inline')) {
            event.preventDefault();
            createList(form);
        }
    }, true);

    // "Ask to rate after Seen", remembered for this browser.
    document.addEventListener('change', (event) => {
        if (event.target.matches && event.target.matches('[data-pop-ask]')) setAskAfterSeen(event.target.checked);
    });

    // Seen from a tile's eye (tile-actions.js, list tick-off mode too) on an unrated movie: "Rate it?" on that tile.
    document.addEventListener('tile:changed', (event) => {
        const detail = event.detail || {};
        const state = detail.state;
        if (detail.act !== 'seen' || !state || !state.ok || state.seenDelta !== 1 || state.seenLocked || !askAfterSeen()) return;
        const stack = event.target.closest && event.target.closest('[data-tile-actions]');
        const star = stack && stack.querySelector('a[data-tile-rate]');
        if (star && star.dataset.score == null) open(star, true);
    });

    // A list ticked or unticked in a tile's sheet (list-membership.js): the tile's button follows.
    document.addEventListener('list:membership', (event) => {
        const sheet = event.target.closest && event.target.closest('[data-tile-sheet]');
        if (sheet && event.detail) applyLists(sheet.dataset.key, event.detail.count > 0);
    });

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', openFromUrl);
    else openFromUrl();
})();
