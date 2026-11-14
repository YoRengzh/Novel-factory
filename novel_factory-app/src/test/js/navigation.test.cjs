'use strict';

// 导航离线回归：执行 app.js 原函数及真实 BookWorkspace，DOM / HTTP / 选区均为替身。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const staticRoot = path.resolve(__dirname, '../../main/resources/static');
const app = fs.readFileSync(path.join(staticRoot, 'app.js'), 'utf8');
const html = fs.readFileSync(path.join(staticRoot, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(staticRoot, 'style.css'), 'utf8');
const bookCss = fs.readFileSync(path.join(staticRoot, 'book-workspace.css'), 'utf8');
const flush = async () => { for (let i = 0; i < 3; i++) await new Promise(resolve => setImmediate(resolve)); };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };

function between(start, end) {
    const a = app.indexOf(start), b = app.indexOf(end, a);
    assert.ok(a >= 0 && b > a, 'app 区块边界必须存在：' + start); return app.slice(a, b);
}
function functionSource(name) {
    const start = app.indexOf('    function ' + name + '(');
    const end = app.indexOf('\n    }', start) + 6;
    assert.ok(start >= 0 && end > start, '导航函数必须存在：' + name); return app.slice(start, end);
}

function bookRules(width = 1920) {
    const rules = [];
    function visit(text) {
        let cursor = 0;
        while (cursor < text.length) {
            const open = text.indexOf('{', cursor);
            if (open < 0) break;
            let close = open + 1, depth = 1;
            for (; close < text.length && depth; close++) {
                if (text[close] === '{') depth++;
                if (text[close] === '}') depth--;
            }
            assert.equal(depth, 0, '书内 CSS 规则必须闭合');
            const selector = text.slice(cursor, open).trim(), body = text.slice(open + 1, close - 1);
            if (selector.startsWith('@media')) {
                const max = /max-width:\s*(\d+)px/.exec(selector), min = /min-width:\s*(\d+)px/.exec(selector);
                if ((!max || width <= Number(max[1])) && (!min || width >= Number(min[1]))) visit(body);
            } else if (!selector.startsWith('@')) {
                const declarations = {};
                for (const part of body.split(';')) {
                    const match = /^\s*([\w-]+)\s*:\s*(.*?)\s*$/.exec(part);
                    if (!match) continue;
                    const [, name, value] = match; declarations[name] = value;
                    // shorthand 的实际边值也进入级联，避免把 margin:0/padding:0 误判成缺少 scoped 规则。
                    if (name === 'margin' || name === 'padding') {
                        const sides = value.split(/\s+(?![^()]*\))/), [top, right = top, bottom = top, left = right] = sides;
                        Object.assign(declarations, { [name + '-top']: top, [name + '-right']: right, [name + '-bottom']: bottom, [name + '-left']: left });
                    }
                }
                rules.push({ selectors: selector.split(',').map(value => value.trim().replace(/\s+/g, ' ')), declarations });
            }
            cursor = close;
        }
    }
    visit(bookCss.replace(/\/\*[\s\S]*?\*\//g, ''));
    return rules;
}
function bookRule(selectors, width = 1920) {
    selectors = Array.isArray(selectors) ? selectors : [selectors];
    return Object.assign({}, ...bookRules(width).filter(rule => rule.selectors.some(selector => selectors.includes(selector))).map(rule => rule.declarations));
}
function pixelLength(value, overrides = {}, width = 1920) {
    assert.equal(typeof value, 'string', '必须声明布局长度');
    const variables = { ...bookRule(':root', width), ...bookRule('.book-workspace', width), ...overrides };
    for (let i = 0; i < 16 && value.includes('var('); i++) value = value.replace(/var\((--[\w-]+)(?:,\s*([^()]+))?\)/g, (_, name, fallback) => variables[name] ?? fallback ?? 'unresolved');
    const expression = value.replace(/px\b/g, '').replace(/\bcalc\(/g, '(').replace(/\b(min|max)\(/g, 'Math.$1(');
    assert.match(expression, /^(?:\d+(?:\.\d+)?|Math\.(?:min|max)|[+\-*/(),\s])+$/, '布局长度仅执行像素四则运算及min/max，不执行任意CSS文本');
    const result = vm.runInNewContext('(' + expression + ')');
    assert.equal(typeof result, 'number'); assert.ok(Number.isFinite(result), '长度必须有限');
    return result;
}

function memoryDom() {
    const decode = value => value.replace(/&(#x[\da-f]+|#\d+|amp|lt|gt|quot|apos|nbsp);/gi, (_, code) => code[0] === '#'
        ? String.fromCodePoint(code[1].toLowerCase() === 'x' ? parseInt(code.slice(2), 16) : Number(code.slice(1)))
        : ({ amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' })[code.toLowerCase()]);
    class Element {
        constructor(tagName = 'div') {
            this.tagName = tagName.toUpperCase(); this.id = ''; this.attributes = {}; this.children = [];
            this.parentElement = null; this.className = ''; this.dataset = {};
            this.style = { setProperty(name, value) { this[name] = String(value); }, getPropertyValue(name) { return this[name] ?? ''; }, removeProperty(name) { const value = this[name] ?? ''; delete this[name]; return value; } };
            this._text = ''; this.listeners = {}; this.value = ''; this.disabled = false; this.checked = false; this.readOnly = false;
            this.rect = { left: 0, top: 0, width: 0, height: 0 }; this.scrollTop = 0; this.scrollLeft = 0;
            const classes = () => new Set(this.className.split(/\s+/).filter(Boolean));
            this.classList = {
                contains: key => classes().has(key),
                add: (...keys) => { const set = classes(); keys.forEach(key => set.add(key)); this.className = [...set].join(' '); },
                remove: (...keys) => { const set = classes(); keys.forEach(key => set.delete(key)); this.className = [...set].join(' '); },
                toggle: (key, force) => { const enabled = force === undefined ? !classes().has(key) : !!force; this.classList[enabled ? 'add' : 'remove'](key); return enabled; }
            };
        }
        get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
        set textContent(value) { this.replaceChildren(); this._text = String(value); }
        get innerText() { return this.textContent; }
        set innerText(value) { this.textContent = value; }
        get innerHTML() { return this._html || ''; }
        set innerHTML(value) { this.replaceChildren(); this._html = String(value); }
        appendChild(child) { this.children.push(child); child.parentElement = this; return child; }
        replaceChildren(...children) { this.children.forEach(child => { child.parentElement = null; }); this.children = []; this._text = ''; children.forEach(child => this.appendChild(child)); }
        setAttribute(key, value) {
            this.attributes[key] = String(value);
            if (key === 'id') this.id = String(value);
            if (key === 'class') this.className = String(value);
            if (key.startsWith('data-')) this.dataset[key.slice(5).replace(/-([a-z])/g, (_, char) => char.toUpperCase())] = String(value);
        }
        getAttribute(key) { return key === 'class' ? this.className : key === 'id' ? this.id || null : this.attributes[key] ?? null; }
        hasAttribute(key) { return this.getAttribute(key) !== null; }
        addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
        emit(type, event = {}) { return type === 'click' && this.disabled ? Promise.resolve() : Promise.all((this.listeners[type] || []).map(listener => listener({ target: this, preventDefault() {}, stopPropagation() {}, ...event }))); }
        contains(node) { for (; node; node = node.parentElement) if (node === this) return true; return false; }
        matches(selector) {
            const compound = (node, rule) => {
                const tag = /^[\w*-]+/.exec(rule);
                if (tag && tag[0] !== '*' && node.tagName.toLowerCase() !== tag[0].toLowerCase()) return false;
                let rest = rule.slice(tag ? tag[0].length : 0);
                while (rest) {
                    const item = /^(?:([.#])([\w-]+)|\[([\w-]+)(?:=["']?([^"'\]]*)["']?)?\])/.exec(rest);
                    if (!item) return false;
                    if (item[1] === '.' && !node.classList.contains(item[2])) return false;
                    if (item[1] === '#' && node.id !== item[2]) return false;
                    if (item[3] && (node.getAttribute(item[3]) === null || (item[4] !== undefined && node.getAttribute(item[3]) !== item[4]))) return false;
                    rest = rest.slice(item[0].length);
                }
                return true;
            };
            return selector.split(',').some(rule => {
                const parts = rule.trim().replace(/\s*>\s*/g, ' > ').split(/\s+/);
                let node = this;
                if (!compound(node, parts.pop())) return false;
                while (parts.length) {
                    const direct = parts.at(-1) === '>'; if (direct) parts.pop();
                    const ancestor = parts.pop(); node = node.parentElement;
                    if (direct) { if (!node || !compound(node, ancestor)) return false; }
                    else { while (node && !compound(node, ancestor)) node = node.parentElement; if (!node) return false; }
                }
                return true;
            });
        }
        querySelectorAll(selector) { return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]); }
        querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
        closest(selector) { for (let node = this; node; node = node.parentElement) if (node.matches(selector)) return node; return null; }
        getBoundingClientRect() { const { left, top, width, height } = this.rect; return { x: left, y: top, left, top, right: left + width, bottom: top + height, width, height }; }
        get clientWidth() { return this.rect.width; }
        get clientHeight() { return this.rect.height; }
        get offsetWidth() { return this.rect.width; }
        get offsetHeight() { return this.rect.height; }
        scrollIntoView() { this.scrolled = true; }
        focus() { this.focused = true; }
    }
    // 解析项目 HTML 的元素与父子层级；不合成子页签，以便真 wrapper/ID/ARIA 参与行为测试。
    const root = new Element('document'), stack = [root], elements = Object.create(null);
    const voidTags = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
    for (const token of html.replace(/<!--[\s\S]*?-->/g, '').matchAll(/<\/?([a-z][\w-]*)\b([^>]*)>|([^<]+)/gi)) {
        if (token[3] !== undefined) { stack.at(-1)._text += decode(token[3]); continue; }
        const name = token[1].toLowerCase();
        if (token[0].startsWith('</')) {
            const index = stack.findLastIndex(item => item.tagName.toLowerCase() === name);
            if (index > 0) stack.length = index;
            continue;
        }
        const item = new Element(name);
        for (const attr of token[2].matchAll(/([:\w-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g)) item.setAttribute(attr[1], decode(attr[2] ?? attr[3] ?? attr[4] ?? ''));
        item.disabled = item.hasAttribute('disabled'); item.checked = item.hasAttribute('checked'); item.readOnly = item.hasAttribute('readonly');
        if (item.hasAttribute('value')) item.value = item.getAttribute('value');
        stack.at(-1).appendChild(item);
        if (item.id) elements[item.id] = item;
        if (!voidTags.has(name) && !token[0].endsWith('/>')) stack.push(item);
    }
    const body = root.querySelector('body'), documentElement = root.querySelector('html'), tabs = body.querySelectorAll('.nav-tab');
    const doc = { body, documentElement, getElementById: id => elements[id] || root.querySelector('#' + id), createElement: tag => new Element(tag),
        querySelectorAll: selector => root.querySelectorAll(selector), querySelector: selector => root.querySelector(selector), addEventListener() {} };
    const setRect = (selector, rect) => { const item = doc.querySelector(selector); assert.ok(item, '矩形 fixture 元素必须存在：' + selector); item.rect = { ...item.rect, ...rect }; return item; };
    return { doc, body, elements, tabs, setRect };
}

function harness(overrides = {}) {
    const dom = memoryDom();
    const timers = [], frames = [], requests = [], confirmations = [], notifications = [], loads = [], windowEvents = {}, layoutObservers = [];
    class LayoutObserver {
        constructor(callback) { this.callback = callback; layoutObservers.push(this); }
        observe(target) { this.target = target; }
    }
    const selection = { rangeCount: 1, removeAllRanges() { selection.rangeCount = 0; selection.cleared++; }, cleared: 0 };
    const fakeWindow = { innerHeight: 900, innerWidth: 1280, scrollY: 0, pageYOffset: 0, getSelection: () => selection,
        confirm: message => { confirmations.push(message); return overrides.confirm !== false; },
        addEventListener(type, listener) { (windowEvents[type] ||= []).push(listener); },
        removeEventListener(type, listener) { windowEvents[type] = (windowEvents[type] || []).filter(item => item !== listener); },
        requestAnimationFrame(callback) { frames.push(callback); return frames.length; }, cancelAnimationFrame() {} };
    dom.setRect('#book-workspace', { left: 96, top: 74, width: 1088, height: 620 });
    dom.setRect('#btn-update', { left: 12, top: 846, width: 80, height: 36 });
    dom.doc.documentElement.rect = { left: 0, top: 0, width: fakeWindow.innerWidth, height: fakeWindow.innerHeight };
    const workspaceModule = { exports: {} };
    vm.runInNewContext(fs.readFileSync(path.join(staticRoot, 'book-workspace.js'), 'utf8'), { module: workspaceModule });
    fakeWindow.BookWorkspace = workspaceModule.exports;
    const context = vm.createContext({
        document: dom.doc, window: fakeWindow, requestAnimationFrame: fakeWindow.requestAnimationFrame, ResizeObserver: LayoutObserver,
        storyViewToken: 0, currentStoryDir: null,
        fetchJson: (url, options = {}) => {
            requests.push({ url, method: options.method || 'GET', body: options.body ? JSON.parse(options.body) : null });
            if (overrides.handle) return Promise.resolve(overrides.handle(url, options));
            if (url.endsWith('/chapters/2')) return Promise.resolve({ chapterNo: 2, title: '第二章', content: '新章正文' });
            if (url.endsWith('/chapters')) return Promise.resolve([]);
            // 维护读取故意返回无效：失败仅影响维护面板，仍可验证 canLeave 的 extraDirty 和忙碌机制。
            return Promise.resolve({});
        },
        setTimeout: callback => { timers.push(callback); return timers.length; }, clearTimeout() {},
        showToast: message => notifications.push(message), esc: value => String(value),
        loadStories: () => loads.push('stories'), loadLlmConfig: () => loads.push('llm'),
        loadUserAssets: () => loads.push('assets'), loadStoryOptions: () => loads.push('options'), loadMetrics: () => loads.push('metrics'),
        loadWorkbench: () => loads.push('workbench'), loadCheckpoints: () => loads.push('checkpoints'),
        referenceIndexManager: { refresh: () => loads.push('reference-refresh') }
    });
    const declarations = between('    // ===== 故事库 =====', '    // 就地编辑：');
    const segmentState = between('    // ===== 段落级编辑：', '    function hideSegmentToolbar()');
    const wire = between('    // ===== 书内工作区（作者维护与品质问题）=====', '    // ===== 版本通知');
    vm.runInContext(declarations + '\n' + segmentState + '\n'
        + ['focusAtEnd', 'enterChapterEditMode', 'syncChapterSaveButton', 'hideSegmentToolbar', 'resetSegmentSelection', 'maybeShowSegmentToolbar', 'openSegmentDialog', 'closeSegmentDialog', 'runSegmentEdit', 'loadChapter', 'loadChapters'].map(functionSource).join('\n')
        + '\n' + wire + '\n' + between('    // ===== Tab 切换 =====', '    // ===== 生成表单 =====')
        + '\nglobalThis.navigation = { closeBook, syncBookNavigation, clearBookTransientUi, isBookLibraryVisible, isBookChapterVisible, maybeShowSegmentToolbar, runSegmentEdit, loadChapter, loadChapters, workspace: bookWorkspace, setBook(dir, no, list) { currentStoryDir = dir; currentChapterNo = no; chapterSequence = list; }, dirty(value) { chapterHasUnsavedEdits = value; }, saving(value) { chapterSaving = value; bookWorkspace.sync(); }, editorFixture() { chapterHasUnsavedEdits = true; chapterFactsChanged = true; chapterSnapshotOnEnter = "原正文"; }, enterEdit() { enterChapterEditMode(false); }, select(value) { segmentSelection = value; }, segmentState() { return { selection: segmentSelection, result: segmentResult, request: segmentRequestToken }; }, state() { return { story: currentStoryDir, chapter: currentChapterNo, sequence: chapterSequence, dirty: chapterHasUnsavedEdits, facts: chapterFactsChanged, snapshot: chapterSnapshotOnEnter, token: storyViewToken }; } };', context);
    const h = { ...dom, api: context.navigation, window: fakeWindow, windowEvents, layoutObservers, timers, requests, confirmations, notifications, loads, selection };
    h.activate = name => { h.tabs.forEach(tab => tab.classList.toggle('active', tab.dataset.tab === name));
        for (const tabName of ['generate', 'library', 'reference-index', 'models', 'assets', 'settings']) h.elements['tab-' + tabName].classList.toggle('active', tabName === name); };
    h.open = async () => {
        h.activate('library'); h.api.setBook('book-a', 2, [1, 2, 3]); await h.api.workspace.open('book-a');
        h.elements['library-list'].classList.add('hidden'); h.elements['chapter-view'].classList.remove('hidden');
        h.elements['chapter-list'].classList.add('hidden'); h.api.syncBookNavigation();
    };
    h.editFixture = () => {
        h.elements['chapter-content-readonly'].textContent = '原正文'; h.elements['chapter-content-edit'].textContent = '未保存的章节草稿';
        h.elements['chapter-view'].classList.add('editing'); h.elements['chapter-content-readonly'].classList.add('hidden');
        h.elements['chapter-content-edit'].classList.remove('hidden'); h.elements['btn-edit'].classList.add('hidden');
        h.elements['btn-save'].classList.remove('hidden'); h.elements['btn-cancel-edit'].classList.remove('hidden'); h.api.editorFixture();
    };
    h.clickTab = name => { const tab = h.tabs.find(item => item.dataset.tab === name); assert.ok(tab, '全局页签必须来自真实 HTML：' + name); return tab.emit('click'); };
    h.emitWindow = async type => { for (const listener of windowEvents[type] || []) await listener({ type, target: fakeWindow }); while (frames.length) frames.shift()(); };
    return h;
}
function hidden(h, id, expected, message = id) { assert.equal(h.elements[id].classList.contains('hidden'), expected, message); }

test('小说库列表和长正文统一保留滚动条槽位，切全局页时恢复原布局', async () => {
    assert.equal(bookRule('html.book-library-active')['scrollbar-gutter'], 'stable');
    const h = harness();
    await h.clickTab('library');
    assert.equal(h.doc.documentElement.classList.contains('book-library-active'), true);
    await h.open();
    assert.equal(h.doc.documentElement.classList.contains('book-library-active'), true);
    h.api.closeBook();
    assert.equal(h.doc.documentElement.classList.contains('book-library-active'), true);
    await h.clickTab('settings');
    assert.equal(h.doc.documentElement.classList.contains('book-library-active'), false);
});

// 布局断言限定 book-workspace.css 与书内选择器；不再把旧全局 rail/大圆角当作契约。
test('返回48×30、两行目录48×44，间距16px；通知靠近底边', () => {
    const dom = memoryDom();
    for (const id of ['btn-back-stories', 'btn-back-chapters']) {
        const matches = dom.doc.querySelectorAll('#' + id);
        assert.equal(matches.length, 1, id + ' 必须唯一');
        const button = matches[0];
        assert.equal(button.tagName, 'BUTTON'); assert.equal(button.parentElement, dom.body, id + ' 不能留在书头或正文卡片内');
        for (const name of ['btn-back', 'book-return', 'hidden']) assert.ok(button.classList.contains(name), id + ' / ' + name);
        assert.equal(button.getAttribute('type'), 'button');
        assert.ok(html.indexOf('id="' + id + '"') > html.indexOf('</main>'), id + ' 必须在 main 外');
    }
    assert.ok(dom.elements['btn-back-chapters'].classList.contains('book-return-chapters'));
    assert.equal(dom.elements['btn-back-stories'].textContent.trim(), '返回');
    assert.equal(dom.elements['btn-back-stories'].getAttribute('aria-label'), '返回小说库');
    const chaptersButton = dom.elements['btn-back-chapters'];
    assert.deepEqual(chaptersButton.children.map(item => item.textContent.trim()), ['章节', '目录']);
    assert.equal(chaptersButton.getAttribute('aria-label'), '返回章节目录');
    assert.doesNotMatch(chaptersButton.textContent, /←|<-/);
    for (const width of [1920, 1280, 900, 760, 560, 390, 320]) {
        const rule = bookRule('.book-return', width), chapters = { ...rule, ...bookRule('.book-return-chapters', width) };
        assert.equal(rule.position, 'fixed'); assert.equal(pixelLength(rule.left), 0);
        assert.equal(pixelLength(rule.top), 54); assert.equal(pixelLength(chapters.top), 100);
        assert.equal(pixelLength(rule.width), 48); assert.equal(pixelLength(chapters.width), 48);
        assert.equal(pixelLength(rule.height), 30); assert.equal(pixelLength(chapters.height), 44);
        assert.equal(chapters['flex-direction'], 'column');
        assert.equal(pixelLength(rule['border-radius']), 4); assert.equal(rule.bottom, 'auto');
        assert.equal(pixelLength(chapters.top) - pixelLength(rule.top) - pixelLength(rule.height), 16, '两入口间隔16px');
        assert.equal(rule['white-space'], 'nowrap'); assert.equal(rule['flex-direction'], 'row');
        assert.equal(rule['writing-mode'], 'horizontal-tb');
    }
    const notice = /\.update-fab\s*\{([^}]+)\}/.exec(css)[1];
    assert.match(notice, /left:\s*var\(--left-rail-offset\)/); assert.match(notice, /bottom:\s*var\(--left-rail-bottom\)/);
    assert.match(notice, /height:\s*var\(--left-notice-height\)/);
    const desktop = /:root\s*\{([^}]+)\}/.exec(css)[1];
    const narrow = /@media \(max-width: 760px\) \{\s*:root\s*\{([^}]+)\}/.exec(css)[1];
    assert.match(desktop, /--left-rail-bottom:\s*16px/);
    assert.match(narrow, /--left-rail-bottom:\s*12px/);
});

test('书内共同外框不预留左右rail，header/card无gap；正文同宽且窄章导航最后规则仍有效', () => {
    for (const width of [1920, 1280, 900, 760, 560, 390, 320]) {
        const workspace = bookRule('.book-workspace', width), header = bookRule('.book-workspace-header', width);
        assert.match(workspace['padding-left'] ?? workspace.padding ?? '', /^0(?:px)?$/, width + 'px 不缩窄左边');
        assert.match(workspace['padding-right'] ?? workspace.padding ?? '', /^0(?:px)?$/, width + 'px 不缩窄右边');
        assert.match(header['margin-bottom'], /^0(?:px)?$/, width + 'px header/panel 紧贴');
        assert.match(bookRule('.book-panel > .content-card', width)['margin-top'], /^0(?:px)?$/);
        const chapter = bookRule('.book-workspace .chapter-text', width);
        assert.equal(chapter['max-width'], 'none'); assert.equal(chapter.width, '100%', width + 'px 正文不能保留旧900px上限');
        if (width <= 760) {
            const nav = bookRule('#chapter-navigation', width);
            assert.equal(nav.top, 'auto'); assert.equal(nav.bottom, '12px'); assert.equal(nav['flex-direction'], 'row');
            assert.equal(workspace['padding-bottom'], '0');
            assert.equal(bookRule('#book-panel-chapters', width)['padding-bottom'], '64px', '窄窗仅在正文页内为底部章节导航让位');
        }
    }
    assert.equal(bookRules().some(rule => rule.selectors.includes('.chapter-text')), false, '不能用非书内 scoped 规则改动其它正文');
});

test('书签7个完整标签在links wrapper内，单列同grid-area外侧sticky，浅V背景与窄gutter自身滚动', () => {
    const dom = memoryDom(), layout = dom.doc.querySelector('.book-authoring-layout'), nav = layout.querySelector('.book-subnav');
    const links = nav.querySelector('.book-subnav-links'), content = layout.querySelector('.book-authoring-content');
    assert.ok(links, '七按钮必须有自身滚动 wrapper');
    assert.deepEqual(layout.children, [nav, content], '书签仍是内容的同级元素，不能变成占宽的新列');
    assert.equal(links.parentElement, nav);
    const buttons = links.querySelectorAll('[data-authoring-tab]');
    assert.equal(dom.doc.querySelectorAll('[data-authoring-tab]').length, 7);
    assert.deepEqual(buttons.map(item => [item.dataset.authoringTab, item.textContent.trim()]), [
        ['settings', '故事设定'], ['characters', '人物档案'], ['items', '物品'], ['factions', '势力'],
        ['foreshadow', '伏笔'], ['issues', '待处理问题'], ['depth', '深度建议']
    ]);
    buttons.forEach(item => { assert.equal(item.getAttribute('role'), 'tab'); assert.equal(item.getAttribute('aria-controls'), 'book-authoring-' + item.dataset.authoringTab); });
    for (const width of [1920, 1280, 900, 760, 560, 390, 320]) {
        const grid = bookRule('.book-authoring-layout', width);
        const bookmark = bookRule(['.book-subnav', '.book-authoring-layout > .book-subnav'], width);
        const authoring = bookRule(['.book-authoring-content', '.book-authoring-layout > .book-authoring-content'], width);
        const scroll = bookRule('.book-subnav-links', width);
        const button = bookRule(['.book-subnav button', '.book-subnav-links button', '.book-subnav .book-subnav-links button'], width);
        assert.equal(grid.display, 'grid'); assert.match(grid['grid-template-columns'], /^(?:minmax\(0(?:px)?,\s*1fr\)|1fr)$/);
        assert.ok(bookmark['grid-area']); assert.equal(bookmark['grid-area'], authoring['grid-area'], width + 'px 两元素叠在同一grid-area');
        assert.equal(bookmark.position, 'sticky', width + 'px 不能退回正文内横排导航');
        assert.equal(pixelLength(bookmark.top), 152, '书签sticky避开54px页头与两行目录入口');
        for (const gutter of [0, 4, 8, 24, 96, 160, 211]) {
            const fixture = { '--book-page-gutter': gutter + 'px' };
            const bookmarkWidth = pixelLength(bookmark.width, fixture, width), offset = pixelLength(bookmark['margin-left'], fixture, width);
            assert.equal(bookmarkWidth, Math.min(146, Math.max(0, gutter - 6)), '宽度限定在实际外侧gutter');
            assert.equal(offset + bookmarkWidth, 0, '书签右边缘贴共同外框左边线，不能遮正文');
            assert.ok(gutter + offset >= 0, '窄gutter不会越过视口左边');
        }
        assert.match(scroll['overflow-y'] ?? scroll.overflow ?? '', /^(?:auto|scroll)$/);
        assert.match(scroll['max-height'], /--book-bookmark-bottom/, '滚动范围必须避开底部通知');
        assert.equal(button['white-space'] ?? scroll['white-space'] ?? bookmark['white-space'] ?? 'normal', 'normal', '默认或显式normal都可完整换行');
        assert.equal(button['overflow-wrap'], 'anywhere');
        assert.notEqual(button['text-overflow'], 'ellipsis'); assert.equal(button['-webkit-line-clamp'], undefined);
        for (const pseudo of ['::before', '::after']) {
            const tip = bookRule('.book-subnav' + pseudo, width);
            assert.match(tip.content, /^(?:""|'')$/); assert.equal(tip.position, 'absolute'); assert.equal(tip['z-index'], '-1', '浅V仅为按钮后面的背景');
            assert.match(tip['clip-path'], /^polygon\(/); assert.match(tip['clip-path'], /50%/); assert.ok(tip.background || tip['background-color']);
        }
        assert.equal(bookmark['clip-path'], undefined, '不得裁剪书签button焦点或完整标签');
    }
});

test('body返回目录只在library正文显示，目录与其它书内页隐藏；小说库入口覆盖所有书内页', async () => {
    const h = harness(); hidden(h, 'btn-back-stories', true); hidden(h, 'btn-back-chapters', true);
    await h.open();
    for (const id of ['btn-back-stories', 'btn-back-chapters', 'chapter-navigation']) hidden(h, id, false);
    for (const tab of ['overview', 'authoring', 'runs']) {
        h.api.workspace.selectTab(tab);
        hidden(h, 'btn-back-stories', false, tab + ' 仍可退出当前书');
        hidden(h, 'btn-back-chapters', true, tab + ' 不显示body目录入口'); hidden(h, 'chapter-navigation', true);
        assert.equal(h.api.state().chapter, 2); assert.deepEqual(Array.from(h.api.state().sequence), [1, 2, 3]);
    }
    h.api.workspace.selectTab('chapters'); hidden(h, 'btn-back-chapters', false); hidden(h, 'chapter-navigation', false);
    await h.elements['btn-back-chapters'].emit('click');
    hidden(h, 'btn-back-stories', false); hidden(h, 'btn-back-chapters', true); hidden(h, 'chapter-navigation', true);
    hidden(h, 'chapter-view', true); hidden(h, 'chapter-list', false);
    h.api.loadChapter('book-a', 2); await flush();
    hidden(h, 'btn-back-chapters', false); hidden(h, 'chapter-view', false); hidden(h, 'chapter-list', true);
    h.elements['book-workspace'].classList.add('hidden'); h.api.syncBookNavigation();
    for (const id of ['btn-back-stories', 'btn-back-chapters', 'chapter-navigation']) hidden(h, id, true, '隐藏整个书内外框 / ' + id);
});

test('其他全局tab隐藏两个body返回、章节nav和段落浮层；仅reference-index激活refresh', async () => {
    const h = harness(); await h.open();
    for (const name of ['generate', 'reference-index', 'models', 'assets', 'settings']) {
        h.api.select({ text: '旧片段', start: 0 });
        h.elements['segment-toolbar'].classList.remove('hidden'); h.elements['segment-dialog'].classList.remove('hidden');
        h.elements['segment-original'].textContent = '旧预览';
        await h.clickTab(name);
        for (const id of ['btn-back-stories', 'btn-back-chapters', 'chapter-navigation', 'segment-toolbar', 'segment-dialog']) hidden(h, id, true, name + ' / ' + id);
        assert.equal(h.api.segmentState().selection, null); assert.equal(h.elements['segment-original'].textContent, '');
        await h.clickTab('library'); hidden(h, 'btn-back-stories', false); hidden(h, 'btn-back-chapters', false);
    }
    assert.equal(h.loads.filter(value => value === 'reference-refresh').length, 1);
    assert.equal(h.requests.some(item => item.url.startsWith('/api/reference-index')), false);
});

test('原canLeave确认取消或busy时不closeBook，原书与选区保持；通过才关闭', async () => {
    const cancel = harness({ confirm: false }); await cancel.open(); cancel.api.dirty(true); cancel.api.select({ text: '未保存选区' });
    const token = cancel.api.state().token;
    await cancel.elements['btn-back-stories'].emit('click');
    assert.equal(cancel.confirmations.length, 1); assert.equal(cancel.api.state().story, 'book-a');
    assert.equal(cancel.api.state().token, token); assert.notEqual(cancel.api.segmentState().selection, null);
    hidden(cancel, 'btn-back-stories', false); hidden(cancel, 'btn-back-chapters', false);
    const busy = harness(); await busy.open(); busy.api.workspace.state.busy = true;
    await busy.elements['btn-back-stories'].emit('click');
    assert.equal(busy.api.state().story, 'book-a'); assert.match(busy.notifications[0], /请求中/); assert.equal(busy.confirmations.length, 0);
});

test('真实BookWorkspace.sync在维护、品质、章节保存busy时同时禁用两个body按钮及所有书内tabs', async () => {
    const h = harness(); await h.open();
    const controls = [...h.doc.querySelectorAll('[data-book-tab], [data-authoring-tab], [data-character-tab]'), h.elements['btn-back-stories'], h.elements['btn-back-chapters']];
    assert.equal(controls.length, 17, '4一级+7书签+4人物+2返回，不能漏掉新wrapper内按钮');
    for (const lock of ['busy', 'qualityBusy', 'chapterSaving']) {
        if (lock === 'chapterSaving') h.api.saving(true);
        else { h.api.workspace.state[lock] = true; h.api.workspace.sync(); }
        controls.forEach(item => assert.equal(item.disabled, true, lock + ' / ' + (item.id || item.dataset.authoringTab || item.dataset.characterTab)));
        assert.equal(h.elements['book-workspace'].getAttribute('aria-busy'), 'true');
        const token = h.api.state().token, authoring = h.api.workspace.state.authoringTab;
        await h.elements['btn-back-stories'].emit('click'); await h.elements['btn-back-chapters'].emit('click');
        await h.doc.querySelector('[data-authoring-tab="items"]').emit('click');
        assert.equal(h.api.state().story, 'book-a'); assert.equal(h.api.state().token, token);
        assert.equal(h.api.workspace.state.authoringTab, authoring); assert.equal(h.confirmations.length, 0);
        hidden(h, 'chapter-view', false); hidden(h, 'chapter-list', true);
        if (lock === 'chapterSaving') h.api.saving(false);
        else { h.api.workspace.state[lock] = false; h.api.workspace.sync(); }
        controls.forEach(item => assert.equal(item.disabled, false, lock + ' 结束后恢复 / ' + item.id));
        assert.equal(h.elements['book-workspace'].getAttribute('aria-busy'), 'false');
    }
});

test('目录按钮未保存章节确认取消时不清编辑器、选区或token', async () => {
    const h = harness({ confirm: false }); await h.open(); h.editFixture(); h.api.select({ text: '编辑中的选区' });
    const token = h.api.state().token;
    await h.elements['btn-back-chapters'].emit('click');
    assert.equal(h.confirmations.length, 1); assert.equal(h.api.state().story, 'book-a'); assert.equal(h.api.state().chapter, 2);
    assert.equal(h.api.state().token, token); assert.equal(h.api.state().dirty, true); assert.equal(h.api.state().facts, true);
    assert.equal(h.api.state().snapshot, '原正文'); assert.notEqual(h.api.segmentState().selection, null);
    assert.equal(h.elements['chapter-view'].classList.contains('editing'), true);
    assert.equal(h.elements['chapter-content-edit'].textContent, '未保存的章节草稿');
    hidden(h, 'chapter-content-edit', false); hidden(h, 'chapter-view', false); hidden(h, 'chapter-list', true); hidden(h, 'btn-back-chapters', false);
});

test('目录按钮确认丢弃章节编辑后返回目录并重置编辑器，但不退出书籍或清其它创作draft', async () => {
    const h = harness(); await h.open(); h.editFixture(); h.api.select({ text: '待清理选区' });
    const draft = { dirty: true, controls: [], values: { name: '保留的物品输入' } }; h.api.workspace.state.entryDrafts.ITEM = draft;
    const token = h.api.state().token;
    await h.elements['btn-back-chapters'].emit('click');
    assert.equal(h.confirmations.length, 1); assert.equal(h.api.state().story, 'book-a'); assert.equal(h.api.workspace.state.storyDir, 'book-a');
    assert.equal(h.api.state().token, token + 1); assert.equal(h.api.state().dirty, false); assert.equal(h.api.state().facts, false); assert.equal(h.api.state().snapshot, null);
    assert.equal(h.api.segmentState().selection, null); assert.equal(h.elements['chapter-view'].classList.contains('editing'), false);
    for (const id of ['chapter-view', 'chapter-content-edit', 'btn-save', 'btn-cancel-edit', 'btn-back-chapters', 'chapter-navigation']) hidden(h, id, true);
    for (const id of ['chapter-list', 'chapter-content-readonly', 'btn-edit', 'btn-back-stories']) hidden(h, id, false);
    assert.equal(h.elements['chapter-content-readonly'].textContent, '原正文');
    assert.equal(h.api.workspace.state.entryDrafts.ITEM, draft); assert.equal(h.api.workspace.isDirty(), true);
    h.api.loadChapter('book-a', 2); await flush(); h.api.enterEdit();
    assert.equal(h.elements['chapter-content-edit'].textContent, '新章正文', '再进编辑不能采用已丢弃章节草稿');
});

test('只有创作draft时目录无需章节确认，小说库退出仍走原canLeave', async () => {
    const h = harness({ confirm: false }); await h.open();
    const draft = { dirty: true, controls: [] }; h.api.workspace.state.entryDrafts.ITEM = draft; h.api.workspace.sync();
    await h.elements['btn-back-chapters'].emit('click');
    assert.equal(h.confirmations.length, 0); hidden(h, 'chapter-list', false); assert.equal(h.api.state().story, 'book-a');
    assert.equal(h.api.workspace.state.entryDrafts.ITEM, draft); assert.equal(h.api.workspace.isDirty(), true);
    await h.elements['btn-back-stories'].emit('click');
    assert.equal(h.confirmations.length, 1); assert.equal(h.api.state().story, 'book-a'); assert.equal(h.api.workspace.state.entryDrafts.ITEM, draft);
});

test('7个真实书签click保持互斥与ARIA，跨一级/全局页不重置人物、预览、独立draft及章节state', async () => {
    const h = harness(); await h.open(); h.api.workspace.selectTab('authoring'); h.api.workspace.selectCharacterTab('state');
    const state = h.api.workspace.state, settings = state.settings, draft = { dirty: true, controls: [], values: { name: '独立草稿' } };
    const preview = { scope: 'entry:ITEM', version: state.version };
    state.entryDrafts.ITEM = draft; state.preview = preview; state.selectedCharacter = '离线人物'; state.issuesRead = true;
    const epoch = state.epoch, version = state.version, storyToken = h.api.state().token;
    const generateInput = h.doc.querySelector('#tab-generate input'); assert.ok(generateInput); generateInput.value = '生成表单独立输入';
    const buttons = h.doc.querySelectorAll('.book-subnav-links [data-authoring-tab]'); assert.equal(buttons.length, 7);
    const names = buttons.map(item => item.dataset.authoringTab);
    for (const selected of buttons) {
        await selected.emit('click');
        assert.equal(state.authoringTab, selected.dataset.authoringTab); assert.equal(state.tab, 'authoring');
        for (const item of buttons) {
            const active = item === selected;
            assert.equal(item.classList.contains('active'), active); assert.equal(item.getAttribute('aria-selected'), String(active));
            hidden(h, 'book-authoring-' + item.dataset.authoringTab, !active);
        }
        hidden(h, 'book-authoring-preview', selected.dataset.authoringTab !== 'items', '保持原ITEM预览分组机制');
        assert.equal(state.characterTab, 'state'); assert.equal(state.selectedCharacter, '离线人物');
        assert.equal(state.settings, settings); assert.equal(state.entryDrafts.ITEM, draft); assert.equal(state.preview, preview);
        assert.equal(state.epoch, epoch); assert.equal(state.version, version); assert.equal(h.api.state().token, storyToken);
        assert.equal(h.api.state().chapter, 2); assert.deepEqual(Array.from(h.api.state().sequence), [1, 2, 3]);
        hidden(h, 'btn-back-stories', false); hidden(h, 'btn-back-chapters', true);
    }
    for (const tab of ['overview', 'runs', 'chapters', 'authoring']) {
        assert.equal(h.api.workspace.selectTab(tab), true); assert.equal(state.authoringTab, names.at(-1));
        assert.equal(state.entryDrafts.ITEM, draft); assert.equal(state.preview, preview); assert.equal(state.characterTab, 'state');
        h.doc.querySelectorAll('[data-book-tab]').forEach(item => assert.equal(item.getAttribute('aria-selected'), String(item.dataset.bookTab === tab)));
    }
    await h.clickTab('settings'); await h.clickTab('library');
    assert.equal(state.tab, 'authoring'); assert.equal(state.authoringTab, 'depth'); assert.equal(state.entryDrafts.ITEM, draft);
    assert.equal(state.settings, settings); assert.equal(state.preview, preview); assert.equal(generateInput.value, '生成表单独立输入');
    assert.equal(h.doc.querySelectorAll('[data-authoring-tab]').length, 7); assert.equal(h.doc.querySelectorAll('[data-book-tab]').length, 4);
    assert.equal(h.doc.querySelectorAll('[data-character-tab]').length, 4); assert.equal(h.tabs.length, 6);
});

test('未知书签或busy中的子页切换被拒绝，不误改全局/一级/人物页state', async () => {
    const h = harness(); await h.open(); h.api.workspace.selectTab('authoring');
    const state = h.api.workspace.state;
    for (const invalid of ['library', 'overview', 'basic', '', 'unknown']) assert.equal(h.api.workspace.selectAuthoringTab(invalid), false);
    assert.equal(state.authoringTab, 'settings'); assert.equal(state.tab, 'authoring'); assert.equal(state.characterTab, 'basic');
    state.busy = true; h.api.workspace.sync();
    assert.equal(h.api.workspace.selectAuthoringTab('characters'), false);
    await h.doc.querySelector('[data-authoring-tab="depth"]').emit('click');
    assert.equal(state.authoringTab, 'settings'); assert.equal(state.tab, 'authoring'); assert.equal(state.characterTab, 'basic');
    assert.equal(h.tabs.find(item => item.classList.contains('active')).dataset.tab, 'library');
    state.busy = false; h.api.workspace.sync(); await h.doc.querySelector('[data-authoring-tab="characters"]').emit('click');
    assert.equal(state.authoringTab, 'characters'); assert.equal(state.tab, 'authoring'); assert.equal(state.characterTab, 'basic');
});

test('容器尺寸变化也重算书签空隙，不依赖宿主必须转发window.resize', async () => {
    const h = harness(); await h.open();
    assert.equal(h.layoutObservers.length, 1);
    assert.equal(h.layoutObservers[0].target, h.doc.querySelector('.app-main'));
    h.setRect('#book-workspace', { left: 176.5 });
    h.layoutObservers[0].callback();
    assert.equal(h.elements['book-workspace'].style.getPropertyValue('--book-page-gutter'), '176.5px');
    await h.clickTab('settings');
    h.setRect('#book-workspace', { left: 0 });
    h.layoutObservers[0].callback();
    assert.equal(h.elements['book-workspace'].style.getPropertyValue('--book-page-gutter'), '176.5px');
});

test('真实syncBookNavigation/resize按workspace实际x更新gutter，退出和隐藏全局页hook安全', async () => {
    const h = harness(); await h.open();
    assert.ok(h.windowEvents.resize?.length, '必须接线window.resize重算布局');
    for (const left of [8, 24, 96, 160, 211]) {
        h.setRect('#book-workspace', { left }); h.api.syncBookNavigation();
        assert.equal(h.elements['book-workspace'].style.getPropertyValue('--book-page-gutter'), left + 'px', '不能从同一1280px视口猜margin');
    }
    h.setRect('#book-workspace', { left: 33 }); h.window.innerWidth = 390; h.doc.documentElement.rect.width = 390;
    await h.emitWindow('resize');
    assert.equal(h.elements['book-workspace'].style.getPropertyValue('--book-page-gutter'), '33px', '窄视口仍以rectfixture为准');
    await h.clickTab('models'); await h.emitWindow('resize');
    hidden(h, 'btn-back-stories', true); hidden(h, 'btn-back-chapters', true);
    h.api.closeBook(); h.setRect('#book-workspace', { left: 0, width: 0, height: 0 }); await h.emitWindow('resize');
    hidden(h, 'btn-back-stories', true); hidden(h, 'btn-back-chapters', true); hidden(h, 'book-workspace', true);
    assert.equal(h.api.state().story, null); assert.equal(h.requests.some(item => item.method !== 'GET'), false);
});

test('closeBook清除选区、预览、双返回与旧序列，失效所有读取；保持原closeBook/open机制', async () => {
    const h = harness(); await h.open(); h.api.dirty(true); h.api.select({ text: '旧片段' });
    for (const id of ['segment-toolbar', 'segment-dialog', 'chapter-issue-context', 'save-result']) h.elements[id].classList.remove('hidden');
    for (const id of ['segment-original', 'segment-edited', 'segment-note']) h.elements[id].textContent = '旧结果';
    h.elements['chapter-view'].classList.add('editing');
    const token = h.api.state().token;
    await h.elements['btn-back-stories'].emit('click');
    assert.equal(h.confirmations.length, 1); assert.equal(h.api.state().story, null); assert.equal(h.api.state().chapter, null);
    assert.equal(h.api.state().sequence.length, 0); assert.equal(h.api.state().dirty, false); assert.equal(h.api.state().token, token + 1);
    assert.equal(h.api.workspace.state.storyDir, ''); assert.equal(h.api.segmentState().selection, null); assert.equal(h.api.segmentState().result, null);
    for (const id of ['btn-back-stories', 'btn-back-chapters', 'chapter-navigation', 'btn-prev-chapter', 'btn-next-chapter', 'segment-toolbar', 'segment-dialog', 'chapter-issue-context', 'save-result', 'book-workspace']) hidden(h, id, true, id);
    for (const id of ['segment-original', 'segment-edited', 'segment-note']) assert.equal(h.elements[id].textContent, '');
    hidden(h, 'library-list', false); assert.equal(h.elements['chapter-view'].classList.contains('editing'), false);
    assert.ok(h.selection.cleared > 0); assert.equal(h.selection.rangeCount, 0);
});

test('退出后的异步段落成功/失败不得重新打开全局弹层或显示别书结果', async () => {
    for (const leave of ['close', 'tab', 'book-tab']) {
        const wait = deferred();
        const h = harness({ handle: url => url.endsWith('/segment-edit') ? wait.promise : {} });
        await h.open(); h.api.select({ text: '足够长的旧章节片段', before: '', after: '', start: 0, end: 10 });
        h.api.runSegmentEdit('polish'); await flush();
        hidden(h, 'segment-dialog', false);
        if (leave === 'close') h.api.closeBook();
        if (leave === 'tab') await h.clickTab('settings');
        if (leave === 'book-tab') h.api.workspace.selectTab('overview');
        wait.resolve({ editedText: '旧请求回来的危险正文', action: 'polish' }); await flush();
        hidden(h, 'segment-dialog', true, leave); hidden(h, 'btn-back-chapters', true, leave);
        assert.equal(h.api.segmentState().result, null); assert.equal(h.elements['segment-edited'].textContent, '');
    }
});

test('其它全局tab的迟到章节读取不显示body返回/nav也不滚动隐藏正文；退出后迟到目录失效', async () => {
    const chapterWait = deferred(), chaptersWait = deferred();
    const h = harness({ handle: url => url.endsWith('/chapters/2') ? chapterWait.promise : url.endsWith('/chapters') ? chaptersWait.promise : {} });
    await h.open(); h.api.loadChapter('book-a', 2); await h.clickTab('models');
    chapterWait.resolve({ chapterNo: 2, title: '第二章', content: '新正文' }); await flush();
    hidden(h, 'btn-back-stories', true); hidden(h, 'btn-back-chapters', true);
    hidden(h, 'chapter-navigation', true); assert.equal(h.elements['chapter-view-title'].scrolled, undefined);
    await h.clickTab('library'); h.api.loadChapters('book-a'); h.api.closeBook();
    chaptersWait.resolve([{ chapterNo: 2, title: '旧目录' }]); await flush();
    assert.equal(h.api.state().story, null); hidden(h, 'book-workspace', true); hidden(h, 'library-list', false);
    hidden(h, 'btn-back-stories', true); hidden(h, 'btn-back-chapters', true);
});

test('非library与非正文选区事件不弹段落工具条；返回目录清理body目录入口与选区导航', async () => {
    const h = harness(); await h.open();
    h.api.select({ text: '选中的旧正文' }); await h.elements['btn-back-chapters'].emit('click');
    assert.equal(h.api.segmentState().selection, null); hidden(h, 'chapter-navigation', true); hidden(h, 'btn-back-chapters', true);
    hidden(h, 'chapter-view', true); hidden(h, 'chapter-list', false); hidden(h, 'btn-back-stories', false);
    await h.clickTab('settings'); h.api.select({ text: '再造残留选区' }); h.api.maybeShowSegmentToolbar();
    assert.equal(h.api.segmentState().selection, null); hidden(h, 'segment-toolbar', true);
    await h.clickTab('library'); h.api.workspace.selectTab('overview'); h.api.select({ text: '非正文的选区' }); h.api.maybeShowSegmentToolbar();
    assert.equal(h.api.segmentState().selection, null); hidden(h, 'segment-toolbar', true); hidden(h, 'btn-back-chapters', true);
});
