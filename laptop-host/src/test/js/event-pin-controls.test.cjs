const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const web = path.resolve(__dirname, '../../../web');
const source = fs.readFileSync(path.join(web, 'players.js'), 'utf8');
const policy = source.match(/^function updateEventPinValidity\(\)\{.*\}$/m)[0];
const html = fs.readFileSync(path.join(web, 'players.html'), 'utf8');

function editor(stored, value = stored) {
    const pin = { value, setCustomValidity(message) { this.validationMessage = message; } };
    const context = { state: { organizerPin: stored }, byId: () => pin };
    vm.createContext(context);
    vm.runInContext(policy, context);
    return { pin, context, valid: () => context.updateEventPinValidity() };
}

test('new PIN controls advertise two to six digits and preserve leading zeros', () => {
    assert.match(html, /id="organizer-pin"[^>]*pattern="\[0-9\]\{2,6\}" minlength="2" maxlength="6"/);
    for (const value of ['00', '007', '0012', '00123', '001234']) {
        const form = editor('09', value);
        assert.equal(form.valid(), true);
        assert.equal(form.pin.value, value);
        assert.equal(form.pin.maxLength, 6);
        assert.equal(form.pin.validationMessage, '');
    }
});

test('new PIN input rejects other lengths and non-ASCII digits', () => {
    for (const value of ['', '1', '1234567', '12345678', '+12', '12 34', '12.0', '\u0661\u0662']) {
        const form = editor('09', value);
        assert.equal(form.valid(), false);
        assert.notEqual(form.pin.validationMessage, '');
    }
});

test('unchanged legacy PINs remain savable while replacements use the new bounds', () => {
    for (const stored of ['0012345', '00123456']) {
        const form = editor(stored);
        assert.equal(form.valid(), true);
        assert.equal(form.pin.maxLength, 8);
        assert.equal(new RegExp('^(?:' + form.pin.pattern + ')$').test(stored), true);
        form.pin.value = '87654321';
        assert.equal(form.valid(), false);
        assert.equal(new RegExp('^(?:' + form.pin.pattern + ')$').test(form.pin.value), false);
        form.pin.value = '03';
        assert.equal(form.valid(), true);
    }
});

test('a fresh response retires the old legacy exception after applying a shorter PIN', () => {
    const form = editor('00123456');
    assert.equal(form.valid(), true);
    form.context.state = { organizerPin: '03' };
    assert.equal(form.valid(), false);
    assert.equal(form.pin.maxLength, 6);
    assert.equal(form.pin.pattern, '[0-9]{2,6}');
    form.pin.value = '03';
    assert.equal(form.valid(), true);
    assert.equal(form.pin.validationMessage, '');
});
