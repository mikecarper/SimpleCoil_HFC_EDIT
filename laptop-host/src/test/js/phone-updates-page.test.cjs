const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const html = fs.readFileSync(path.resolve(__dirname, '../../../web/phone-updates.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

class Element {
    constructor(tag = 'div') {
        this.tagName = tag;
        this.children = [];
        this.dataset = {};
        this.hidden = false;
        this.disabled = false;
        this.checked = false;
        this.classes = new Set();
        this.classList = { toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name) };
        this.value = '';
    }
    set textContent(value) { this.text = String(value); this.children = []; }
    get textContent() { return (this.text || '') + this.children.map(child => child.textContent).join(''); }
    set innerHTML(_) { throw Error('Phone details must not become HTML.'); }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.text = ''; this.children = [...children]; }
}

const base = () => ({
    enabled: true, available: true, busy: false, gameActive: false,
    cachedUpdate: { version: '1.59', versionCode: 60 }, phones: [], usbPhones: []
});
const reply = (data, ok = true) => ({ ok, json: async () => data });
const flush = () => new Promise(resolve => setImmediate(resolve));

async function page(initial = base()) {
    const elements = new Map();
    const calls = [];
    const context = {
        document: {
            getElementById(id) { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); },
            createElement: tag => new Element(tag)
        },
        fetch: async (url, options) => { calls.push({ url, options }); return reply(initial); },
        setInterval() {}
    };
    vm.createContext(context);
    vm.runInContext(script, context);
    await flush();
    return {
        context, calls, get: id => elements.get(id),
        render(status) { context.nextStatus = status; vm.runInContext('status=nextStatus; render()', context); },
        run: code => vm.runInContext(code, context)
    };
}

function buttons(element) {
    return element.children.flatMap(child => child.tagName === 'button' ? [child] : buttons(child));
}

test('USB enrollment selects only the explicitly authorized phone and uses the guarded local API', async () => {
    const state = { ...base(), usbPhones: [
        { serial: 'allowed-123', label: 'Game phone', state: 'device' },
        { serial: 'waiting-456', label: 'Other phone', state: 'unauthorized' }
    ] };
    const ui = await page(state);
    const [allowed, waiting] = buttons(ui.get('usb-phones'));
    assert.equal(allowed.disabled, false);
    assert.equal(waiting.disabled, true);
    assert.match(ui.get('usb-phones').textContent, /accept Allow USB debugging/);
    ui.context.fetch = async (url, options) => {
        ui.calls.push({ url, options });
        return reply(options.method ? { ok: true, message: 'Enrolling', status: state } : state);
    };
    await allowed.onclick();
    const request = ui.calls.find(call => call.options.method === 'POST');
    assert.equal(request.url, '/api/phone-updates');
    assert.deepEqual(JSON.parse(request.options.body), { action: 'enroll-usb', serial: 'allowed-123' });
    assert.equal(request.options.headers['X-SimpleCoil-Control'], '1');
    assert.equal(request.options.mode, 'same-origin');
    assert.equal(request.options.redirect, 'error');
    assert.equal(request.options.credentials, 'same-origin');
});

test('rounds and disabled automatic updates prevent enrollment or retry but allow removing a phone', async () => {
    const state = { ...base(), gameActive: true,
        usbPhones: [{ serial: 'usb', state: 'device' }], phones: [{ serial: 'known', state: 'ready' }] };
    const ui = await page(state);
    assert.equal(buttons(ui.get('usb-phones'))[0].disabled, true);
    const [retry, remove] = buttons(ui.get('enrolled-phones'));
    assert.equal(retry.disabled, true);
    assert.equal(remove.disabled, false);
    assert.equal(ui.get('round-note').hidden, false);
    ui.render({ ...state, gameActive: false, enabled: false });
    assert.equal(buttons(ui.get('enrolled-phones'))[0].disabled, true);
    assert.equal(buttons(ui.get('usb-phones'))[0].disabled, false);
});

test('missing ADB gives setup instructions while retaining the ability to disable updates', async () => {
    const ui = await page({ ...base(), available: false });
    assert.equal(ui.get('adb-note').hidden, false);
    assert.equal(ui.get('auto-enabled').disabled, false);
    assert.equal(ui.get('update-status').textContent, 'ADB unavailable');
    ui.render({ ...base(), available: false, enabled: false });
    assert.equal(ui.get('auto-enabled').disabled, true);
    ui.render({ ...base(), busy: true });
    assert.equal(ui.get('auto-enabled').disabled, false);
    assert.match(html, /Install Android SDK Platform-Tools/);
    assert.match(html, /reconnect USB and enroll again/);
    assert.match(html, /game app 1\.59 or newer/);
});

test('phone labels, addresses, states and messages render as text', async () => {
    const injected = '<img src=x onerror="alert(1)">';
    const ui = await page({ ...base(), message: injected, cachedUpdate: { version: injected }, phones: [
        { serial: 'known', label: injected, address: injected, state: injected, message: injected, installedVersion: injected }
    ] });
    assert.match(ui.get('enrolled-phones').textContent, /<img src=x/);
    assert.equal(ui.get('service-message').textContent, injected);
    assert.equal(ui.get('cached-release').textContent, 'Cached game app: ' + injected);
});

test('failed commands restore controls and report the host error', async () => {
    const state = { ...base(), phones: [{ serial: 'known', state: 'ready' }] };
    const ui = await page(state);
    ui.context.fetch = async (_url, options) => options.method ? reply({ message: 'Phone is playing.' }, false) : reply(state);
    await buttons(ui.get('enrolled-phones'))[0].onclick();
    assert.equal(ui.get('message').textContent, 'Phone is playing.');
    assert.equal(ui.get('message').classes.has('error'), true);
    assert.equal(buttons(ui.get('enrolled-phones'))[0].disabled, false);
});

test('an older pending status response cannot undo a newer control result', async () => {
    const state = base();
    const ui = await page(state);
    let finishOld;
    ui.context.fetch = async (_url, options) => options.method
        ? reply({ ok: true, status: { ...state, enabled: false } })
        : new Promise(resolve => { finishOld = resolve; });
    const pendingRefresh = ui.run('refresh()');
    const action = ui.run("control({action:'set-enabled',enabled:false})");
    await action;
    assert.equal(ui.get('auto-enabled').checked, false);
    finishOld(reply(state));
    await pendingRefresh;
    assert.equal(ui.get('auto-enabled').checked, false);
});
