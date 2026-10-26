// Check the real embedded map confirmation without starting Leaflet or a host.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/java/com/simplecoil/laptophost/LaptopHost.java'), 'utf8');
const page = source.match(/private static final String MAP_PAGE = """([\s\S]*?)""";/)[1];
const handler = page.match(/end\.onclick\s*=\s*([\s\S]*?);fitButton\.onclick/)[1];

function control(state, accept = true, duringConfirmation = () => {}) {
    const requests = [], prompts = [];
    const context = {
        state, end: {}, encodeURIComponent,
        confirm(prompt) {
            prompts.push(prompt);
            duringConfirmation(context);
            return accept;
        },
        control(request) { requests.push(new URL(request, 'http://localhost')); }
    };
    vm.runInNewContext(`end.onclick=${handler}`, context);
    context.end.onclick();
    return { requests, prompts };
}

test('an open countdown confirmation keeps its original round and cancellation intent', () => {
    const { requests, prompts } = control(
        { state: 'countdown', eventMode: true, roundtoken: 'displayed-round' }, true,
        context => { context.state = { state: 'running', eventMode: true, roundtoken: 'next-round' }; });
    assert.deepEqual(prompts, ['Cancel the countdown for all players?']);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].pathname, '/api/end');
    assert.equal(requests[0].searchParams.get('roundtoken'), 'displayed-round');
    assert.equal(requests[0].searchParams.get('action'), 'cancel-countdown');
});

test('live controls capture their direct-end or vote intent before a mode change', () => {
    for (const eventMode of [false, true]) {
        const { requests, prompts } = control(
            { state: 'running', eventMode, roundtoken: 'current-round' }, true,
            context => { context.state.eventMode = !eventMode; });
        assert.deepEqual(prompts, [eventMode ? 'End the round for all players?'
            : 'Ask two participating players to approve ending the round?']);
        assert.equal(requests[0].searchParams.get('roundtoken'), 'current-round');
        assert.equal(requests[0].searchParams.get('action'), eventMode ? 'end-round' : 'request-end-vote');
    }
});

test('declining a confirmation sends no end control', () => {
    const { requests } = control({ state: 'countdown', roundtoken: 'current-round' }, false);
    assert.equal(requests.length, 0);
});

test('a map without a round token cannot submit an unscoped end control', () => {
    const { requests, prompts } = control({ state: 'countdown', eventMode: true });
    assert.equal(requests.length, 0);
    assert.equal(prompts.length, 0);
});
