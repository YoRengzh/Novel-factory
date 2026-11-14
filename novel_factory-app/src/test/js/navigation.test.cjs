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

function memoryDom() {
    class Element {
        constructor(tagName = 'div') {
            this.tagName = tagName.toUpperCase(); this.id = ''; this.attributes = {}; this.children = [];
            this.parentElement = null; this.className = ''; this.dataset = {}; this.style = {};
            this._text = ''; this.listeners = {}; this.value = ''; this.disabled = false; this.checked = false; this.readOnly = false;
            const classes = () => new Set(this.className.split(/\s+/).filter(Boolean));
            this.classList = {
                contains: key => classes().has(key),
                add: (...keys) => { const set = classes(); keys.forEach(key => set.add(key)); this.className = [...set].join(' '); },
                remove: (...keys) => { const set = classes(); keys.forEach(key => set.delete(key)); this.className = [...set].join(' '); },
                toggle: (key, force) => { const enabled = force === undefined ? !classes().has(key) : !!force; this.classList[enabled ? 'add' : 'remove'](key); return enabled; }
            };
        }
        get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
        set textContent(value) { this.children = []; this._text = String(value); }
        get innerText() { return this.textContent; }
        set innerText(value) { this.textContent = value; }
        get innerHTML() { return this._html || ''; }
        set innerHTML(value) { this.children = []; this._html = String(value); }
        appendChild(child) { this.children.push(child); child.parentElement = this; return child; }
        replaceChildren(...children) { this.children.forEach(child => { child.parentElement = null; }); this.children = []; this._text = ''; children.forEach(child => this.appendChild(child)); }
        setAttribute(key, value) { this.attributes[key] = String(value); }
        getAttribute(key) { return this.attributes[key] ?? null; }
        addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
        emit(type) { return this.disabled ? Promise.resolve() : Promise.all((this.listeners[type] || []).map(listener => listener({ target: this, preventDefault() {} }))); }
        contains(node) { for (; node; node = node.parentElement) if (node === this) return true; return false; }
        matches(selector) {
            return selector.split(',').some(rule => {
                rule = rule.trim();
                if (/^[\w-]+$/.test(rule)) return this.tagName.toLowerCase() === rule;
                if (rule[0] === '.') return this.classList.contains(rule.slice(1));
                if (rule[0] === '#') return this.id === rule.slice(1);
                const attr = /^(?:([\w-]+))?\[([\w-]+)(?:="([^"]*)")?\]$/.exec(rule);
                return attr ? (!attr[1] || this.tagName.toLowerCase() === attr[1]) && this.getAttribute(attr[2]) !== null
                    && (attr[3] === undefined || this.getAttribute(attr[2]) === attr[3]) : false;
            });
        }
        querySelectorAll(selector) { return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]); }
        querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
        scrollIntoView() { this.scrolled = true; }
    }
    const body = new Element('body'), elements = {}, tabs = [];
    for (const tag of html.matchAll(/<([\w-]+)\b([^>]*\bid="[^"]+"[^>]*)>/g)) {
        const item = new Element(tag[1]);
        for (const attr of tag[2].matchAll(/([\w-]+)="([^"]*)"/g)) {
            item.setAttribute(attr[1], attr[2]);
            if (attr[1] === 'id') item.id = attr[2];
            if (attr[1] === 'class') item.className = attr[2];
            if (attr[1].startsWith('data-')) item.dataset[attr[1].slice(5).replace(/-([a-z])/g, (_, char) => char.toUpperCase())] = attr[2];
        }
        elements[item.id] = item; body.appendChild(item);
    }
    const modePlaceholder = new Element('option'); modePlaceholder.setAttribute('value', '');
    elements['book-character-depth-mode'].appendChild(modePlaceholder);
    for (const [attribute, datasetKey, names] of [['book-tab', 'bookTab', ['overview', 'chapters', 'authoring', 'runs']],
        ['authoring-tab', 'authoringTab', ['settings', 'characters', 'items', 'factions', 'foreshadow', 'issues', 'depth']],
        ['character-tab', 'characterTab', ['basic', 'motivation', 'state', 'history']]]) {
        for (const name of names) {
            const item = attribute === 'book-tab' ? elements['book-tab-' + name] : new Element('button');
            item.dataset[datasetKey] = name; item.setAttribute('data-' + attribute, name);
            if (attribute !== 'book-tab') body.appendChild(item);
        }
    }
    for (const name of ['generate', 'library', 'reference-index', 'models', 'assets', 'settings']) {
        const item = new Element('button'); item.className = 'nav-tab' + (name === 'generate' ? ' active' : ''); item.dataset.tab = name;
        tabs.push(item); body.appendChild(item);
    }
    const doc = { body, getElementById: id => elements[id] || body.querySelectorAll('#' + id)[0], createElement: tag => new Element(tag),
        querySelectorAll: selector => body.querySelectorAll(selector), addEventListener() {} };
    return { doc, body, elements, tabs };
}

function harness(overrides = {}) {
    const dom = memoryDom();
    const timers = [], requests = [], confirmations = [], notifications = [], loads = [];
    let selection = { rangeCount: 1, removeAllRanges() { selection.rangeCount = 0; selection.cleared++; }, cleared: 0 };
    const workspaceModule = { exports: {} };
    vm.runInNewContext(fs.readFileSync(path.join(staticRoot, 'book-workspace.js'), 'utf8'), { module: workspaceModule });
    const context = vm.createContext({
        document: dom.doc, window: { BookWorkspace: workspaceModule.exports, getSelection: () => selection,
            confirm: message => { confirmations.push(message); return overrides.confirm !== false; } },
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
        + ['hideSegmentToolbar', 'resetSegmentSelection', 'maybeShowSegmentToolbar', 'openSegmentDialog', 'closeSegmentDialog', 'runSegmentEdit', 'loadChapter', 'loadChapters'].map(functionSource).join('\n')
        + '\n' + wire + '\n' + between('    // ===== Tab 切换 =====', '    // ===== 生成表单 =====')
        + '\nglobalThis.navigation = { closeBook, syncBookNavigation, clearBookTransientUi, isBookLibraryVisible, isBookChapterVisible, maybeShowSegmentToolbar, runSegmentEdit, loadChapter, loadChapters, workspace: bookWorkspace, setBook(dir, no, list) { currentStoryDir = dir; currentChapterNo = no; chapterSequence = list; }, dirty(value) { chapterHasUnsavedEdits = value; }, select(value) { segmentSelection = value; }, segmentState() { return { selection: segmentSelection, result: segmentResult, request: segmentRequestToken }; }, state() { return { story: currentStoryDir, chapter: currentChapterNo, sequence: chapterSequence, dirty: chapterHasUnsavedEdits, token: storyViewToken }; } };', context);
    const h = { ...dom, api: context.navigation, timers, requests, confirmations, notifications, loads, selection };
    h.activate = name => { h.tabs.forEach(tab => tab.classList.toggle('active', tab.dataset.tab === name));
        for (const tabName of ['generate', 'library', 'reference-index', 'models', 'assets', 'settings']) h.elements['tab-' + tabName].classList.toggle('active', tabName === name); };
    h.open = async () => {
        h.activate('library'); h.api.setBook('book-a', 2, [1, 2, 3]); await h.api.workspace.open('book-a');
        h.elements['library-list'].classList.add('hidden'); h.elements['chapter-view'].classList.remove('hidden');
        h.elements['chapter-list'].classList.add('hidden'); h.api.syncBookNavigation();
    };
    h.clickTab = name => h.tabs.find(tab => tab.dataset.tab === name).emit('click');
    return h;
}

test('原返回ID唯一、脱离书头，fixed贴54px页头下沿且通知bottom不动', () => {
    assert.equal([...html.matchAll(/\bid="btn-back-stories"/g)].length, 1);
    const button = /<button\b[^>]*id="btn-back-stories"[^>]*>[^<]*<\/button>/.exec(html)[0];
    assert.match(button, /class="btn-back book-return hidden"/); assert.match(button, /type="button"/);
    assert.ok(html.indexOf(button) > html.indexOf('</main>'));
    const rule = /\.book-return\s*\{([^}]+)\}/.exec(bookCss)[1];
    assert.match(rule, /position:\s*fixed/); assert.match(rule, /left:\s*var\(--left-rail-offset\)/);
    assert.match(rule, /top:\s*54px/); assert.match(rule, /bottom:\s*auto/);
    assert.match(rule, /border-radius:\s*999px/); assert.match(rule, /width:\s*var\(--left-return-width\)/);
    assert.match(rule, /white-space:\s*nowrap/); assert.match(rule, /flex-direction:\s*row/);
    assert.match(rule, /writing-mode:\s*horizontal-tb/); assert.doesNotMatch(rule, /writing-mode:.*vertical/);
    const notice = /\.update-fab\s*\{([^}]+)\}/.exec(css)[1];
    assert.match(notice, /left:\s*var\(--left-rail-offset\)/); assert.match(notice, /bottom:\s*var\(--left-rail-bottom\)/);
    assert.match(notice, /height:\s*var\(--left-notice-height\)/);
});

test('桌面和窄窗都由共享变量预留左空栏，滚动不改固定按钮位置；窄章导航最终规则有效', () => {
    const workspace = /\.book-workspace\s*\{([^}]+)\}/.exec(bookCss)[1];
    assert.match(workspace, /padding-left:\s*calc\(var\(--left-rail-offset\) \+ var\(--left-return-width\) \+ var\(--left-rail-gap\)\)/);
    const readVar = (text, name) => Number(new RegExp('--' + name + ':\\s*(\\d+)px').exec(text)[1]);
    const rootVars = /:root\s*\{([^}]+)\}/.exec(css)[1];
    const narrowVars = /@media \(max-width: 760px\) \{\s*:root\s*\{([^}]+)\}/.exec(css)[1];
    for (const width of [1920, 1280, 760, 390, 320]) {
        const values = Object.fromEntries(['left-rail-offset', 'left-return-width', 'left-rail-gap', 'left-rail-bottom', 'left-notice-height']
            .map(name => [name, readVar(narrowVars.includes('--' + name + ':') && width <= 760 ? narrowVars : rootVars, name)]));
        const returnRight = values['left-rail-offset'] + values['left-return-width'];
        const mainWidth = Math.min(1600, width * .95), mainLeft = (width - mainWidth) / 2 + 24;
        const contentLeft = mainLeft + returnRight + values['left-rail-gap'];
        assert.ok(contentLeft > returnRight, width + 'px 左空栏须让出返回位置');
        assert.ok(width - 24 - contentLeft > 100, width + 'px 仍须留有可读正文宽度');
        const returnTop = Number(/top:\s*(\d+)px/.exec(/\.book-return\s*\{([^}]+)\}/.exec(bookCss)[1])[1]);
        assert.equal(returnTop, 54, '所有视口下都贴页头下沿，不使用通知bottom计算');
        const noticeTop = 800 - values['left-rail-bottom'] - values['left-notice-height'];
        assert.ok(returnTop + 44 < noticeTop, '顶部返回与底部通知不重叠');
    }
    const allCss = css + '\n' + bookCss;
    const lastNavRule = [...allCss.matchAll(/#chapter-navigation\s*\{([^}]+)\}/g)].at(-1)[1];
    assert.match(lastNavRule, /top:\s*auto/); assert.match(lastNavRule, /flex-direction:\s*row/);
    assert.match(lastNavRule, /bottom:\s*12px/);
    assert.match(bookCss, /\.book-workspace \{ padding-right: 0; padding-bottom: 84px; \}/);
});

test('返回只在library书内显示，书内一级tab可见返回但非正文隐藏章节nav', async () => {
    const h = harness(); assert.equal(h.elements['btn-back-stories'].classList.contains('hidden'), true);
    await h.open();
    assert.equal(h.elements['btn-back-stories'].classList.contains('hidden'), false);
    assert.equal(h.elements['chapter-navigation'].classList.contains('hidden'), false);
    for (const tab of ['overview', 'authoring', 'runs']) {
        h.api.workspace.selectTab(tab);
        assert.equal(h.elements['btn-back-stories'].classList.contains('hidden'), false, tab + ' 仍可退出当前书');
        assert.equal(h.elements['chapter-navigation'].classList.contains('hidden'), true, tab + ' 不显示章节浮层');
    }
    h.api.workspace.selectTab('chapters'); assert.equal(h.elements['chapter-navigation'].classList.contains('hidden'), false);
    h.elements['chapter-view'].classList.add('hidden'); h.api.syncBookNavigation();
    assert.equal(h.elements['btn-back-stories'].classList.contains('hidden'), false); assert.equal(h.elements['chapter-navigation'].classList.contains('hidden'), true);
});

test('其他全局tab隐藏返回、章节nav和段落浮层；仅reference-index激活refresh', async () => {
    const h = harness(); await h.open();
    for (const name of ['generate', 'reference-index', 'models', 'assets', 'settings']) {
        h.api.select({ text: '旧片段', start: 0 });
        h.elements['segment-toolbar'].classList.remove('hidden'); h.elements['segment-dialog'].classList.remove('hidden');
        h.elements['segment-original'].textContent = '旧预览';
        await h.clickTab(name);
        for (const id of ['btn-back-stories', 'chapter-navigation', 'segment-toolbar', 'segment-dialog']) assert.equal(h.elements[id].classList.contains('hidden'), true, name + ' / ' + id);
        assert.equal(h.api.segmentState().selection, null); assert.equal(h.elements['segment-original'].textContent, '');
        await h.clickTab('library'); assert.equal(h.elements['btn-back-stories'].classList.contains('hidden'), false);
    }
    assert.equal(h.loads.filter(value => value === 'reference-refresh').length, 1);
    assert.equal(h.requests.some(item => item.url.startsWith('/api/reference-index')), false);
});

test('原canLeave确认被取消或busy时不closeBook，原书与选区保持；通过才关闭', async () => {
    const cancel = harness({ confirm: false }); await cancel.open(); cancel.api.dirty(true); cancel.api.select({ text: '未保存选区' });
    const token = cancel.api.state().token;
    await cancel.elements['btn-back-stories'].emit('click');
    assert.equal(cancel.confirmations.length, 1); assert.equal(cancel.api.state().story, 'book-a');
    assert.equal(cancel.api.state().token, token); assert.notEqual(cancel.api.segmentState().selection, null);
    assert.equal(cancel.elements['btn-back-stories'].classList.contains('hidden'), false);
    const busy = harness(); await busy.open(); busy.api.workspace.state.busy = true;
    await busy.elements['btn-back-stories'].emit('click');
    assert.equal(busy.api.state().story, 'book-a'); assert.match(busy.notifications[0], /请求中/); assert.equal(busy.confirmations.length, 0);
});

test('closeBook清除选区、预览、导航和旧序列，失效所有读取；保持原closeBook/open机制', async () => {
    const h = harness(); await h.open(); h.api.dirty(true); h.api.select({ text: '旧片段' });
    for (const id of ['segment-toolbar', 'segment-dialog', 'chapter-issue-context', 'save-result']) h.elements[id].classList.remove('hidden');
    for (const id of ['segment-original', 'segment-edited', 'segment-note']) h.elements[id].textContent = '旧结果';
    h.elements['chapter-view'].classList.add('editing');
    const token = h.api.state().token;
    await h.elements['btn-back-stories'].emit('click');
    assert.equal(h.confirmations.length, 1); assert.equal(h.api.state().story, null); assert.equal(h.api.state().chapter, null);
    assert.equal(h.api.state().sequence.length, 0); assert.equal(h.api.state().dirty, false); assert.equal(h.api.state().token, token + 1);
    assert.equal(h.api.workspace.state.storyDir, ''); assert.equal(h.api.segmentState().selection, null); assert.equal(h.api.segmentState().result, null);
    for (const id of ['btn-back-stories', 'chapter-navigation', 'btn-prev-chapter', 'btn-next-chapter', 'segment-toolbar', 'segment-dialog', 'chapter-issue-context', 'save-result', 'book-workspace']) assert.equal(h.elements[id].classList.contains('hidden'), true, id);
    for (const id of ['segment-original', 'segment-edited', 'segment-note']) assert.equal(h.elements[id].textContent, '');
    assert.equal(h.elements['library-list'].classList.contains('hidden'), false); assert.equal(h.elements['chapter-view'].classList.contains('editing'), false);
    assert.ok(h.selection.cleared > 0); assert.equal(h.selection.rangeCount, 0);
});

test('退出后的异步段落成功/失败不得重新打开全局弹层或显示别书结果', async () => {
    for (const leave of ['close', 'tab', 'book-tab']) {
        const wait = deferred();
        const h = harness({ handle: url => url.endsWith('/segment-edit') ? wait.promise : {} });
        await h.open(); h.api.select({ text: '足够长的旧章节片段', before: '', after: '', start: 0, end: 10 });
        h.api.runSegmentEdit('polish'); await flush();
        assert.equal(h.elements['segment-dialog'].classList.contains('hidden'), false);
        if (leave === 'close') h.api.closeBook();
        if (leave === 'tab') await h.clickTab('settings');
        if (leave === 'book-tab') h.api.workspace.selectTab('overview');
        wait.resolve({ editedText: '旧请求回来的危险正文', action: 'polish' }); await flush();
        assert.equal(h.elements['segment-dialog'].classList.contains('hidden'), true, leave);
        assert.equal(h.api.segmentState().result, null); assert.equal(h.elements['segment-edited'].textContent, '');
    }
});

test('切至其他全局tab时迟到章节读取不显示nav也不滚动隐藏正文，退出后迟到目录失效', async () => {
    const chapterWait = deferred(), chaptersWait = deferred();
    const h = harness({ handle: url => url.endsWith('/chapters/2') ? chapterWait.promise : url.endsWith('/chapters') ? chaptersWait.promise : {} });
    await h.open(); h.api.loadChapter('book-a', 2); await h.clickTab('models');
    chapterWait.resolve({ chapterNo: 2, title: '第二章', content: '新正文' }); await flush();
    assert.equal(h.elements['btn-back-stories'].classList.contains('hidden'), true);
    assert.equal(h.elements['chapter-navigation'].classList.contains('hidden'), true); assert.equal(h.elements['chapter-view-title'].scrolled, undefined);
    await h.clickTab('library'); h.api.loadChapters('book-a'); h.api.closeBook();
    chaptersWait.resolve([{ chapterNo: 2, title: '旧目录' }]); await flush();
    assert.equal(h.api.state().story, null); assert.equal(h.elements['book-workspace'].classList.contains('hidden'), true);
    assert.equal(h.elements['library-list'].classList.contains('hidden'), false);
});

test('非library与非正文的选区事件不会弹段落工具条；返回章节目录清理选区导航残留', async () => {
    const h = harness(); await h.open();
    h.api.select({ text: '选中的旧正文' }); await h.elements['btn-back-chapters'].emit('click');
    assert.equal(h.api.segmentState().selection, null); assert.equal(h.elements['chapter-navigation'].classList.contains('hidden'), true);
    assert.equal(h.elements['chapter-view'].classList.contains('hidden'), true); assert.equal(h.elements['chapter-list'].classList.contains('hidden'), false);
    await h.clickTab('settings'); h.api.select({ text: '再造残留选区' }); h.api.maybeShowSegmentToolbar();
    assert.equal(h.api.segmentState().selection, null); assert.equal(h.elements['segment-toolbar'].classList.contains('hidden'), true);
});
