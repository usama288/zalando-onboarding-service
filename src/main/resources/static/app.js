/**
 * Onboarding client. One module, no build step, no framework, no dependencies.
 *
 * THE RULE THIS FILE IS BUILT AROUND
 * ----------------------------------
 * Nothing in here knows what a German form contains. Every field, its label, its options,
 * its length limit and the condition that reveals it come from the flow definition the API
 * returns. There is no `if (country === ...)` anywhere below, and there must never be one:
 * a fourth market ships a YAML file and this file does not change.
 *
 * The server is the authority on validation. Nothing here re-implements a rule -- the
 * requiredWhen handling hides a field the applicant cannot answer yet, which is convenience,
 * and the server still refuses the payload if a client sends it anyway.
 */

const API = '/api';
const TOKEN_KEY = 'onboarding.draftToken';

// ------------------------------------------------------------------------------ transport

/** A problem+json response, unpacked. Carries the requestId so support can trace the call. */
class ApiError extends Error {
    constructor(status, problem) {
        super((problem && problem.title) || `Request failed (${status})`);
        this.status = status;
        this.violations = (problem && problem.violations) || [];
        this.requestId = problem && problem.requestId;
    }

    /** The first violation code, which is how the caller decides what a 409 actually was. */
    get code() {
        return this.violations.length ? this.violations[0].code : null;
    }
}

async function request(method, path, body) {
    let response;
    try {
        response = await fetch(API + path, {
            method,
            headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body),
        });
    } catch (cause) {
        throw new ApiError(0, { title: 'Could not reach the server. Check your connection.' });
    }
    const text = await response.text();
    const payload = text ? JSON.parse(text) : null;
    if (!response.ok) throw new ApiError(response.status, payload);
    return payload;
}

const api = {
    flows: () => request('GET', '/flows'),
    createDraft: (email, country) => request('POST', '/applications', { email, country }),
    load: (token) => request('GET', `/applications/${encodeURIComponent(token)}`),
    saveSection: (token, sectionId, values) =>
        request('PUT', `/applications/${encodeURIComponent(token)}/sections/${encodeURIComponent(sectionId)}`, values),
    submit: (token) => request('POST', `/applications/${encodeURIComponent(token)}/submit`),
    byReference: (reference) => request('GET', `/applications/reference/${encodeURIComponent(reference)}`),
};

// -------------------------------------------------------------------------------- storage

/**
 * The draft token, and nothing else. It is a bearer credential, so it is the one thing worth
 * keeping and the one thing worth removing the moment it stops being useful.
 */
/**
 * The token for this tab, held in memory as well as in storage.
 *
 * The in-memory copy is what makes a failed write safe. Without it, a browser that refuses
 * localStorage would fall back to whatever token was already there -- silently dropping the
 * applicant into an earlier application, on a shared machine possibly someone else's. The
 * stale value is removed rather than left to be picked up, so the worst case becomes "this
 * session only", which the resume link shown at creation covers.
 */
let sessionToken = null;

const store = {
    token() {
        if (sessionToken) return sessionToken;
        try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
    },
    setToken(token) {
        sessionToken = token;
        try {
            localStorage.setItem(TOKEN_KEY, token);
        } catch {
            // Never leave an older token behind to be resumed instead of this one.
            try { localStorage.removeItem(TOKEN_KEY); } catch { /* nothing more to do */ }
        }
    },
    clearToken() {
        sessionToken = null;
        try { localStorage.removeItem(TOKEN_KEY); } catch { /* nothing to do */ }
    },
};

// ---------------------------------------------------------------------------- DOM plumbing

function el(tag, props, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
        if (value === null || value === undefined || value === false) continue;
        if (key === 'class') node.className = value;
        else if (key === 'text') node.textContent = value;
        else if (key === 'html') throw new Error('never innerHTML: use text');
        else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
        else if (key === 'dataset') Object.assign(node.dataset, value);
        else node.setAttribute(key, value === true ? '' : value);
    }
    for (const child of children.flat()) {
        if (child === null || child === undefined || child === false) continue;
        node.append(child.nodeType ? child : document.createTextNode(String(child)));
    }
    return node;
}

const app = () => document.getElementById('app');
const statusBar = () => document.getElementById('status');

function mount(...nodes) {
    const target = app();
    target.replaceChildren(...nodes);
    window.scrollTo(0, 0);
}

/**
 * A one-line notice for the next screen.
 *
 * Deferred rather than written straight to the DOM because almost every notice is followed
 * by a redirect -- "finish the earlier steps first" is set by the screen that refuses to
 * render. Writing it immediately would put it on a page that is about to be replaced, and
 * the applicant would be moved somewhere else with no explanation.
 */
let pendingNotice = null;

/** The resume link issued at creation, shown once on the screen that follows. */
let issuedResumeUrl = null;

function notify(message, tone) {
    pendingNotice = { message, tone: tone || 'info' };
}

/**
 * The link back into the application, rendered once and then forgotten.
 *
 * No email is sent (A18), so this link is genuinely the only route back if this browser
 * forgets the token -- a private window, cleared site data, another device. Showing it costs
 * one card and removes the case where the form promises saved progress it cannot return to.
 */
function resumeLinkCard() {
    if (!issuedResumeUrl) return null;
    const url = issuedResumeUrl;
    issuedResumeUrl = null;

    return el('div', { class: 'card card-resume' },
        el('h2', { text: 'Your link back to this application' }),
        el('p', { class: 'hint', text: 'We do not email this to you. Save it now — it is how you return if this browser forgets, or if you continue on another device. Anyone with the link can see the application.' }),
        el('input', {
            class: 'control', type: 'text', readonly: true, value: url,
            'aria-label': 'Link back to this application',
            onfocus: (event) => event.target.select(),
        }));
}

/** Renders a pending notice, or clears the bar. Called once per route. */
function flushNotice() {
    const bar = statusBar();
    if (!pendingNotice) { bar.replaceChildren(); return; }
    bar.replaceChildren(el('p', { class: `banner banner-${pendingNotice.tone}`, text: pendingNotice.message }));
    pendingNotice = null;
}

function go(path, replace) {
    if (replace) window.location.replace(`#${path}`);
    else window.location.hash = path;
}

// ------------------------------------------------------------- reading the flow definition

/**
 * Fallback label, used only if a flow definition omits one. Splits camelCase and does
 * nothing cleverer -- anything cleverer would need to know which words are acronyms in
 * which market, which is precisely the knowledge that belongs in the YAML.
 */
function humanise(name) {
    const spaced = name.replace(/([a-z0-9])([A-Z])/g, '$1 $2');
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

const labelOf = (field) => field.label || humanise(field.name);
const isChoice = (field) => Array.isArray(field.options) && field.options.length > 0;

/**
 * Whether a conditional field applies, given what the section currently holds.
 *
 * Mirrors the server's RequiredWhen comparison exactly: coerce to string, trim, compare
 * case-insensitively, and treat absent as "no". The server owns the rule; this only decides
 * whether to put the control on screen.
 */
function applies(field, values) {
    const condition = field.requiredWhen;
    if (!condition) return true;
    const observed = values[condition.field];
    if (observed === null || observed === undefined) return false;
    return String(observed).trim().toLowerCase() === String(condition.equals).trim().toLowerCase();
}

const sectionOf = (flow, id) => flow.sections.find((section) => section.id === id);
const indexOf = (flow, id) => flow.sections.findIndex((section) => section.id === id);

/** The section a resumeStep names, or null for REVIEW, which is a screen and not a section. */
function sectionForStep(flow, step) {
    return flow.sections.find((section) => section.step === step) || null;
}

function resumePath(view) {
    const section = sectionForStep(view.flow, view.resumeStep);
    return section ? `/apply/${section.id}` : '/review';
}

// ------------------------------------------------------------------------ field rendering

/** One control, chosen by the flow definition rather than by the field's name. */
function control(field, value) {
    const id = `f-${field.name}`;
    const shared = { id, name: field.name, 'data-name': field.name };

    if (isChoice(field)) {
        return el('select', { ...shared, class: 'control' },
            el('option', { value: '', text: 'Please choose' }),
            field.options.map((option) => el('option', {
                value: option.value,
                text: option.label,
                selected: String(value ?? '') === option.value,
            })));
    }

    switch (field.type) {
        case 'DATE':
            return el('input', { ...shared, class: 'control', type: 'date', value: value ?? '' });

        case 'BOOLEAN':
            return el('select', { ...shared, class: 'control' },
                el('option', { value: '', text: 'Please choose' }),
                el('option', { value: 'true', text: 'Yes', selected: String(value) === 'true' }),
                el('option', { value: 'false', text: 'No', selected: String(value) === 'false' }));

        case 'CONSENT':
            return el('input', { ...shared, class: 'checkbox', type: 'checkbox', checked: isAccepted(value) });

        default:
            return el('input', {
                ...shared,
                class: 'control',
                type: 'text',
                value: value ?? '',
                maxlength: field.maxLength,
                autocomplete: 'off',
            });
    }
}

/** A stored consent is {version, acceptedAt} (A11); a bare true is accepted too. */
function isAccepted(value) {
    if (!value) return false;
    if (typeof value === 'object') return Boolean(value.acceptedAt);
    return String(value).toLowerCase() === 'true';
}

function fieldRow(field, value) {
    const input = control(field, value);
    const error = el('p', { class: 'field-error', id: `err-${field.name}`, hidden: true });
    const required = field.required;

    if (field.type === 'CONSENT') {
        return el('div', { class: 'field field-consent', dataset: { field: field.name } },
            el('label', { class: 'consent-label', for: input.id },
                input,
                el('span', { text: labelOf(field) }),
                required ? el('span', { class: 'req', text: '*', 'aria-hidden': 'true' }) : null),
            error);
    }

    if (required) input.setAttribute('aria-required', 'true');
    input.setAttribute('aria-describedby', error.id);

    return el('div', { class: 'field', dataset: { field: field.name } },
        el('label', { class: 'field-label', for: input.id },
            labelOf(field),
            required ? el('span', { class: 'req', text: '*', 'aria-hidden': 'true' }) : null),
        input,
        error);
}

/** The value a control currently holds, already in the type the API should receive. */
function readControl(field, form) {
    const input = form.querySelector(`[data-name="${CSS.escape(field.name)}"]`);
    if (!input) return undefined;

    if (field.type === 'CONSENT') {
        // Unchecked consents are omitted, not sent as false: absent is what makes the
        // server's REQUIRED rule fire, and a stored `false` would look like an answer.
        return input.checked ? { version: field.version, acceptedAt: new Date().toISOString() } : undefined;
    }
    if (field.type === 'BOOLEAN' && !isChoice(field)) {
        if (input.value === '') return undefined;
        return input.value === 'true';
    }
    const text = input.value.trim();
    return text === '' ? undefined : text;
}

/**
 * The payload for one section.
 *
 * Two passes, because visibility depends on sibling values: read everything first, then drop
 * what a requiredWhen condition says does not apply. Empty values are omitted rather than
 * sent as "" so that an optional field left blank is genuinely absent from form_data.
 */
function collect(section, form) {
    const raw = {};
    for (const field of section.fields) raw[field.name] = readControl(field, form);

    const payload = {};
    for (const field of section.fields) {
        if (!applies(field, raw)) continue;
        const value = raw[field.name];
        if (value === undefined) continue;
        payload[field.name] = value;
    }
    return payload;
}

/** Show or hide conditional fields as their controlling field changes. */
function refreshConditionals(section, form) {
    const raw = {};
    for (const field of section.fields) raw[field.name] = readControl(field, form);

    for (const field of section.fields) {
        if (!field.requiredWhen) continue;
        const row = form.querySelector(`[data-field="${CSS.escape(field.name)}"]`);
        if (row) row.hidden = !applies(field, raw);
    }
}

// -------------------------------------------------------------------- violation rendering

function clearViolations(form) {
    for (const error of form.querySelectorAll('.field-error')) {
        error.hidden = true;
        error.textContent = '';
    }
    for (const row of form.querySelectorAll('.field')) row.classList.remove('has-error');
}

/**
 * Put each violation beside the field it names.
 *
 * A violation naming a field that is not on screen -- a section-level code, or a field this
 * flow hides -- goes to the summary instead. Nothing is dropped: a rejection the applicant
 * cannot see is a form that refuses to save for no visible reason.
 */
function showViolations(form, error) {
    clearViolations(form);
    const orphans = [];

    for (const violation of error.violations) {
        const row = violation.field
            ? form.querySelector(`[data-field="${CSS.escape(violation.field)}"]`)
            : null;
        if (!row || row.hidden) {
            orphans.push(violation);
            continue;
        }
        const message = row.querySelector('.field-error');
        message.textContent = violation.message;
        message.hidden = false;
        row.classList.add('has-error');
    }

    const box = form.querySelector('.summary') || statusBar();
    // Only summarise what could not be shown inline. Repeating an error the applicant can
    // already see beside the field is noise; dropping one they cannot see is a bug.
    if (orphans.length || !error.violations.length) summarise(box, error, orphans);
    else box.replaceChildren();

    const first = form.querySelector('.has-error .control, .has-error .checkbox');
    if (first) first.focus();
}

function summarise(box, error, violations) {
    box.replaceChildren(el('div', { class: 'banner banner-error', role: 'alert' },
        el('p', { class: 'banner-title', text: error.message }),
        violations.length
            ? el('ul', { class: 'banner-list' },
                violations.map((violation) => el('li', {
                    text: `${violation.field || violation.section || 'Application'}: ${violation.message}`,
                })))
            : null,
        error.requestId ? el('p', { class: 'requestid', text: `Reference for support: ${error.requestId}` }) : null));
}

// ------------------------------------------------------------------------------- screens

async function landing() {
    const flows = await api.flows();

    const form = el('form', { class: 'card', novalidate: true },
        el('h1', { text: 'Start a new application' }),
        el('p', { class: 'lede', text: 'Sole traders in the markets below can apply in a few minutes. No account is needed.' }),
        el('div', { class: 'field', dataset: { field: 'email' } },
            el('label', { class: 'field-label', for: 'email' }, 'Email address',
                el('span', { class: 'req', text: '*', 'aria-hidden': 'true' })),
            el('input', { class: 'control', id: 'email', type: 'email', autocomplete: 'email', 'aria-required': 'true' }),
            el('p', { class: 'field-error', id: 'err-email', hidden: true })),
        el('div', { class: 'field', dataset: { field: 'country' } },
            el('label', { class: 'field-label', for: 'country' }, 'Country of business registration',
                el('span', { class: 'req', text: '*', 'aria-hidden': 'true' })),
            // Built from the API, so a fourth market appears here without a frontend change.
            el('select', { class: 'control', id: 'country', 'aria-required': 'true' },
                el('option', { value: '', text: 'Please choose' }),
                flows.map((flow) => el('option', { value: flow.country, text: countryName(flow.country) }))),
            el('p', { class: 'field-error', id: 'err-country', hidden: true })),
        el('p', { class: 'hint', text: 'Your country selects the form and cannot be changed afterwards. Entering an email always starts a new application.' }),
        el('div', { class: 'summary' }),
        el('button', { class: 'button', type: 'submit' }, 'Start application'));

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const button = form.querySelector('button');
        button.disabled = true;
        clearViolations(form);
        form.querySelector('.summary').replaceChildren();
        try {
            const created = await api.createDraft(
                form.querySelector('#email').value,
                form.querySelector('#country').value);
            store.setToken(created.draftToken);
            issuedResumeUrl = created.resumeUrl;
            const first = created.flow.sections[0];
            go(`/apply/${first.id}`);
        } catch (error) {
            showViolations(form, error);
        } finally {
            button.disabled = false;
        }
    });

    mount(
        el('div', { class: 'stack' },
            await continueCard(),
            form,
            el('p', { class: 'aside' },
                'Already submitted an application? ',
                el('a', { href: '#/lookup', text: 'Look it up by reference' }),
                '.')));
}

/** The device-local resume path. Absent, unreadable or stale tokens simply produce nothing. */
async function continueCard() {
    const token = store.token();
    if (!token) return null;

    let view;
    try {
        view = await api.load(token);
    } catch (error) {
        if (error.status === 404) store.clearToken();
        return null;
    }
    if (view.status !== 'DRAFT') {
        store.clearToken();
        return null;
    }


    const next = sectionForStep(view.flow, view.resumeStep);
    return el('div', { class: 'card card-resume' },
        el('h2', { text: 'Continue on this device' }),
        el('dl', { class: 'facts' },
            fact('Email', view.email),
            fact('Country', countryName(view.country)),
            fact('Next step', next ? next.title : 'Review and submit')),
        el('a', { class: 'button', href: `#${resumePath(view)}`, text: 'Continue application' }));
}

/**
 * The resume link issued at creation. Consumed once and then removed from the address bar --
 * the token is a bearer credential and there is no reason to leave it in browser history
 * longer than it takes to store it.
 */
function resume(token) {
    store.setToken(token);
    go('/', true);
}

async function step(sectionId) {
    const token = store.token();
    if (!token) return go('/', true);

    let view;
    try {
        view = await api.load(token);
    } catch (error) {
        return handleLoadFailure(error);
    }
    if (view.status !== 'DRAFT') return alreadySubmitted(view);

    const flow = view.flow;
    const section = sectionOf(flow, sectionId);
    if (!section) return go(resumePath(view), true);

    // Forward gating, enforced by the server; this only avoids showing a screen that would
    // be refused. The redirect target is the server's own answer to "where was I".
    const completed = view.lastCompletedStep ? sectionForStep(flow, view.lastCompletedStep) : null;
    const furthest = completed ? indexOf(flow, completed.id) : -1;
    const position = indexOf(flow, sectionId);
    if (position > furthest + 1) {
        notify('Finish the earlier steps first.');
        return go(resumePath(view), true);
    }

    const stored = view.formData[sectionId] || {};
    const form = el('form', { class: 'card', novalidate: true },
        el('div', { class: 'summary' }),
        section.fields.map((field) => fieldRow(field, stored[field.name])),
        el('p', { class: 'save-note', text: 'Your progress is saved after every step.' }),
        el('div', { class: 'actions' },
            position > 0
                ? el('a', { class: 'button button-quiet', href: `#/apply/${flow.sections[position - 1].id}`, text: 'Back' })
                : el('a', { class: 'button button-quiet', href: '#/', text: 'Back' }),
            el('button', { class: 'button', type: 'submit' }, 'Save and continue')));

    refreshConditionals(section, form);
    form.addEventListener('change', () => refreshConditionals(section, form));

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const button = form.querySelector('button[type=submit]');
        button.disabled = true;
        try {
            const saved = await api.saveSection(token, sectionId, collect(section, form));
            // Where to go next is the server's answer, not a local guess. Once every section
            // is complete it says REVIEW, so correcting step 2 from the review screen returns
            // there instead of marching the applicant through steps 3 to 6 again.
            const next = flow.sections[position + 1];
            go(saved.resumeStep === 'REVIEW' || !next ? '/review' : `/apply/${next.id}`);
        } catch (error) {
            if (error.status === 409 && error.code === 'APPLICATION_ALREADY_SUBMITTED') {
                // Re-read so the confirmation can show the reference rather than guess.
                return api.load(token).then(alreadySubmitted).catch(() => alreadySubmitted(null));
            }
            showViolations(form, error);
        } finally {
            button.disabled = false;
        }
    });

    mount(
        el('div', { class: 'stack' },
            resumeLinkCard(),
            progress(flow, position, section),
            form));
}

function progress(flow, position, section) {
    const total = flow.sections.length;
    const done = Math.round(((position) / total) * 100);
    return el('div', { class: 'progress-head' },
        el('p', { class: 'eyebrow', text: `Step ${position + 1} of ${total}` }),
        el('h1', { text: section.title }),
        el('div', {
            class: 'progress', role: 'progressbar', 'aria-valuemin': '0', 'aria-valuemax': String(total),
            'aria-valuenow': String(position), 'aria-label': 'Application progress',
        }, el('span', { class: 'progress-bar', style: `width:${done}%` })));
}

async function review() {
    const token = store.token();
    if (!token) return go('/', true);

    let view;
    try {
        view = await api.load(token);
    } catch (error) {
        return handleLoadFailure(error);
    }
    if (view.status !== 'DRAFT') return alreadySubmitted(view);
    if (view.resumeStep !== 'REVIEW') {
        notify('There are still steps to complete.');
        return go(resumePath(view), true);
    }

    const summary = el('div', { class: 'summary' });
    const submit = el('button', { class: 'button', type: 'button' }, 'Submit application');

    submit.addEventListener('click', async () => {
        submit.disabled = true;
        summary.replaceChildren();
        try {
            const result = await api.submit(token);
            store.clearToken();
            go(`/submitted/${encodeURIComponent(result.reference)}`);
        } catch (error) {
            summarise(summary, error, error.violations);
        } finally {
            submit.disabled = false;
        }
    });

    mount(
        el('div', { class: 'stack' },
            el('div', { class: 'progress-head' },
                el('p', { class: 'eyebrow', text: 'Last step' }),
                el('h1', { text: 'Review your application' }),
                el('p', { class: 'lede', text: 'Check every section. You can still change anything until you submit.' })),
            summary,
            view.flow.sections.map((section) => sectionCard(section, view.formData[section.id], `#/apply/${section.id}`)),
            el('div', { class: 'card card-submit' },
                el('p', { text: 'Once submitted, an application cannot be edited or withdrawn.' }),
                submit)));
}

function success(reference) {
    mount(el('div', { class: 'stack' },
        el('div', { class: 'card card-success' },
            el('p', { class: 'eyebrow', text: 'Application submitted' }),
            el('h1', { text: 'Thank you' }),
            el('p', { class: 'lede', text: 'Keep this reference. It is how you or our support team can find your application.' }),
            el('p', { class: 'reference', text: reference }),
            el('p', { class: 'warn', text: 'Your application cannot be edited or withdrawn.' }),
            el('a', { class: 'button button-quiet', href: `#/lookup/${encodeURIComponent(reference)}`, text: 'View your application' }))));
}

async function lookup(reference) {
    const input = el('input', { class: 'control', id: 'reference', type: 'text', value: reference || '', autocomplete: 'off', placeholder: 'ONB-...' });
    const result = el('div');

    const form = el('form', { class: 'card', novalidate: true },
        el('h1', { text: 'Find a submitted application' }),
        el('div', { class: 'field' },
            el('label', { class: 'field-label', for: 'reference', text: 'Reference' }),
            input,
            el('p', { class: 'field-error', id: 'err-reference', hidden: true })),
        el('div', { class: 'summary' }),
        el('button', { class: 'button', type: 'submit' }, 'Find application'));

    async function find(value) {
        form.querySelector('.summary').replaceChildren();
        result.replaceChildren();
        try {
            const view = await api.byReference(value);
            result.replaceChildren(submittedView(view));
        } catch (error) {
            const message = error.status === 404
                ? new ApiError(404, { title: 'No submitted application matches that reference.', requestId: error.requestId })
                : error;
            summarise(form.querySelector('.summary'), message, []);
        }
    }

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        if (input.value.trim()) find(input.value.trim());
    });

    mount(el('div', { class: 'stack' }, form, result));
    if (reference) await find(reference);
}

function submittedView(view) {
    return el('div', { class: 'stack' },
        el('div', { class: 'card card-success' },
            el('p', { class: 'eyebrow', text: 'Submitted application' }),
            el('p', { class: 'reference', text: view.reference }),
            el('dl', { class: 'facts' },
                fact('Status', view.status),
                fact('Submitted', formatInstant(view.submittedAt)),
                fact('Country', countryName(view.country)),
                fact('Email', view.email)),
            el('p', { class: 'warn', text: 'This application is read-only and cannot be edited.' })),
        view.flow.sections.map((section) => sectionCard(section, view.formData[section.id], null)));
}

/** One section, read-only. The same renderer serves Review and the reference lookup. */
function sectionCard(section, data, editHref) {
    const values = data || {};
    const rows = section.fields
        .filter((field) => applies(field, values))
        .map((field) => fact(labelOf(field), display(field, values[field.name])));

    return el('section', { class: 'card' },
        el('div', { class: 'card-head' },
            el('h2', { text: section.title }),
            editHref ? el('a', { class: 'edit', href: editHref, text: 'Edit' }) : null),
        rows.length ? el('dl', { class: 'facts' }, rows) : el('p', { class: 'hint', text: 'Nothing recorded.' }));
}

function fact(term, value) {
    return el('div', { class: 'fact' },
        el('dt', { text: term }),
        el('dd', { text: value }));
}

/** How a stored value reads back. Driven by the field definition, never by the field's name. */
function display(field, value) {
    if (value === undefined || value === null || value === '') return '—';

    if (field.type === 'CONSENT') {
        return isAccepted(value)
            ? `Accepted${value.acceptedAt ? ` on ${formatInstant(value.acceptedAt)}` : ''}`
            : 'Not accepted';
    }
    // The flow definition says which field is a bank account by naming the validator that
    // checks it, so masking follows configuration rather than a hardcoded field name.
    if (field.validators && field.validators.includes('iban')) return maskIban(String(value));

    if (isChoice(field)) {
        const option = field.options.find((candidate) => candidate.value === String(value));
        return option ? option.label : String(value);
    }
    if (field.type === 'BOOLEAN') return String(value) === 'true' ? 'Yes' : 'No';
    return String(value);
}

function maskIban(iban) {
    const bare = iban.replace(/\s+/g, '');
    return bare.length <= 4 ? bare : `${'•'.repeat(Math.min(bare.length - 4, 18))} ${bare.slice(-4)}`;
}

function formatInstant(value) {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

/** Region names come from the platform, so a new market needs no name added here. */
function countryName(code) {
    try {
        return new Intl.DisplayNames([navigator.language || 'en'], { type: 'region' }).of(code) || code;
    } catch {
        return code;
    }
}

// ------------------------------------------------------------------------ failure handling

function handleLoadFailure(error) {
    if (error.status === 404) {
        store.clearToken();
        notify('That application could not be found. It may have been started on another device.', 'error');
        return go('/', true);
    }
    notify(error.message, 'error');
    return go('/', true);
}

/**
 * The token belongs to an application that is already submitted -- a second tab, or a reload
 * after submitting. The token holder may read every field of it, so withholding the reference
 * protected nothing and left them on a lookup screen with nothing to type.
 */
function alreadySubmitted(view) {
    store.clearToken();
    if (view && view.reference) {
        return go(`/submitted/${encodeURIComponent(view.reference)}`, true);
    }
    notify('This application has already been submitted and cannot be changed. Find it by reference.');
    return go('/lookup', true);
}

// --------------------------------------------------------------------------------- router

const routes = [
    [/^\/?$/, landing],
    [/^\/resume\/(.+)$/, resume],
    [/^\/apply\/([^/]+)$/, step],
    [/^\/review$/, review],
    [/^\/submitted\/([^/]+)$/, success],
    [/^\/lookup\/([^/]+)$/, lookup],
    [/^\/lookup$/, lookup],
];

let renderId = 0;

async function route() {
    const path = decodeURIComponent(window.location.hash.slice(1)) || '/';
    const current = ++renderId;
    flushNotice();

    for (const [pattern, screen] of routes) {
        const match = path.match(pattern);
        if (!match) continue;
        try {
            await screen(...match.slice(1));
        } catch (error) {
            if (current !== renderId) return;
            notify(error.message || 'Something went wrong.', 'error');
            flushNotice();
        }
        return;
    }
    go('/', true);
}

window.addEventListener('hashchange', route);
route();
