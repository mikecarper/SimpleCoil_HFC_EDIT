// Dependency-free component checks for the actual embedded dashboard script.
// This small DOM harness is not a replacement for browser/device testing.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/java/com/simplecoil/laptophost/LaptopHost.java'), 'utf8');
const page = source.match(/private static final String ROUND_REPORT_PAGE = """([\s\S]*?)""";/)[1];
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];

class Element {
    constructor(tag) {
        this.tag = tag;
        this.children = [];
        this.attributes = {};
        this.style = {};
        this.hidden = false;
        this.value = '';
        this.text = '';
    }
    set textContent(value) { this.text = String(value); this.children = []; }
    get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.text = ''; this.children = [...children]; }
    setAttribute(key, value) { this.attributes[key] = String(value); }
}

function fixture() {
    return {
        roundtoken: '12345678-1234-1234-1234-123456789abc', roundid: 7,
        mode: 'teams', reason: 'Time limit', started: true, complete: true,
        durationMs: 120000, omittedEvents: 0,
        accuracyDefinition: 'Confirmed gun IR hits / shots; grenades excluded',
        timing: 'Host receipt times relative to actual GO',
        players: [
            { key: 'p0', name: 'Blue One', team: 1, playerID: 1, seatIDs: [1, 3],
                kills: 3, deaths: 1, hits: 6, shots: 20, accuracy: 30,
                bestkillstreak: 3, bestdeathstreak: 1, skillbefore: 100, skillafter: 114, complete: true },
            { key: 'p1', name: 'Red Two', team: 2, playerID: 2, seatIDs: [2],
                kills: 1, deaths: 3, hits: 4, shots: 10, accuracy: 40,
                bestkillstreak: 1, bestdeathstreak: 3, skillbefore: 100, skillafter: 86, complete: true }
        ],
        events: [
            { timeMs: 0, kind: 'start', count: 1, detail: 'Round started' },
            { timeMs: 12000, kind: 'shot', actor: 'p0', count: 4, detail: 'Shots grouped' },
            { timeMs: 13000, kind: 'hit', actor: 'p0', target: 'p1', count: 1, detail: 'Confirmed hit' },
            { timeMs: 15000, kind: 'kill', actor: 'p0', target: 'p1', count: 1, detail: 'Confirmed elimination' },
            { timeMs: 15000, kind: 'death', actor: 'p1', target: 'p0', count: 1, detail: 'Eliminated' },
            { timeMs: 27000, kind: 'respawn', actor: 'p1', count: 1, detail: 'QR respawn' },
            { timeMs: 42000, kind: 'powerup', actor: 'p1', count: 1, detail: 'Shield boost' },
            { timeMs: 65000, kind: 'join', actor: 'p0', count: 1, detail: 'Reconnected as player 3' },
            { timeMs: 120000, kind: 'end', count: 1, detail: 'Time limit' }
        ]
    };
}

async function render(report = fixture()) {
    const elements = new Map();
    const document = {
        querySelector(selector) {
            if (!elements.has(selector)) elements.set(selector, new Element(selector));
            return elements.get(selector);
        },
        createElement: tag => new Element(tag),
        createElementNS: (_, tag) => new Element(tag),
        createTextNode: text => { const node = new Element('#text'); node.textContent = text; return node; }
    };
    const context = vm.createContext({
        document, URLSearchParams, location: { search: '' }, setTimeout: () => {},
        fetch: async url => ({ ok: true, json: async () => url === '/api/reports'
            ? { reports: [{ roundtoken: report.roundtoken, roundid: report.roundid,
                mode: report.mode, durationMs: report.durationMs }] }
            : structuredClone(report) })
    });
    await vm.runInContext(script, context);
    return { context, elements };
}

const descendants = node => [node, ...node.children.flatMap(descendants)];
const marks = svg => descendants(svg).filter(node => node.attributes.tabindex === '0');

test('completed report renders elapsed axis, player lanes, stats and accessible marks', async () => {
    const { elements } = await render();
    const svg = elements.get('#timeline');
    assert.equal(elements.get('#review').hidden, false);
    assert.equal(elements.get('#empty').hidden, true);
    assert.match(svg.attributes.viewBox, /^0 0 1400 /);
    assert.match(svg.textContent, /2:00/);
    assert.match(svg.textContent, /Blue One/);
    assert.match(svg.textContent, /Red Two/);
    assert.equal(marks(svg).length, 8); // Shot activity starts hidden.
    assert.ok(marks(svg).every(mark => mark.attributes['aria-label']));
    const rows = elements.get('#rows').children;
    assert.equal(rows.length, 2);
    assert.equal(rows[0].children[4].textContent, '30.0%');
    assert.equal(rows[0].children[7].textContent, '100 / 114');
    assert.match(elements.get('#download').href, /\/api\/report\?roundtoken=/);
});

test('legend toggles shots and kills without changing the report totals', async () => {
    const { elements } = await render();
    const controls = elements.get('#filters').children;
    const shots = controls[0].children[0];
    shots.checked = true;
    shots.onchange();
    assert.equal(marks(elements.get('#timeline')).length, 9);
    const kills = controls[2].children[0];
    kills.checked = false;
    kills.onchange();
    assert.equal(marks(elements.get('#timeline')).length, 8);
    assert.equal(elements.get('#rows').children[0].children[1].textContent, '3');
});

test('missing shots show N/A rather than invented zero accuracy', async () => {
    const report = fixture();
    report.players[0].shots = 0;
    report.players[0].accuracy = null;
    const { elements } = await render(report);
    assert.equal(elements.get('#rows').children[0].children[4].textContent, 'N/A');
});

test('partial and clipped histories visibly disclose their limitations', async () => {
    const report = fixture();
    report.complete = false;
    report.players[0].complete = false;
    report.omittedEvents = 11;
    const { elements } = await render(report);
    assert.match(elements.get('#quality').textContent, /incomplete/);
    assert.match(elements.get('#quality').textContent, /11 event batches omitted/);
    assert.equal(elements.get('#rows').children[0].children[8].textContent, 'Partial');
});

test('player names are assigned as text rather than injected markup', async () => {
    const report = fixture();
    report.players[0].name = '<img src=x onerror=alert(1)>';
    const { elements } = await render(report);
    const row = elements.get('#rows').children[0].children[0];
    assert.equal(row.textContent, report.players[0].name + ' / Team 1');
    assert.equal(row.children.length, 0);
});

test('a retained report can generate a standalone SVG for layout review', async () => {
    const { elements } = await render();
    const svg = elements.get('#timeline');
    const escape = value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    const serialize = node => node.tag === '#text' ? escape(node.textContent)
        : '<' + node.tag + Object.entries(node.attributes)
            .map(([key, value]) => ' ' + key + '="' + escape(value) + '"').join('') + '>'
            + escape(node.text) + node.children.map(serialize).join('') + '</' + node.tag + '>';
    svg.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    const width = svg.attributes.viewBox.split(' ')[2];
    const height = svg.attributes.viewBox.split(' ')[3];
    const background = new Element('rect');
    for (const [key, value] of Object.entries({ width, height, fill: '#111e2b' }))
        background.setAttribute(key, value);
    svg.children.unshift(background);
    const xml = serialize(svg);
    assert.match(xml, /aria-label="0:15 \/ kill \/ Blue One -&gt; Red Two/);
    if (process.env.SIMPLECOIL_TIMELINE_SVG) fs.writeFileSync(process.env.SIMPLECOIL_TIMELINE_SVG, xml);
});
