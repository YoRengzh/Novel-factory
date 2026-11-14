'use strict';

// 离线回归：真实 UMD + 内存 DOM / JSON / 定时器；不访问后端、真实资料或向量库。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const staticRoot = path.resolve(__dirname, '../../main/resources/static');
const source = fs.readFileSync(path.join(staticRoot, 'reference-index-manager.js'), 'utf8');
const html = fs.readFileSync(path.join(staticRoot, 'index.html'), 'utf8');
const BASE = '/api/reference-index';
const copy = value => JSON.parse(JSON.stringify(value));
const flush = async () => { for (let i = 0; i < 3; i++) await new Promise(resolve => setImmediate(resolve)); };
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const ownerA = { storyDirName: 'book-a', novelTitle: '小说 A' };
const ownerB = { storyDirName: 'book-b', novelTitle: '小说 B' };

function fixture() {
    return {
        backend: 'qdrant', version: 'v1', busy: false, busyReason: '', task: null,
        collections: [
            { id: 'ref/current', name: '当前参考', kind: 'reference', state: 'current', dimensions: 1536, pointCount: 52, owners: [], canDelete: true, canRebuild: true },
            { id: 'ref-old', name: '历史参考', kind: 'reference', state: 'historical', dimensions: 768, pointCount: 3, owners: [], canDelete: true, canRebuild: true },
            { id: 'ref-legacy', name: '旧版参考', kind: 'reference', state: 'legacy', dimensions: null, pointCount: 2, owners: [], canDelete: true, canRebuild: true },
            { id: 'ref-error', name: '错误集合', kind: 'reference', state: 'error', dimensions: null, pointCount: null, error: '读取失败 <script>不执行</script>', owners: [], canDelete: true, canRebuild: false },
            { id: 'story-1', name: 'opaque_story_name', kind: 'story', state: 'current', dimensions: 1536, pointCount: 4, owners: [ownerA], canDelete: true, canRebuild: true },
            { id: 'world-1', name: 'opaque_world_name', kind: 'world', state: 'current', dimensions: 1536, pointCount: 8, owners: [ownerA, ownerB], canDelete: true, canRebuild: true },
            { id: 'ref-orphan', name: '无源集合', kind: 'reference', state: 'orphan', dimensions: 768, pointCount: 1, owners: [], canDelete: true, canRebuild: false },
            { id: 'readonly', name: '未授权集合', kind: 'reference', state: 'current', owners: [], canDelete: false, canRebuild: false },
            { id: 'unknown', name: '未知集合', kind: 'unknown', state: 'unknown', owners: [], canDelete: true, canRebuild: true }
        ],
        sources: [
            { id: 'source-enabled', title: '写作技法', group: '技法', builtin: false, enabled: true, blockCount: 52, collections: ['ref/current', 'ref-old'] },
            { id: 'source-disabled', title: '内置范例', group: '范例', builtin: true, enabled: false, blockCount: 0, collections: [] }
        ]
    };
}
const resultItem = (id, status = 'completed', message = '处理完成') => ({ id, name: fixture().collections.find(item => item.id === id)?.name || id, status, message });

function memoryDom() {
    class Element {
        constructor(tag = 'div') {
            this.tagName = tag.toUpperCase(); this.id = ''; this.attributes = {}; this.children = [];
            this.parentElement = null; this.listeners = {}; this.disabled = false; this.className = ''; this._text = '';
            this.value = ''; this.checked = false; this.indeterminate = false;
            const classes = () => new Set(this.className.split(/\s+/).filter(Boolean));
            this.classList = {
                contains: key => classes().has(key),
                add: (...keys) => { const set = classes(); keys.forEach(key => set.add(key)); this.className = [...set].join(' '); },
                remove: (...keys) => { const set = classes(); keys.forEach(key => set.delete(key)); this.className = [...set].join(' '); },
                toggle: (key, force) => { const enabled = force === undefined ? !classes().has(key) : !!force; this.classList[enabled ? 'add' : 'remove'](key); return enabled; }
            };
        }
        get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
        set textContent(value) { this.children.forEach(child => { child.parentElement = null; }); this.children = []; this._text = String(value); }
        set innerHTML(value) { throw new Error('不得通过 innerHTML 写入内容：' + value); }
        appendChild(child) { this.children.push(child); child.parentElement = this; return child; }
        setAttribute(key, value) { this.attributes[key] = String(value); }
        getAttribute(key) { return this.attributes[key] ?? null; }
        addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
        emit(type) { return this.disabled ? Promise.resolve() : Promise.all((this.listeners[type] || []).map(listener => listener({ target: this, preventDefault() {} }))); }
        click() { return this.emit('click'); }
        matches(selector) { return selector[0] === '#' ? this.id === selector.slice(1) : selector[0] === '.' ? this.classList.contains(selector.slice(1)) : this.tagName.toLowerCase() === selector; }
        querySelectorAll(selector) { return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]); }
        querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    }
    const root = new Element('section'); root.id = 'reference-index-manager';
    const elements = { 'reference-index-manager': root };
    const start = html.indexOf('<section id="reference-index-manager"'), end = html.indexOf('<!-- 05 资料设置', start);
    assert.ok(start >= 0 && end > start, '真实 HTML 必须有独立管理容器');
    for (const [, tag, attrs] of html.slice(start, end).matchAll(/<([\w-]+)\b([^>]*\bid="[^"]+"[^>]*)>/g)) {
        const id = /\bid="([^"]+)"/.exec(attrs)[1]; if (id === root.id) continue;
        const item = new Element(tag); item.id = id;
        for (const [, key, value] of attrs.matchAll(/([\w-]+)="([^"]*)"/g)) {
            item.setAttribute(key, value); if (key === 'class') item.className = value;
        }
        elements[id] = item; root.appendChild(item);
    }
    const doc = { createElement: tag => new Element(tag), getElementById: id => elements[id] || root.querySelector('#' + id) };
    return { root, elements, doc };
}

function harness(overrides = {}) {
    const dom = memoryDom(), requests = [], confirmations = [], notifications = [], timers = new Map(); let nextTimer = 0;
    const h = { ...dom, requests, confirmations, notifications, timers, summary: fixture(), confirmValue: true, operationResult: null,
        pointsResult: { points: [{ id: 1, payload: { fileName: '资料.md', sectionTitle: '第一节', text: '正文内容', headingPath: ['章', '节'], corpusVersion: 'v1' } }], nextCursor: null } };
    h.defaultReply = record => {
        if (record.url === BASE) return copy(h.summary);
        if (record.url === BASE + '/preview') {
            const body = record.body, selected = body.collectionIds || ['ref/current'];
            const value = { token: 'token-' + body.action, action: body.action, title: '预览 ' + body.action,
                message: '服务端完整影响说明：清理索引，不删除原文', version: h.summary.version,
                collections: selected.map(id => { const item = h.summary.collections.find(item => item.id === id); return { id, name: item.name, pointCount: item.pointCount ?? null }; }),
                sources: body.sourceId ? [{ id: body.sourceId, title: '受影响资料' }] : [],
                embeddingChars: body.action === 'rebuild' ? 12000 : 0, embeddingBatches: body.action === 'rebuild' ? 3 : 0 };
            h.lastPreview = value; return value;
        }
        if (record.url === BASE + '/operations') return h.operationResult ? copy(h.operationResult)
            : { completed: true, message: '操作已完成', results: h.lastPreview.collections.map(item => resultItem(item.id)) };
        if (record.url.includes('/points?')) return copy(h.pointsResult);
        if (record.url.includes('/tasks/')) return { id: decodeURIComponent(record.url.split('/tasks/')[1]), status: 'running', message: '构建中', results: [] };
        throw new Error('未允许的替身请求：' + record.url);
    };
    const context = vm.createContext({ module: { exports: {} }, window: {},
        fetch() { throw new Error('离线测试不得真实调用 fetch'); },
        setTimeout: (callback, delay) => { const id = ++nextTimer; timers.set(id, { callback, delay }); return id; }, clearTimeout: id => timers.delete(id) });
    vm.runInContext(source, context, { filename: 'reference-index-manager.js' }); h.exports = context.module.exports;
    h.api = h.exports.create({ document: dom.doc,
        fetchJson: (url, options = {}) => {
            const record = { url, method: options.method || 'GET', body: options.body ? JSON.parse(options.body) : null }; requests.push(record);
            return Promise.resolve(overrides.handle ? overrides.handle(record, h) : h.defaultReply(record));
        },
        confirm: message => { confirmations.push(message); return overrides.confirm ? overrides.confirm(message, h) : h.confirmValue; },
        notify: message => notifications.push(message) });
    h.button = (id, label) => {
        const container = dom.doc.getElementById(id), found = label ? container.querySelectorAll('button').find(item => item.textContent === label) : container;
        assert.ok(found, '未找到按钮：' + id + ' / ' + (label || '')); return found;
    };
    h.click = (id, label) => h.button(id, label).click();
    h.change = (id, value) => { const item = dom.doc.getElementById(id); item.value = value; return item.emit('change'); };
    h.rows = () => h.elements['reference-index-collections'].querySelector('tbody').children;
    h.row = id => { const row = h.rows().find(item => item.getAttribute('data-collection-id') === id); assert.ok(row, '必须显示集合 ' + id); return row; };
    h.select = (id, checked = true) => { const input = h.row(id).querySelector('input'); input.checked = checked; return input.emit('change'); };
    h.preview = (id, action = 'delete') => h.row(id).querySelectorAll('button').find(item => item.textContent === '预览' + (action === 'delete' ? '删除集合' : '重建集合')).click();
    h.inspect = async id => { await h.change('reference-index-inspect-collection', id); await h.click('btn-read-reference-points'); };
    h.tick = async () => { const first = timers.entries().next().value; assert.ok(first, '必须有任务定时器'); timers.delete(first[0]); first[1].callback(); await flush(); };
    return h;
}
async function begin(h, id = 'ref/current', action = 'delete') { await h.api.refresh(); await h.preview(id, action); }

// 导航、布局与只读辅助。
test('UMD/create/refresh 契约：初始化不请求，两次refresh仅GET，不自动收费', async () => {
    const h = harness(); assert.equal(typeof h.exports.create, 'function'); assert.equal(typeof h.api.refresh, 'function');
    assert.equal(h.requests.length, 0); assert.equal(h.timers.size, 0); assert.equal(h.button('btn-rebuild-reference-index').disabled, true);
    const browser = vm.createContext({ window: {} }); vm.runInContext(source, browser); assert.equal(typeof browser.window.ReferenceIndexManager.create, 'function');
    await h.api.refresh(); await h.api.refresh(); assert.deepEqual(h.requests.map(item => [item.method, item.url]), [['GET', BASE], ['GET', BASE]]);
    assert.equal(h.confirmations.length, 0); assert.equal(h.notifications.length, 0);
});
test('独立03索引tab，manager完全移出settings；04模型/05资料/06设置，辅助默认details收起', () => {
    const navs = [...html.matchAll(/<button\b[^>]*data-tab="([^"]+)"[^>]*>\s*<span\b[^>]*>(\d+)<\/span>/g)].map(match => [match[1], match[2]]);
    assert.deepEqual(navs, [['generate', '01'], ['library', '02'], ['reference-index', '03'], ['models', '04'], ['assets', '05'], ['settings', '06']]);
    const settings = html.slice(html.indexOf('<section id="tab-settings"'), html.indexOf('<section id="tab-reference-index"'));
    assert.ok(settings.replace(/<!--[\s\S]*?-->/g, '').trimEnd().endsWith('</section>')); assert.ok(!settings.includes('id="reference-index-manager"'));
    assert.ok(html.indexOf('id="reference-index-manager"') > html.indexOf('id="tab-reference-index"'));
    for (const id of ['reference-index-sources-details', 'reference-index-points-details']) {
        assert.match(html, new RegExp('<details[^>]*id="' + id + '"[^>]*>')); assert.doesNotMatch(new RegExp('<details[^>]*id="' + id + '"[^>]*>').exec(html)[0], /\bopen\b/);
    }
    assert.ok(html.indexOf('src="reference-index-manager.js"') < html.indexOf('src="app.js"')); assert.ok(html.includes('href="reference-index-manager.css"'));
    for (const [, id] of source.matchAll(/el\('([^']+)'\)/g)) assert.equal([...html.matchAll(new RegExp('\\bid="' + id + '"', 'g'))].length, 1, id + ' 必须唯一');
    const app = fs.readFileSync(path.join(staticRoot, 'app.js'), 'utf8');
    const nav = app.slice(app.indexOf('// ===== Tab 切换 ====='), app.indexOf('// ===== 生成表单 ====='));
    assert.match(nav, /btn\.dataset\.tab === 'reference-index' && referenceIndexManager.*referenceIndexManager\.refresh\(\)/);
    assert.doesNotMatch(nav, /settings' && referenceIndexManager|previewAction|\/operations|\/preview|rebuild/);
    assert.match(app, /window\.ReferenceIndexManager\.create\(\{/); assert.doesNotMatch(source, /canClear|collectionName|JSON\.stringify\(\{\s*collectionId:/);
});
test('主表格显示checkbox/name/owners/dim/count/state/actions，不猜内部集合名归属', async () => {
    const h = harness(); await h.api.refresh(); const table = h.elements['reference-index-collections'].querySelector('table');
    assert.ok(table); assert.match(table.querySelector('thead').textContent, /集合名称.*所属小说.*维度.*分块数.*状态.*操作/);
    assert.equal(h.rows().length, 9); assert.equal(h.row('ref/current').querySelector('input').type, 'checkbox');
    assert.match(h.row('ref/current').textContent, /参考资料.*全局共享.*1,536.*52.*当前/);
    assert.match(h.row('story-1').querySelectorAll('td')[2].textContent, /小说私有.*小说 A \[book-a\]/);
    assert.match(h.row('world-1').querySelectorAll('td')[2].textContent, /共享世界.*小说 A \[book-a\].*小说 B \[book-b\]/);
    assert.doesNotMatch(h.row('world-1').querySelectorAll('td')[2].textContent, /opaque/);
    assert.equal(h.elements['reference-index-collections'].querySelectorAll('button').some(item => /分块|清空|停用/.test(item.textContent)), false);
});
test('所有不可信名称、owners、消息及分块正文均纯文本；分块只读不显示vector', async () => {
    const h = harness(), attack = '<img src=x onerror="alert(1)"><script>危险</script>';
    h.summary.backend = attack; h.summary.collections[0].name = attack; h.summary.collections[4].owners = [{ storyDirName: attack, novelTitle: attack }]; h.summary.sources[0].title = attack;
    await h.api.refresh(); assert.ok(h.elements['reference-index-collections'].textContent.includes(attack));
    assert.match(h.elements['reference-index-collections'].textContent, /读取失败 <script>不执行<\/script>/);
    assert.equal(h.root.querySelectorAll('img').length, 0); assert.equal(h.root.querySelectorAll('script').length, 0);
    h.pointsResult = { points: [{ id: attack, vector: [666], payload: { fileName: attack, sectionTitle: attack, text: attack, headingPath: [attack], corpusVersion: attack } }, { id: 2, payload: {} }], nextCursor: null };
    await h.inspect('ref/current'); assert.ok(h.elements['reference-index-points'].textContent.includes(attack));
    assert.match(h.elements['reference-index-points'].textContent, /旧索引无分块正文/); assert.doesNotMatch(h.elements['reference-index-points'].textContent, /666/);
    assert.equal(h.elements['reference-index-points'].querySelectorAll('button').length, 0);
    assert.ok(h.root.querySelectorAll('input').every(item => item.type === 'checkbox')); assert.equal(h.root.querySelectorAll('textarea').length, 0);
});

// 筛选、选择和服务端权限。
test('kind及小说筛选相交，共享world按任意owner可见且仍显示完整owners', async () => {
    const h = harness(); await h.api.refresh(); await h.change('reference-index-owner-filter', 'novel:book-a');
    assert.deepEqual(h.rows().map(item => item.getAttribute('data-collection-id')), ['story-1', 'world-1']);
    await h.change('reference-index-kind-filter', 'world'); assert.equal(h.rows().length, 1); assert.match(h.row('world-1').textContent, /小说 A.*小说 B/);
    await h.change('reference-index-owner-filter', 'novel:book-b'); assert.equal(h.rows().length, 1);
    await h.change('reference-index-kind-filter', 'story'); assert.equal(h.rows().length, 0);
    await h.change('reference-index-kind-filter', ''); await h.change('reference-index-owner-filter', 'reference');
    assert.equal(h.rows().length, 6); assert.ok(h.rows().every(item => item.textContent.includes('参考资料')));
    assert.equal(h.requests.filter(item => item.method === 'POST').length, 0);
});
test('多选跨筛选保持，可见全选/取消不影响隐藏选择，取消全部；表头indeterminate正确', async () => {
    const h = harness(); await h.api.refresh(); await h.select('ref/current');
    let header = h.elements['reference-index-collections'].querySelector('thead').querySelector('input'); assert.equal(header.indeterminate, true);
    await h.change('reference-index-owner-filter', 'novel:book-a'); await h.click('btn-reference-select-visible');
    assert.match(h.elements['reference-index-selection'].textContent, /已选择 3 个/); assert.equal(h.button('btn-rebuild-reference-index').disabled, false);
    header = h.elements['reference-index-collections'].querySelector('thead').querySelector('input'); assert.equal(header.checked, true);
    header.checked = false; await header.emit('change'); assert.match(h.elements['reference-index-selection'].textContent, /已选择 1 个/);
    await h.change('reference-index-owner-filter', ''); assert.equal(h.row('ref/current').querySelector('input').checked, true);
    await h.click('btn-reference-clear-selection'); assert.match(h.elements['reference-index-selection'].textContent, /已选择 0 个/);
    assert.equal(h.button('btn-delete-reference-index').disabled, true);
});
test('reference/story/world均按canDelete/canRebuild授权；orphan仅delete，未知/未授权不可选择写入', async () => {
    const h = harness(); await h.api.refresh();
    for (const id of ['ref/current', 'ref-old', 'ref-legacy', 'story-1', 'world-1']) assert.deepEqual(h.row(id).querySelectorAll('button').map(item => item.disabled), [false, false]);
    assert.deepEqual(h.row('ref-orphan').querySelectorAll('button').map(item => item.disabled), [false, true]);
    for (const id of ['readonly', 'unknown']) { assert.ok(h.row(id).querySelector('input').disabled); assert.ok(h.row(id).querySelectorAll('button').every(item => item.disabled)); }
    await h.click('btn-reference-select-visible'); assert.match(h.elements['reference-index-selection'].textContent, /已选择 7 个/);
    assert.equal(h.button('btn-delete-reference-index').disabled, false); assert.equal(h.button('btn-rebuild-reference-index').disabled, true);
    h.summary.collections.find(item => item.id === 'ref-orphan').canRebuild = true; await h.api.refresh();
    assert.equal(h.row('ref-orphan').querySelectorAll('button')[1].disabled, true, '无源状态不得因错误permission被允许重建');
});
test('批量限制1..100，超过100仅禁批量不禁单项；100项使用collectionIds', async () => {
    const h = harness(); h.summary.collections = Array.from({ length: 101 }, (_, index) => ({ ...fixture().collections[0], id: 'ref-' + index, name: '集合 ' + index }));
    await h.api.refresh(); await h.click('btn-reference-select-visible');
    assert.equal(h.button('btn-delete-reference-index').disabled, true); assert.match(h.elements['reference-index-selection'].textContent, /请减少选择/);
    await h.preview('ref-0'); assert.deepEqual(h.requests.at(-1).body, { action: 'delete', collectionIds: ['ref-0'] });
    await h.click('btn-cancel-reference-index'); await h.select('ref-100', false); await h.click('btn-delete-reference-index');
    assert.equal(h.requests.at(-1).body.collectionIds.length, 100); assert.deepEqual(Object.keys(h.requests.at(-1).body), ['action', 'collectionIds']);
});

// 预览与确认安全链路。
test('单项及批量仅action+collectionIds，完整owners/影响范围/cost展示后confirm，写请求仅token', async () => {
    for (const action of ['delete', 'rebuild']) {
        const h = harness(); await h.api.refresh(); await h.select('story-1'); await h.select('world-1');
        await h.click(action === 'delete' ? 'btn-delete-reference-index' : 'btn-rebuild-reference-index');
        assert.deepEqual(h.requests.at(-1).body, { action, collectionIds: ['story-1', 'world-1'] }); assert.equal(h.confirmations.length, 0);
        const preview = h.elements['reference-index-preview'].textContent;
        assert.match(preview, /服务端完整影响说明/); assert.match(preview, /小说 A \[book-a\].*小说 B \[book-b\]/); assert.match(preview, /影响范围：.*\n世界索引影响全部所属小说/s);
        await h.click('btn-confirm-reference-index'); await flush(); assert.equal(h.confirmations.length, 1);
        assert.match(h.confirmations[0], /正文、摘要、story-bible/); assert.match(h.confirmations[0], /影响集合：/); assert.match(h.confirmations[0], /预计 Embedding：/);
        if (action === 'rebuild') assert.match(h.confirmations[0], /12,000 字符，3 批调用。\n\n可能产生费用/);
        assert.deepEqual(h.requests.find(item => item.url === BASE + '/operations').body, { token: 'token-' + action });
        assert.ok(h.requests.every(item => !JSON.stringify(item.body).includes('collectionName') && !JSON.stringify(item.body).includes('"collectionId"')));
        assert.equal(h.notifications.length, 1); assert.match(h.elements['reference-index-results'].textContent, /处理完成/);
    }
    for (const id of ['ref/current', 'story-1', 'world-1', 'ref-orphan']) {
        const h = harness(); await begin(h, id); assert.deepEqual(h.requests.at(-1).body, { action: 'delete', collectionIds: [id] });
    }
});
test('历史reference重建明确当前来源/current索引非还原历史，允许已知有权限去重目标', async () => {
    const h = harness({ handle: (record, self) => {
        const value = self.defaultReply(record); if (record.url.endsWith('/preview')) value.collections = [{ id: 'ref/current', name: '当前参考', pointCount: 52 }]; return value;
    } });
    await begin(h, 'ref-old', 'rebuild'); assert.deepEqual(h.requests.at(-1).body, { action: 'rebuild', collectionIds: ['ref-old'] });
    assert.match(h.elements['reference-index-preview'].textContent, /按当前启用来源重建当前索引，并非还原历史/);
    assert.match(h.elements['reference-index-preview'].textContent, /本次选择：历史参考/); assert.match(h.elements['reference-index-preview'].textContent, /影响集合：\n当前参考/);
    await h.click('btn-confirm-reference-index'); await flush(); assert.equal(h.requests.filter(item => item.url.endsWith('/operations')).length, 1);
});
test('多个reference重建去重只返回一个实际构建结果，不按选择数量假失败', async () => {
    const h = harness(); h.operationResult = { completed: true, message: '参考索引已按当前来源去重重建', results: [resultItem('ref/current')] };
    await h.api.refresh(); await h.select('ref/current'); await h.select('ref-old'); await h.select('ref-legacy');
    await h.click('btn-rebuild-reference-index'); assert.deepEqual(h.requests.at(-1).body.collectionIds, ['ref/current', 'ref-old', 'ref-legacy']);
    assert.match(h.elements['reference-index-preview'].textContent, /历史参考/);
    await h.click('btn-confirm-reference-index'); await flush();
    assert.match(h.elements['reference-index-results'].textContent, /执行结果 · 已完成/);
    assert.doesNotMatch(h.elements['reference-index-results'].textContent, /未全部完成|缺少执行结果/);
    assert.equal(h.notifications.length, 1); assert.ok(h.notifications[0].startsWith('操作已完成：'));
});
test('资料停用/恢复保留sourceId辅助API，也须全文预览及确认，无主表点级操作', async () => {
    for (const [action, label, sourceId] of [['disable', '预览停用', 'source-enabled'], ['enable', '预览恢复', 'source-disabled']]) {
        const h = harness(); await h.api.refresh(); await h.click('reference-index-sources', label);
        assert.deepEqual(h.requests.at(-1).body, { action, sourceId }); assert.equal(h.confirmations.length, 0);
        await h.click('btn-confirm-reference-index'); await flush(); assert.equal(h.confirmations.length, 1);
        assert.match(h.confirmations[0], /影响资料：/); assert.match(h.confirmations[0], /原文保留|原文/);
    }
});
test('确认取消/取消预览不写不报成功，不残留可复用token', async () => {
    for (const way of ['confirm', 'preview']) {
        const h = harness(); await begin(h); h.confirmValue = false;
        await h.click(way === 'confirm' ? 'btn-confirm-reference-index' : 'btn-cancel-reference-index');
        assert.equal(h.requests.some(item => item.url.endsWith('/operations')), false); assert.equal(h.notifications.length, 0);
        assert.match(h.elements['reference-index-status'].textContent, /取消/); assert.equal(h.doc.getElementById('btn-confirm-reference-index'), null);
    }
});
test('预览每个id必须已知且对应权限true：未知、任意name、只读及无源重建全部拒绝', async () => {
    for (const [action, injected] of [['delete', 'not-known'], ['delete', 'opaque_world_name'], ['delete', 'readonly'], ['delete', 'unknown'], ['rebuild', 'ref-orphan'], ['rebuild', 'ref-error']]) {
        const h = harness({ handle: (record, self) => { const value = self.defaultReply(record); if (record.url.endsWith('/preview')) value.collections = [{ id: injected, name: '可伪造显示名称', pointCount: 3 }]; return value; } });
        await begin(h, 'ref/current', action); assert.match(h.elements['reference-index-status'].textContent, /预览失败/);
        assert.equal(h.elements['reference-index-preview'].classList.contains('hidden'), true); assert.equal(h.confirmations.length, 0);
        assert.equal(h.requests.some(item => item.url.endsWith('/operations')), false);
    }
    const h = harness(); h.summary.collections.find(item => item.id === 'story-1').canDelete = false; await h.api.refresh();
    await h.preview('story-1'); assert.equal(h.requests.filter(item => item.method === 'POST').length, 0);
});
test('action/token/version/cost/重复id/空影响/未知source校验失败，不接受部分合法preview', async () => {
    for (const edit of [value => { value.action = 'rebuild'; }, value => { value.token = ''; }, value => { value.version = 'v2'; },
        value => { delete value.embeddingBatches; }, value => { value.collections.push(copy(value.collections[0])); }, value => { value.collections = []; },
        value => { value.sources = [{ id: 'unknown-source' }]; }, value => { value.collections.push({ id: 'readonly', pointCount: 0 }); }]) {
        const h = harness({ handle: (record, self) => { const value = self.defaultReply(record); if (record.url.endsWith('/preview')) edit(value); return value; } });
        await begin(h); assert.match(h.elements['reference-index-status'].textContent, /预览失败/); assert.equal(h.doc.getElementById('btn-confirm-reference-index'), null);
    }
});
test('未知点数null/-1可删除，不读取向量估数；其它负数拒绝', async () => {
    for (const pointCount of [null, -1, -2]) {
        const h = harness({ handle: (record, self) => { const value = self.defaultReply(record); if (record.url.endsWith('/preview')) value.collections[0].pointCount = pointCount; return value; } });
        await begin(h, 'ref-error'); assert.deepEqual(h.requests.at(-1).body.collectionIds, ['ref-error']);
        if (pointCount === -2) assert.match(h.elements['reference-index-status'].textContent, /预览失败/);
        else { assert.match(h.elements['reference-index-preview'].textContent, /未知 块/); await h.click('btn-confirm-reference-index'); await flush(); assert.match(h.confirmations[0], /未知 块/); }
    }
});

// 执行结果，忙碌与异步安全。
test('快速操作不凭HTTP200/字符串true/taskId假成功，缺逐项或无效状态不确认完成', async () => {
    for (const result of [{}, { completed: 'true' }, { completed: false }, { completed: false, taskId: '不可冒充删除' },
        { completed: true, results: [] }, { completed: true, results: [resultItem('ref/current', 'partial')] }]) {
        const h = harness(); h.operationResult = result; await begin(h); await h.click('btn-confirm-reference-index'); await flush();
        assert.equal(h.notifications.length, 0); assert.equal(h.timers.size, 0); assert.match(h.elements['reference-index-status'].textContent, /未确认.*完成/);
        assert.equal(h.doc.getElementById('btn-confirm-reference-index'), null); assert.equal(h.requests.filter(item => item.url.endsWith('/operations')).length, 1);
    }
});
test('批量partial/failed仍显示completed/failed/skipped逐项，不使用completed=true假报全部成功', async () => {
    for (const completed of [false, true]) {
        const h = harness(); h.operationResult = { completed, message: '服务端批量说明', results: [resultItem('story-1'), resultItem('world-1', 'failed', '共享世界写入失败'), resultItem('ref-old', 'skipped', '当前参考已去重')] };
        await h.api.refresh(); for (const id of ['story-1', 'world-1', 'ref-old']) await h.select(id);
        await h.click('btn-delete-reference-index'); await h.click('btn-confirm-reference-index'); await flush();
        const box = h.elements['reference-index-results']; assert.equal(box.classList.contains('hidden'), false); assert.equal(box.classList.contains('is-error'), true);
        assert.match(box.textContent, /未全部完成/); assert.match(box.textContent, /opaque_story_name \[story-1\] · 已完成/);
        assert.match(box.textContent, /world-1.*失败：共享世界写入失败/); assert.match(box.textContent, /ref-old.*跳过：当前参考已去重/);
        assert.equal(h.notifications.length, 1); assert.ok(h.notifications.every(message => message.startsWith('操作未全部完成：')));
        await h.api.refresh(); assert.match(box.textContent, /共享世界写入失败/);
        assert.equal(h.doc.getElementById('btn-confirm-reference-index'), null);
    }
});
test('busy禁止写但可筛选选择/只读分块及刷新，没有自动rebuild', async () => {
    const h = harness(); h.summary.busy = true; h.summary.busyReason = '已有索引任务'; await h.api.refresh();
    assert.match(h.elements['reference-index-summary'].textContent, /已有索引任务/); assert.equal(h.root.getAttribute('aria-busy'), 'true');
    await h.select('story-1'); assert.equal(h.button('btn-delete-reference-index').disabled, true);
    for (const item of h.elements['reference-index-sources'].querySelectorAll('button')) assert.equal(item.disabled, true);
    await h.preview('story-1'); await h.inspect('story-1'); assert.equal(h.requests.filter(item => item.method === 'POST').length, 0);
    h.summary.busy = false; await h.api.refresh(); assert.equal(h.button('btn-rebuild-reference-index').disabled, false);
});
test('请求中阻止重复预览/选择改变，允许只读；异步confirm锁定且token仅消费一次', async () => {
    const previewWait = deferred(), confirmWait = deferred();
    const h = harness({ handle: (record, self) => record.url.endsWith('/preview') ? previewWait.promise : self.defaultReply(record), confirm: () => confirmWait.promise });
    await h.api.refresh(); const first = h.preview('ref/current'); await flush();
    assert.equal(h.button('btn-rebuild-reference-index').disabled, true); await h.preview('ref/current');
    await h.inspect('ref-old'); assert.ok(h.requests.some(item => item.url.includes('/points?')));
    assert.equal(await h.api.refresh(), false); assert.equal(h.requests.filter(item => item.url.endsWith('/preview')).length, 1);
    const pending = h.requests.find(item => item.url.endsWith('/preview')); previewWait.resolve(h.defaultReply(pending)); await first;
    const execute = h.click('btn-confirm-reference-index'); await flush(); await h.click('btn-confirm-reference-index'); assert.equal(h.confirmations.length, 1);
    assert.equal(h.requests.filter(item => item.url.endsWith('/operations')).length, 0);
    confirmWait.resolve(true); await execute; await flush(); assert.equal(h.requests.filter(item => item.url.endsWith('/operations')).length, 1);
});
test('刷新剔除不存在/无权限选择、保留有效选择并丢弃旧version预览token', async () => {
    const h = harness(); await h.api.refresh(); for (const id of ['ref-old', 'story-1', 'world-1']) await h.select(id);
    await h.click('btn-delete-reference-index'); const oldExecute = h.button('btn-confirm-reference-index');
    h.summary.version = 'v2'; h.summary.collections = h.summary.collections.filter(item => item.id !== 'ref-old');
    h.summary.collections.find(item => item.id === 'story-1').canDelete = false; h.summary.collections.find(item => item.id === 'story-1').canRebuild = false;
    await h.api.refresh(); assert.match(h.elements['reference-index-summary'].textContent, /取消 2 个失效/); assert.match(h.elements['reference-index-selection'].textContent, /已选择 1 个/);
    assert.equal(h.row('world-1').querySelector('input').checked, true); assert.equal(h.doc.getElementById('btn-confirm-reference-index'), null);
    await oldExecute.click(); assert.equal(h.requests.some(item => item.url.endsWith('/operations')), false);
});
test('refresh合并状态读取；无效summary关闭全部写操作，无效owners与权限不能放行', async () => {
    const wait = deferred(); let first = true;
    const h = harness({ handle: (record, self) => { if (first && record.url === BASE) { first = false; return wait.promise; } return self.defaultReply(record); } });
    const a = h.api.refresh(), b = h.api.refresh(); assert.equal(a, b); await flush(); assert.equal(h.requests.length, 1); wait.resolve(copy(h.summary)); await a;
    for (const edit of [value => { value.version = null; }, value => { value.task = undefined; }, value => { delete value.collections[0].owners; }, value => { value.collections[0].canDelete = 'true'; }, value => { value.collections[0].owners = [{ storyDirName: 'book-a' }]; }]) {
        const invalid = harness(); edit(invalid.summary); await invalid.api.refresh(); assert.equal(invalid.button('btn-delete-reference-index').disabled, true);
        assert.match(invalid.elements['reference-index-status'].textContent, /读取资料索引失败/); assert.equal(invalid.notifications.length, 0);
    }
});
test('进入索引tab恢复queued/running任务，合并轮询且只读不提交operations', async () => {
    for (const taskStatus of ['queued', 'running']) {
        const h = harness(); h.summary.task = { id: 'task/初始', status: taskStatus, message: '原任务进行中', results: [] };
        await h.api.refresh(); await h.api.refresh(); assert.equal(h.timers.size, 1); assert.equal(h.button('btn-delete-reference-index').disabled, true);
        await h.tick(); assert.equal(h.requests.at(-1).url, BASE + '/tasks/task%2F%E5%88%9D%E5%A7%8B'); assert.equal(h.timers.size, 1);
        assert.equal(h.requests.some(item => item.method === 'POST'), false); assert.equal(h.notifications.length, 0);
    }
});
test('重建受理不算完成，queued→running→completed逐项全部完成后才成功', async () => {
    const states = ['queued', 'running', 'completed'];
    const h = harness({ handle: (record, self) => {
        if (!record.url.includes('/tasks/')) return self.defaultReply(record);
        const status = states.shift(); return { id: 'task-1', status, message: '任务状态说明', results: status === 'completed' ? [resultItem('world-1')] : [] };
    } });
    h.operationResult = { completed: false, taskId: 'task-1', message: '已排队', results: [] };
    await begin(h, 'world-1', 'rebuild'); await h.click('btn-confirm-reference-index'); assert.equal(h.notifications.length, 0);
    await h.tick(); await h.tick(); assert.equal(h.notifications.length, 0); await h.tick(); assert.equal(h.notifications.length, 1);
    assert.match(h.notifications[0], /任务已完成/); assert.match(h.elements['reference-index-task'].textContent, /world-1.*已完成/);
    assert.equal(h.timers.size, 0); assert.equal(h.requests.at(-1).url, BASE);
});
test('任务failed或completed含失败/跳过逐项均显示且不假成功，查询失败仍锁定并可刷新恢复', async () => {
    for (const terminal of ['failed', 'completed']) {
        let failRead = true;
        const h = harness({ handle: (record, self) => {
            if (record.url.includes('/tasks/')) {
                if (failRead) throw new Error('503 状态不可用');
                return { id: 'task-1', status: terminal, message: 'Embedding 配额不足', results: [resultItem('story-1'), resultItem('world-1', 'failed', '共享世界失败'), resultItem('ref-old', 'skipped', '来源缺失')] };
            }
            return self.defaultReply(record);
        } });
        h.summary.task = { id: 'task-1', status: 'running', message: '后台索引中', results: [] };
        await h.api.refresh(); await h.tick(); assert.match(h.elements['reference-index-task'].textContent, /状态查询失败.*503/);
        assert.equal(h.button('btn-delete-reference-index').disabled, true); assert.equal(h.notifications.length, 0); assert.equal(h.timers.size, 0);
        failRead = false; await h.api.refresh(); h.summary.task = null; await h.tick();
        assert.match(h.elements['reference-index-task'].textContent, /共享世界失败/); assert.match(h.elements['reference-index-task'].textContent, /跳过：来源缺失/);
        assert.match(h.elements['reference-index-status'].textContent, /未全部完成/); assert.ok(h.notifications.every(message => message.startsWith('索引任务未全部完成：')));
        assert.equal(h.timers.size, 0); assert.equal(h.row('world-1').querySelectorAll('button')[0].disabled, false);
    }
});
test('迟到旧任务响应不能覆盖新任务，刷新旧running快照不能倒退已确认终态', async () => {
    const old = deferred();
    const h = harness({ handle: (record, self) => record.url.endsWith('/tasks/task-a') ? old.promise : record.url.endsWith('/tasks/task-b')
        ? { id: 'task-b', status: 'completed', message: '新任务完成', results: [resultItem('story-1')] } : self.defaultReply(record) });
    h.summary.task = { id: 'task-a', status: 'running', message: '旧任务', results: [] }; await h.api.refresh();
    const first = h.tick(); await flush(); h.summary.task = { id: 'task-b', status: 'running', message: '新任务', results: [] }; await h.api.refresh();
    old.resolve({ id: 'task-a', status: 'failed', message: '旧错误不得显示', results: [resultItem('world-1', 'failed')] }); await first;
    assert.doesNotMatch(h.elements['reference-index-task'].textContent, /旧错误/); await h.tick();
    assert.match(h.elements['reference-index-task'].textContent, /新任务完成/); assert.equal(h.timers.size, 0);
    assert.equal(h.row('story-1').querySelectorAll('button')[0].disabled, false, '旧running快照不能重新锁定已完成任务');
});

// 只读分页保留既有编码与迟到响应保护。
test('分块仅逻辑ID+编码cursor+limit25，支持上一页及数值0；无点级POST', async () => {
    const h = harness({ handle: (record, self) => record.url.includes('/points?') ? { points: [{ id: 1, payload: { text: '本页正文' } }], nextCursor: record.url.includes('cursor=&') ? 'opaque/?&下一页' : null } : self.defaultReply(record) });
    await h.api.refresh(); await h.inspect('ref/current'); assert.equal(h.requests.at(-1).url, BASE + '/collections/ref%2Fcurrent/points?cursor=&limit=25');
    await h.click('btn-reference-points-next'); assert.equal(h.requests.at(-1).url, BASE + '/collections/ref%2Fcurrent/points?cursor=opaque%2F%3F%26%E4%B8%8B%E4%B8%80%E9%A1%B5&limit=25');
    assert.match(h.elements['reference-index-points-page'].textContent, /第 2 页/); await h.click('btn-reference-points-prev'); assert.match(h.elements['reference-index-points-page'].textContent, /第 1 页/);
    const zero = harness(); zero.pointsResult.nextCursor = 0; await zero.api.refresh(); await zero.inspect('ref/current');
    assert.equal(zero.button('btn-reference-points-next').disabled, false); zero.pointsResult.nextCursor = null; await zero.click('btn-reference-points-next'); assert.ok(zero.requests.at(-1).url.includes('cursor=0&limit=25'));
    assert.equal(h.requests.some(item => item.method === 'POST'), false);
});
test('切集合忽略迟到分块；分页失败保持原页；刷新删除集合清空辅助预览', async () => {
    const old = deferred(); let rejectNext = false;
    const h = harness({ handle: (record, self) => {
        if (record.url.includes('/points?')) {
            if (record.url.includes('ref%2Fcurrent')) return old.promise;
            if (rejectNext) throw new Error('分块读取失败');
            return { points: [{ id: 2, payload: { text: '新集合正文' } }], nextCursor: 'next' };
        }
        return self.defaultReply(record);
    } });
    await h.api.refresh(); const first = h.inspect('ref/current'); await flush(); await h.inspect('ref-old');
    old.resolve({ points: [{ id: 1, payload: { text: '旧集合不得显示' } }], nextCursor: null }); await first;
    assert.match(h.elements['reference-index-points'].textContent, /新集合正文/); assert.doesNotMatch(h.elements['reference-index-points'].textContent, /旧集合/);
    rejectNext = true; await h.click('btn-reference-points-next'); assert.match(h.elements['reference-index-points-status'].textContent, /读取分块失败/);
    assert.match(h.elements['reference-index-points-page'].textContent, /第 1 页/); h.summary.collections = h.summary.collections.filter(item => item.id !== 'ref-old');
    await h.api.refresh(); assert.equal(h.elements['reference-index-points-panel'].classList.contains('hidden'), true); assert.equal(h.elements['reference-index-points'].textContent, '');
});
