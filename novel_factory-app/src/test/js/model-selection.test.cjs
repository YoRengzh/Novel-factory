'use strict';

// 模型选择离线回归：执行真实模型设置区块；内存 DOM + 可控响应，不启动服务、不安装依赖。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const flush = () => new Promise(resolve => setImmediate(resolve));
const copy = value => JSON.parse(JSON.stringify(value));
const appPath = path.resolve(__dirname, '../../main/resources/static/app.js');
const MODELS = '/api/config/llm/models';
const CONFIG = '/api/config/llm';
const CHAT_MODELS = '/api/auth/chatgpt/models';

function modelBlock() {
    const source = fs.readFileSync(appPath, 'utf8');
    const start = source.indexOf('    // ===== 模型接入设置 =====');
    const end = source.indexOf('    // ===== 我的参考资料', start);
    assert.ok(start >= 0 && end > start, '模型设置区块边界必须存在');
    return source.slice(start, end);
}

// 只实现模型设置用到的 DOM：解析产品生成的行，保留父子关系、属性、事件冒泡和异步观察。
function memoryDom() {
    const observers = [];
    const escape = value => String(value).replace(/[&<>"']/g,
        char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
    const decode = value => value.replace(/&(#x[\da-f]+|#\d+|amp|lt|gt|quot|apos|nbsp);/gi, (_, entity) =>
        entity[0] === '#' ? String.fromCodePoint(entity[1].toLowerCase() === 'x'
            ? parseInt(entity.slice(2), 16) : Number(entity.slice(1)))
            : ({ amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' })[entity.toLowerCase()]);
    function changed(node) {
        observers.forEach(observer => {
            if (!observer.target || observer.queued || !(observer.target === node
                || observer.subtree && observer.target.contains(node))) return;
            observer.queued = true;
            queueMicrotask(() => { observer.queued = false; observer.callback([], observer); });
        });
    }
    class Event {
        constructor(type, options = {}) { Object.assign(this, { type, bubbles: false }, options); }
        preventDefault() { this.defaultPrevented = true; }
        stopPropagation() { this.stopped = true; }
    }
    class Element {
        constructor(tagName = 'div', text = '') {
            this.tagName = tagName.toUpperCase();
            this.nodeType = tagName === '#text' ? 3 : 1;
            this.childNodes = [];
            this.parentElement = null;
            this.attributes = {};
            this.style = {};
            this.listeners = {};
            this.checked = this.disabled = this.readOnly = this.selected = false;
            this._value = '';
            this._text = text;
            this.dataset = new Proxy({}, {
                get: (_, key) => this.getAttribute('data-' + String(key).replace(/[A-Z]/g, c => '-' + c.toLowerCase())) ?? undefined,
                set: (_, key, value) => { this.setAttribute('data-' + String(key).replace(/[A-Z]/g, c => '-' + c.toLowerCase()), value); return true; }
            });
            const classes = () => new Set(this.className.split(/\s+/).filter(Boolean));
            this.classList = {
                contains: key => classes().has(key),
                add: (...keys) => { const set = classes(); keys.forEach(key => set.add(key)); this.className = [...set].join(' '); },
                remove: (...keys) => { const set = classes(); keys.forEach(key => set.delete(key)); this.className = [...set].join(' '); },
                toggle: (key, force) => {
                    const enabled = force === undefined ? !classes().has(key) : !!force;
                    this.classList[enabled ? 'add' : 'remove'](key);
                    return enabled;
                }
            };
        }
        get children() { return this.childNodes.filter(child => child.nodeType === 1); }
        get firstChild() { return this.childNodes[0] || null; }
        get parentNode() { return this.parentElement; }
        get id() { return this.attributes.id || ''; }
        set id(value) { this.attributes.id = String(value); }
        get className() { return this.attributes.class || ''; }
        set className(value) { this.attributes.class = String(value); }
        get title() { return this.attributes.title || ''; }
        set title(value) { this.attributes.title = String(value); }
        get options() { return this.querySelectorAll('option'); }
        get value() {
            if (this.tagName === 'SELECT') return this.options.find(option => option.selected)?.value || '';
            if (this.tagName === 'OPTION') return this.attributes.value ?? this.textContent;
            return this._value;
        }
        set value(value) {
            value = String(value);
            if (this.tagName === 'SELECT') this.options.forEach(option => { option.selected = option.value === value; });
            else if (this.tagName === 'OPTION') this.attributes.value = value;
            else this._value = value;
        }
        get textContent() { return this.nodeType === 3 ? this._text : this.childNodes.map(child => child.textContent).join(''); }
        set textContent(value) {
            if (this.nodeType === 3) this._text = String(value);
            else this.replaceChildren(new Element('#text', String(value)));
        }
        get innerHTML() { return this.childNodes.map(child => child.outerHTML).join(''); }
        get outerHTML() {
            if (this.nodeType === 3) return escape(this.textContent);
            return '<' + this.tagName.toLowerCase() + Object.entries(this.attributes)
                .map(([key, value]) => ' ' + key + '="' + escape(value) + '"').join('') + '>'
                + this.innerHTML + '</' + this.tagName.toLowerCase() + '>';
        }
        set innerHTML(html) {
            this.replaceChildren();
            const stack = [this];
            for (const token of String(html).match(/<!--[\s\S]*?-->|<[^>]+>|[^<]+/g) || []) {
                if (token.startsWith('<!--')) continue;
                if (token.startsWith('</')) { stack.pop(); continue; }
                if (!token.startsWith('<')) { stack.at(-1).appendChild(new Element('#text', decode(token))); continue; }
                const tag = /^<([\w-]+)/.exec(token)[1];
                const child = new Element(tag);
                const attrs = token.slice(tag.length + 1, -1);
                for (const match of attrs.matchAll(/([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g)) {
                    child.setAttribute(match[1], decode(match[2] ?? match[3] ?? match[4] ?? ''));
                }
                stack.at(-1).appendChild(child);
                if (!/^(input|br|hr|img|meta|link)$/i.test(tag) && !token.endsWith('/>')) stack.push(child);
            }
        }
        setAttribute(key, value) {
            this.attributes[key] = String(value);
            if (key === 'value') this.value = value;
            if (['disabled', 'checked', 'selected'].includes(key)) this[key] = true;
            if (key === 'readonly') this.readOnly = true;
        }
        getAttribute(key) { return this.attributes[key] ?? null; }
        removeAttribute(key) { delete this.attributes[key]; if (['disabled', 'checked', 'selected'].includes(key)) this[key] = false; }
        appendChild(child) { return this.insertBefore(child, null); }
        append(...children) { children.forEach(child => this.appendChild(typeof child === 'string' ? new Element('#text', child) : child)); }
        insertBefore(child, before) {
            child.remove();
            child.parentElement = this;
            this.childNodes.splice(before ? this.childNodes.indexOf(before) : this.childNodes.length, 0, child);
            if (this.tagName === 'SELECT' && !this.options.some(option => option.selected)) this.options[0].selected = true;
            changed(this);
            return child;
        }
        removeChild(child) { this.childNodes.splice(this.childNodes.indexOf(child), 1); child.parentElement = null; changed(this); return child; }
        remove() { if (this.parentElement) this.parentElement.removeChild(this); }
        replaceChildren(...children) { [...this.childNodes].forEach(child => child.remove()); children.forEach(child => this.appendChild(child)); changed(this); }
        contains(node) { for (; node; node = node.parentElement) if (node === this) return true; return false; }
        matches(selector) {
            return selector.split(',').some(part => {
                const pieces = part.trim().split(/\s+/);
                const simple = (node, rule) => {
                    if (!node || node.nodeType !== 1) return false;
                    const tag = /^[\w-]+/.exec(rule)?.[0];
                    if (tag && node.tagName !== tag.toUpperCase()) return false;
                    if ([...rule.matchAll(/#([\w-]+)/g)].some(match => node.id !== match[1])) return false;
                    if ([...rule.matchAll(/\.([\w-]+)/g)].some(match => !node.classList.contains(match[1]))) return false;
                    return [...rule.matchAll(/\[([\w-]+)(?:=["']?([^\]"']*)["']?)?\]/g)].every(match =>
                        match[2] === undefined ? node.getAttribute(match[1]) !== null : node.getAttribute(match[1]) === match[2]);
                };
                let node = this;
                if (!simple(node, pieces.pop())) return false;
                while (pieces.length) {
                    const rule = pieces.pop();
                    do { node = node.parentElement; } while (node && !simple(node, rule));
                    if (!node) return false;
                }
                return true;
            });
        }
        closest(selector) { for (let node = this; node; node = node.parentElement) if (node.matches(selector)) return node; return null; }
        querySelectorAll(selector) { return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]); }
        querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
        addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
        removeEventListener(type, listener) { this.listeners[type] = (this.listeners[type] || []).filter(item => item !== listener); }
        dispatchEvent(event) {
            event.target ||= this;
            const route = [this];
            if (event.bubbles) for (let node = this.parentElement; node; node = node.parentElement) route.push(node);
            for (const node of route) {
                event.currentTarget = node;
                (node.listeners[event.type] || []).slice().forEach(listener => listener.call(node, event));
                if (typeof node['on' + event.type] === 'function') node['on' + event.type](event);
                if (event.stopped) break;
            }
            return !event.defaultPrevented;
        }
        emit(type) { this.dispatchEvent(new Event(type, { bubbles: type !== 'focus' })); }
        click() { if (!this.disabled) this.emit('click'); }
        focus() { if (!this.disabled) { document.activeElement = this; this.emit('focus'); } }
        getBoundingClientRect() { return { left: 10, top: 10, bottom: 40, width: 300, height: 30 }; }
        scrollIntoView() {}
    }
    const document = new Element('document');
    document.body = document.appendChild(new Element('body'));
    document.createElement = tag => new Element(tag);
    document.createTextNode = text => new Element('#text', text);
    document.getElementById = id => document.querySelector('#' + id);
    class MutationObserver {
        constructor(callback) { this.callback = callback; observers.push(this); }
        observe(target, options = {}) { this.target = target; this.subtree = options.subtree; }
        disconnect() { this.target = null; }
    }
    return { document, Element, Event, MutationObserver, escape };
}

function config() {
    return {
        configured: true, authMode: 'api-key', providerId: 'a', model: '', maxTokens: 8192, sceneApiEnabled: true,
        providers: ['a', 'b', 'c'].map(id => ({ id, name: id.toUpperCase() + '站',
            baseUrl: 'https://' + id + '.invalid/v1', protocol: 'openai', hasKey: true, models: ['shared', id + '-only'] })),
        scenes: [
            { key: 'outline', label: '大纲', staticModel: 'shared', overrideProviderId: 'b' },
            { key: 'chapter-content', label: '正文', staticModel: 'shared', overrideProviderId: 'c' },
            { key: 'summary', label: '摘要', staticModel: 'legacy', overrideHasKey: true }
        ],
        fallback: { overrideProviderId: 'c' },
        toggles: [{ key: 'review-enabled', label: '复核', effectiveValue: true }]
    };
}

function harness(data = config()) {
    const dom = memoryDom();
    const { document, Element } = dom;
    const groups = {
        form: 'llm-config-form',
        input: 'llm_baseUrl llm_apiKey llm_model llm_maxTokens provider_name provider_baseUrl provider_apiKey fallback_model fallback_maxTokens fallback_temperature fallback_baseUrl fallback_apiKey',
        select: 'llm_authMode llm_provider llm_protocol provider_protocol fallback_provider',
        button: 'btn-save-llm-config btn-reset-llm-config btn-chatgpt-login btn-chatgpt-logout btn-save-scene-config btn-reset-scene-config btn-save-provider btn-cancel-provider btn-test-provider btn-save-fallback btn-reset-fallback',
        div: 'llm-config-status chatgpt-auth-panel chatgpt-auth-status chatgpt-model-options scene-matrix scene-unified-note scene-config-status scene-api-hint llm-manual-panel provider-list provider-status feature-toggles fallback-manual-panel fallback-status'
    };
    for (const [tag, ids] of Object.entries(groups)) for (const id of ids.split(' ')) {
        const element = document.createElement(tag);
        element.id = id;
        document.body.appendChild(element);
    }
    for (const id of ['scene_api_enabled', 'llm_manual_toggle', 'fallback_manual_toggle']) {
        const checkbox = document.createElement('input');
        checkbox.id = id;
        checkbox.setAttribute('type', 'checkbox');
        document.body.appendChild(checkbox);
    }
    const el = id => { const node = document.getElementById(id); assert.ok(node, '缺少元素 ' + id); return node; };
    el('llm_authMode').innerHTML = '<option value="api-key">API Key</option><option value="chatgpt">ChatGPT</option>';
    for (const id of ['llm_protocol', 'provider_protocol']) el(id).innerHTML = '<option value="openai">OpenAI</option><option value="anthropic">Anthropic</option>';
    const requests = [], toasts = [], timers = new Map();
    let timerId = 0;
    const fetchJson = (url, options = {}) => {
        const request = { url, method: options.method || 'GET', body: options.body ? JSON.parse(options.body) : null };
        requests.push(request);
        if (url === '/api/auth/chatgpt/status') return Promise.resolve({ authorized: false });
        return new Promise((resolve, reject) => {
            request.resolve = value => { request.settled = true; resolve(copy(value)); };
            request.reject = error => { request.settled = true; reject(error); };
        });
    };
    const context = vm.createContext({
        document, Event: dom.Event, MutationObserver: dom.MutationObserver,
        window: Object.assign(new Element('window'), { innerHeight: 900, confirm: () => true }),
        fetchJson, fetch: () => { throw new Error('禁止真实网络请求'); },
        esc: dom.escape, showToast: message => toasts.push(message), renderEmbedding() {},
        setTimeout: callback => { timers.set(++timerId, callback); return timerId; },
        clearTimeout: id => timers.delete(id), queueMicrotask, console
    });
    vm.runInContext(modelBlock() + '\nglobalThis.modelTest = { renderProviders, renderLlmConfig, renderSceneMatrix, showModelDropdown, hideModelDropdown, fetchProviderModels, ensureProviderModels, loadChatGptModels, saveSceneOverrides, modelSourceOf, refreshModelDropdown, syncSceneApiControls };', context, { filename: appPath });
    const api = context.modelTest;
    api.renderLlmConfig(copy(data));
    const row = key => el('scene-matrix').querySelector('[data-scene-key="' + key + '"]');
    const field = (key, name = 'model') => { const node = row(key)?.querySelector('[data-field="' + name + '"]'); assert.ok(node, key + '/' + name); return node; };
    const dropdown = el('chatgpt-model-options');
    const options = () => dropdown.querySelectorAll('.model-option');
    return {
        api, el, row, field, dropdown, options, requests, toasts,
        values: () => options().map(option => option.dataset.value),
        visible: () => options().filter(option => option.style.display !== 'none').map(option => option.dataset.value),
        models: id => requests.filter(request => request.url === MODELS && request.body?.providerId === id),
        puts: () => requests.filter(request => request.url === CONFIG && request.method === 'PUT'),
        chatRequests: () => requests.filter(request => request.url === CHAT_MODELS),
        show: input => api.showModelDropdown(input),
        select: (input, model) => {
            api.showModelDropdown(input);
            const option = options().find(item => item.dataset.value === model);
            assert.ok(option, '模型应可选择：' + model);
            option.click();
            assert.equal(input.value, model);
            assert.equal(dropdown.classList.contains('visible'), false);
        },
        runTimers: () => { const pending = [...timers.values()]; timers.clear(); pending.forEach(callback => callback()); }
    };
}

function expectModels(h, models) { assert.deepEqual([...h.values()].sort(), [...models].sort()); }
function onlyModelRequests(h) {
    assert.ok(h.requests.every(request => request.url === MODELS && request.method === 'POST'),
        '获取模型只能 POST 模型端点，不得 GET 全配置或 PUT：' + JSON.stringify(h.requests));
}
function edit(input, value, type = 'input') { input.value = value; input.emit(type); }
function check(input, checked) { input.checked = checked; input.emit('change'); }
function draftState(h) {
    const ids = ['llm_provider', 'llm_authMode', 'llm_model', 'llm_maxTokens', 'llm_baseUrl', 'llm_apiKey', 'llm_protocol',
        'fallback_provider', 'fallback_model', 'fallback_maxTokens', 'fallback_temperature', 'fallback_baseUrl', 'fallback_apiKey'];
    return {
        fields: Object.fromEntries(ids.map(id => [id, h.el(id).value])),
        checks: ['scene_api_enabled', 'llm_manual_toggle', 'fallback_manual_toggle'].map(id => h.el(id).checked),
        feature: h.el('feature-toggles').querySelector('.toggle-input').checked,
        scenes: ['outline', 'chapter-content', 'summary'].map(key => ['model', 'providerId', 'maxTokens', 'temperature'].map(name => h.field(key, name).value))
    };
}
function prepareDrafts(h) {
    edit(h.el('llm_model'), 'global-draft');
    edit(h.el('llm_maxTokens'), '9999');
    edit(h.el('fallback_model'), 'fallback-draft');
    edit(h.el('fallback_maxTokens'), '1234');
    edit(h.el('fallback_temperature'), '0.2');
    edit(h.field('outline'), 'outline-draft');
    edit(h.field('outline', 'maxTokens'), '3456');
    edit(h.field('outline', 'temperature'), '0');
    edit(h.field('chapter-content'), 'content-draft');
    h.el('feature-toggles').querySelector('.toggle-input').checked = false;
    h.row('summary').querySelector('.scene-clear').click();
}

test('全局 A、场景 B/C 独立选模；切换别处的站不抢当前场景', async () => {
    const h = harness();
    await flush();
    for (const [input, id] of [[h.el('llm_model'), 'a'], [h.field('outline'), 'b'], [h.field('chapter-content'), 'c']]) {
        input.focus();
        expectModels(h, ['shared', id + '-only']);
    }
    h.show(h.field('outline'));
    edit(h.el('llm_provider'), 'c', 'change');
    expectModels(h, ['shared', 'b-only']);
    edit(h.field('outline', 'providerId'), 'c', 'change');
    expectModels(h, ['shared', 'c-only']);
    edit(h.field('outline', 'providerId'), '', 'change');
    edit(h.el('llm_provider'), 'a', 'change');
    expectModels(h, ['shared', 'a-only']);
    assert.equal(h.requests.length, 0);
});

test('同名模型限定站标该站；手动合并去重并标「多站可用」', async () => {
    const h = harness();
    await flush();
    for (const [input, name] of [[h.el('llm_model'), 'A站'], [h.field('outline'), 'B站'], [h.field('chapter-content'), 'C站']]) {
        h.show(input);
        const text = h.options().find(option => option.dataset.value === 'shared').textContent;
        assert.ok(text.includes(name));
        assert.doesNotMatch(text, /多站可用/);
        for (const other of ['A站', 'B站', 'C站'].filter(station => station !== name)) assert.ok(!text.includes(other));
    }
    check(h.el('llm_manual_toggle'), true);
    h.show(h.el('llm_model'));
    expectModels(h, ['shared', 'a-only', 'b-only', 'c-only']);
    assert.match(h.options().find(option => option.dataset.value === 'shared').textContent, /多站可用/);
    assert.match(h.options().find(option => option.dataset.value === 'b-only').textContent, /B站/);
    assert.equal(h.requests.length, 0);
});

test('无缓存先清空旧列表；模型响应不重拉配置、不保存、不丢各区草稿与清除意图', async () => {
    const data = config();
    delete data.providers[1].models;
    const h = harness(data);
    await flush();
    h.show(h.el('llm_model'));
    expectModels(h, ['shared', 'a-only']);
    h.show(h.field('outline'));
    expectModels(h, []);
    assert.match(h.dropdown.textContent, /加载|获取|读取/);
    assert.equal(h.models('b').length, 1);
    prepareDrafts(h);
    check(h.el('llm_manual_toggle'), true);
    edit(h.el('llm_baseUrl'), 'https://global-draft.invalid/v1');
    edit(h.el('llm_apiKey'), 'global-unsaved-key');
    edit(h.el('llm_protocol'), 'anthropic', 'change');
    check(h.el('fallback_manual_toggle'), true);
    edit(h.el('fallback_baseUrl'), 'https://fallback-draft.invalid/v1');
    edit(h.el('fallback_apiKey'), 'fallback-unsaved-key');
    // 模拟尚未持久化的开关视图；真实 change 自动保存另有独立用例。
    h.el('scene_api_enabled').checked = false;
    h.api.syncSceneApiControls();
    const before = draftState(h), originalRow = h.row('outline');
    h.models('b')[0].resolve(['shared', 'b-live']);
    await flush();
    assert.deepEqual(draftState(h), before);
    assert.equal(h.row('outline'), originalRow, '获取模型不得重建场景矩阵');
    onlyModelRequests(h);
    h.api.saveSceneOverrides();
    assert.deepEqual(h.puts()[0].body.scenes.summary, { apiKey: '' }, '显式恢复的隐藏密钥仍须清除');
    assert.deepEqual(h.puts()[0].body.scenes.outline, { model: 'outline-draft', maxTokens: 3456, temperature: 0, providerId: 'b' });
    assert.equal(h.puts()[0].body.sceneApiEnabled, false);
    h.puts()[0].resolve({ ...data, sceneApiEnabled: false });
    await flush();
});

test('同站请求去重、不同站并发；B 的迟到响应不抢当前 C 下拉和筛选', async () => {
    const data = config();
    delete data.providers[1].models;
    delete data.providers[2].models;
    const h = harness(data);
    await flush();
    h.show(h.field('outline'));
    h.api.ensureProviderModels('b');
    h.show(h.field('chapter-content'));
    h.api.ensureProviderModels('c');
    h.api.ensureProviderModels('b');
    h.api.fetchProviderModels('b');
    assert.equal(h.models('b').length, 1, '自动获取与手动获取必须共用同站在途请求');
    assert.equal(h.models('c').length, 1, 'C 无需等待 B 完成');
    h.models('c')[0].resolve(['shared', 'c-live']);
    await flush();
    expectModels(h, ['shared', 'c-live']);
    edit(h.field('chapter-content'), 'c-');
    h.models('b')[0].resolve(['shared', 'b-live']);
    await flush();
    expectModels(h, ['shared', 'c-live']);
    assert.deepEqual(h.visible(), ['c-live']);
    assert.equal(h.field('chapter-content').value, 'c-');
    h.show(h.field('outline'));
    expectModels(h, ['shared', 'b-live']);
    assert.equal(h.models('b').length, 1, '返回结果已进入 B 的本地缓存');
    onlyModelRequests(h);
});

for (const outcome of ['empty', 'error']) test((outcome === 'empty' ? '空模型列表' : '模型获取失败') + '有提示、可手输、不自动重试；按钮可手动重试', async () => {
    const data = config();
    delete data.providers[1].models;
    const h = harness(data);
    await flush();
    const input = h.field('outline');
    h.show(input);
    if (outcome === 'empty') h.models('b')[0].resolve([]);
    else h.models('b')[0].reject(new Error('503 测试模型服务不可用'));
    await flush();
    expectModels(h, []);
    assert.match(h.dropdown.textContent, outcome === 'empty' ? /暂无|没有|未.*模型|空|0\s*个/ : /失败|不可用|错误/);
    assert.equal(input.disabled, false);
    assert.equal(input.readOnly, false);
    for (let i = 0; i < 3; i++) {
        h.api.hideModelDropdown();
        input.focus();
        h.api.refreshModelDropdown();
        h.api.ensureProviderModels('b');
    }
    edit(input, 'manual-only');
    h.runTimers();
    await flush();
    assert.equal(h.models('b').length, 1, '空结果或失败后再次聚焦/刷新/输入不得循环请求');
    assert.equal(input.value, 'manual-only');
    const retry = h.dropdown.querySelectorAll('button, [role="button"]').find(button => /重试|重新获取|重新加载/.test(button.textContent));
    assert.ok(retry, '下拉应提供用户主动重试入口');
    retry.click();
    assert.equal(h.models('b').length, 2);
    h.models('b')[1].resolve(['b-live', 'manual-only']);
    await flush();
    expectModels(h, ['b-live', 'manual-only']);
    assert.equal(input.value, 'manual-only');
    assert.deepEqual(h.visible(), ['manual-only']);
    onlyModelRequests(h);
});

test('场景 API 关闭跟随全局、重开恢复场景草稿；自动保存响应不全量重绘', async () => {
    const data = config(), h = harness(data);
    await flush();
    prepareDrafts(h);
    const originalRow = h.row('outline');
    const originalToggle = h.el('feature-toggles').querySelector('.toggle-input');
    h.show(h.field('outline'));
    const toggle = h.el('scene_api_enabled');
    check(toggle, false);
    assert.deepEqual(h.puts().map(request => request.body), [{ sceneApiEnabled: false }]);
    assert.equal(toggle.disabled, true);
    assert.equal(h.field('outline', 'providerId').disabled, true);
    expectModels(h, ['shared', 'a-only']);
    edit(h.el('llm_model'), 'global-edited-during-save');
    edit(h.el('fallback_model'), 'fallback-edited-during-save');
    const before = draftState(h);
    h.puts()[0].resolve({ ...data, sceneApiEnabled: false });
    await flush();
    assert.deepEqual(draftState(h), before);
    assert.equal(h.row('outline'), originalRow);
    assert.equal(h.el('feature-toggles').querySelector('.toggle-input'), originalToggle);
    assert.equal(toggle.disabled, false);
    h.show(h.field('outline'));
    expectModels(h, ['shared', 'a-only']);
    check(toggle, true);
    assert.deepEqual(h.puts()[1].body, { sceneApiEnabled: true });
    h.puts()[1].resolve(data);
    await flush();
    before.checks[0] = true;
    assert.deepEqual(draftState(h), before);
    assert.equal(h.row('outline'), originalRow);
    assert.equal(h.field('outline', 'providerId').disabled, false);
    expectModels(h, ['shared', 'b-only']);
    assert.ok(h.requests.every(request => request.url === CONFIG && request.method === 'PUT'));
    h.api.saveSceneOverrides();
    assert.deepEqual(h.puts()[2].body.scenes.summary, { apiKey: '' });
    assert.equal(h.puts()[2].body.scenes.outline.providerId, 'b');
    assert.equal(h.puts()[2].body.scenes.outline.model, 'outline-draft');
    h.puts()[2].resolve(data);
    await flush();
});

for (const initiallyEnabled of [true, false]) test('场景 API 自动保存失败回滚开关、控件与模型来源（原状态 ' + initiallyEnabled + '）', async () => {
    const h = harness({ ...config(), sceneApiEnabled: initiallyEnabled });
    await flush();
    prepareDrafts(h);
    h.show(h.field('outline'));
    const before = draftState(h), originalRow = h.row('outline');
    check(h.el('scene_api_enabled'), !initiallyEnabled);
    assert.equal(h.puts().length, 1);
    h.puts()[0].reject(new Error('503 开关保存失败'));
    await flush();
    assert.deepEqual(draftState(h), before);
    assert.equal(h.row('outline'), originalRow);
    assert.equal(h.el('scene_api_enabled').disabled, false);
    assert.equal(h.field('outline', 'providerId').disabled, !initiallyEnabled);
    expectModels(h, ['shared', initiallyEnabled ? 'b-only' : 'a-only']);
    assert.match(h.el('scene-config-status').textContent, /失败/);
    assert.equal(h.requests.length, 1);
});

test('兜底显式站优先；兜底手动地址与历史场景手动地址不误用全局 A', async () => {
    const h = harness();
    await flush();
    h.show(h.el('fallback_model'));
    expectModels(h, ['shared', 'c-only']);
    edit(h.el('fallback_provider'), 'b', 'change');
    expectModels(h, ['shared', 'b-only']);
    check(h.el('fallback_manual_toggle'), true);
    edit(h.el('fallback_baseUrl'), 'https://fallback-manual.invalid/v1');
    h.show(h.el('fallback_model'));
    assert.equal(h.el('fallback_provider').value, '');
    expectModels(h, ['shared', 'a-only', 'b-only', 'c-only']);
    check(h.el('fallback_manual_toggle'), false);
    h.show(h.el('fallback_model'));
    expectModels(h, ['shared', 'a-only']);
    h.show(h.field('summary'));
    expectModels(h, ['shared', 'a-only', 'b-only', 'c-only']);
    assert.equal(h.requests.length, 0);
});

test('ChatGPT 独立缓存：迟到响应不污染站模型，站响应也不抢 ChatGPT 下拉', async () => {
    const h = harness();
    await flush();
    edit(h.el('llm_authMode'), 'chatgpt', 'change');
    h.api.loadChatGptModels();
    edit(h.el('llm_model'), 'typed-after-chat-request');
    edit(h.el('llm_authMode'), 'api-key', 'change');
    h.show(h.field('outline'));
    const chatCount = h.chatRequests().length;
    assert.ok(chatCount > 0);
    h.chatRequests().forEach(request => request.resolve([{ slug: 'chat-only' }, { slug: 'shared' }]));
    await flush();
    expectModels(h, ['shared', 'b-only']);
    assert.equal(h.el('llm_model').value, 'typed-after-chat-request');
    h.show(h.el('llm_model'));
    expectModels(h, ['shared', 'a-only']);
    h.api.fetchProviderModels('c');
    edit(h.el('llm_authMode'), 'chatgpt', 'change');
    h.show(h.el('llm_model'));
    expectModels(h, ['chat-only', 'shared']);
    assert.equal(h.chatRequests().length, chatCount, '重新聚焦应复用 ChatGPT 专属缓存');
    assert.doesNotMatch(h.options().find(option => option.dataset.value === 'shared').textContent, /A站|B站|C站|多站可用/);
    h.models('c')[0].resolve(['c-refreshed']);
    await flush();
    expectModels(h, ['chat-only', 'shared']);
    assert.equal(h.el('llm_model').value, 'typed-after-chat-request');
    edit(h.el('llm_authMode'), 'api-key', 'change');
    h.show(h.field('chapter-content'));
    expectModels(h, ['c-refreshed']);
    assert.ok(!h.requests.some(request => request.url === CONFIG));
});

test('停留 ChatGPT 模式时，异步模型列表也不覆盖用户已经输入的模型', async () => {
    const h = harness();
    await flush();
    edit(h.el('llm_authMode'), 'chatgpt', 'change');
    h.api.loadChatGptModels();
    h.show(h.el('llm_model'));
    edit(h.el('llm_model'), 'my-custom-chat-model');
    assert.ok(h.chatRequests().length > 0);
    h.chatRequests().forEach(request => request.resolve([{ slug: 'chat-default' }]));
    await flush();
    assert.equal(h.el('llm_model').value, 'my-custom-chat-model');
    expectModels(h, ['chat-default']);
    edit(h.el('llm_model'), '');
    h.select(h.el('llm_model'), 'chat-default');
    assert.equal(h.field('outline').value, '');
    assert.ok(!h.requests.some(request => request.url === CONFIG));
});

test('真实点击选模后 saveSceneOverrides 仍逐行提交正确 model/providerId 与数值', async () => {
    const data = config(), h = harness(data);
    await flush();
    h.select(h.el('llm_model'), 'a-only');
    h.select(h.field('outline'), 'b-only');
    h.select(h.field('chapter-content'), 'c-only');
    edit(h.field('outline', 'maxTokens'), '2048');
    edit(h.field('outline', 'temperature'), '0');
    edit(h.field('chapter-content', 'maxTokens'), '3072');
    edit(h.field('chapter-content', 'temperature'), '0.7');
    h.row('summary').querySelector('.scene-clear').click();
    assert.equal(h.requests.length, 0, '选模不是保存');
    h.api.saveSceneOverrides();
    assert.equal(h.puts().length, 1);
    assert.deepEqual(h.puts()[0].body, {
        sceneApiEnabled: true,
        scenes: {
            outline: { model: 'b-only', maxTokens: 2048, temperature: 0, providerId: 'b' },
            'chapter-content': { model: 'c-only', maxTokens: 3072, temperature: 0.7, providerId: 'c' },
            summary: { apiKey: '' }
        }
    });
    h.puts()[0].resolve(data);
    await flush();
    assert.equal(h.el('btn-save-scene-config').disabled, false);
    assert.match(h.el('scene-config-status').textContent, /已保存/);
});
