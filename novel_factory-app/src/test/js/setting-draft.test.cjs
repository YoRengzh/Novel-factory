'use strict';

// 设定草稿离线回归：只执行草稿 UI 区块，用内存 DOM 和替身端点验证请求体与回填。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const flush = () => new Promise(resolve => setImmediate(resolve));

const root = path.resolve(__dirname, '../../../..');
const staticRoot = path.join(root, 'novel_factory-app/src/main/resources/static');
const fieldIds = {
    novelTitle: 'novel_title', style: 'style', worldSetting: 'worldSetting', perspective: 'perspective',
    targetAudience: 'targetAudience', tone: 'tone', protagonist: 'protagonist', outline: 'outline',
    chapterGoal: 'chapterGoal', totalChapters: 'maxChapterCount'
};

function draftBlock() {
    const source = fs.readFileSync(path.join(staticRoot, 'app.js'), 'utf8');
    const start = source.indexOf('    // ===== 一键生成设定集');
    const end = source.indexOf('    // ===== 故事库', start);
    assert.ok(start >= 0 && end > start, '草稿 UI 区块边界必须存在');
    return source.slice(start, end);
}

function harness(overrides = {}) {
    class Element {
        constructor(id = '') {
            this.id = id;
            this.value = '';
            this.textContent = '';
            this.children = [];
            this.dataset = {};
            this.attributes = {};
            this.listeners = {};
            const classes = new Set();
            this.classList = {
                add: key => classes.add(key), remove: key => classes.delete(key),
                toggle: (key, enabled) => enabled ? classes.add(key) : classes.delete(key)
            };
        }
        appendChild(child) { this.children.push(child); }
        setAttribute(key, value) { this.attributes[key] = value; }
        addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
        emit(type) {
            const event = { preventDefault() {}, stopPropagation() {}, target: this };
            (this.listeners[type] || []).forEach(listener => listener(event));
        }
        focus() { this.focused = true; }
    }
    const elements = {};
    const labels = {};
    [...Object.values(fieldIds), 'theme-presets', 'draft-hints', 'draft-status', 'btn-draft-all',
        'btn-draft-outline', 'theme'].forEach(id => { elements[id] = new Element(id); });
    Object.values(fieldIds).forEach(id => { labels[id] = new Element(); });
    elements.theme.value = '校园青春';
    const requests = [], confirmations = [], timers = [], clearedTimers = [];
    const response = {};
    const context = vm.createContext({
        generateFormRevision: 0,
        resumeSelect: { value: '' },
        document: {
            getElementById: id => elements[id],
            createElement: () => new Element(),
            querySelector: selector => labels[/label\[for="([^"]+)"\]/.exec(selector)?.[1]],
            querySelectorAll: () => Object.values(labels).flatMap(label => label.children)
        },
        window: { confirm: message => { confirmations.push(message); return overrides.confirm !== false; } },
        setInterval: (callback, delay) => { timers.push({ callback, delay }); return timers.length; },
        clearInterval: id => clearedTimers.push(id),
        fetchJson: (url, options = {}) => {
            requests.push({ url, method: options.method || 'GET', body: options.body ? JSON.parse(options.body) : null });
            return overrides.error ? Promise.reject(new Error(overrides.error)) : Promise.resolve(response);
        },
        fetch: url => { requests.push({ url }); throw new Error('草稿必须使用替身端点'); },
        submitGenerate: () => { throw new Error('草稿不得提交生成作业'); },
        form: { submit() { throw new Error('草稿不得提交表单'); },
            requestSubmit() { throw new Error('草稿不得请求提交表单'); } },
        applyFormValues: values => {
            Object.entries(values).forEach(([id, value]) => {
                elements[id].value = String(value);
                elements[id].emit('input');
            });
            return Object.keys(values).length;
        }
    });
    vm.runInContext(draftBlock() + '\nglobalThis.draftTest = { requestSettingDraft, applyDraft, collectDraftPrevious, collectPinnedFields, draftLockedFields, draftAiAppliedFields };', context);
    return {
        api: context.draftTest, elements, requests, response, confirmations, timers, clearedTimers,
        resumeSelect: context.resumeSelect,
        lock: key => labels[fieldIds[key]].children.find(child => child.className === 'draft-lock-btn'),
        regenerate: key => labels[fieldIds[key]].children.find(child => child.className === 'regen-btn'),
        edit: (key, value) => { elements[fieldIds[key]].value = value; elements[fieldIds[key]].emit('input'); }
    };
}

test('草稿成功只回填表单；整份、大纲和字段按钮都不创建作业', async () => {
    for (const entry of ['full', 'outline', 'field']) {
        const h = harness();
        h.elements.maxChapterCount.value = '20';
        Object.assign(h.response, { novelTitle: '新故事', tone: '轻松', outline: '新的故事大纲', totalChapters: 30,
            regenerated: entry === 'full' ? ['novelTitle', 'tone', 'outline', 'totalChapters'] : ['outline'] });
        if (entry === 'full') h.elements['btn-draft-all'].emit('click');
        else if (entry === 'outline') h.elements['btn-draft-outline'].emit('click');
        else h.regenerate('outline').emit('click');
        assert.equal(h.elements['btn-draft-all'].disabled, true);
        await flush();
        assert.equal(h.requests.length, 1);
        assert.equal(h.requests[0].url, '/api/setting-draft');
        assert.equal(h.requests[0].method, 'POST');
        assert.deepEqual(h.requests[0].body.targets, entry === 'full' ? null : ['outline']);
        assert.equal(h.elements.outline.value, '新的故事大纲');
        assert.equal(h.elements.novel_title.value, entry === 'full' ? '新故事' : '');
        assert.equal(h.elements.maxChapterCount.value, entry === 'full' ? '30' : '20');
        assert.match(h.elements['draft-status'].textContent, /已回填/);
        assert.doesNotMatch(h.elements['draft-status'].textContent, /生成失败/);
        assert.equal(h.elements['btn-draft-all'].disabled, false);
        assert.equal(h.elements['btn-draft-outline'].disabled, false);
        assert.equal(h.api.draftLockedFields.size, 0);
        assert.equal(h.confirmations.length, 0);
        assert.deepEqual(h.clearedTimers, [1]);
        assert.ok(h.requests.every(request => !request.url.startsWith('/api/jobs')));
    }
});

test('取消覆盖时保留手改字段与显式保留项，且不发出请求', async () => {
    const h = harness({ confirm: false });
    h.edit('novelTitle', '手写书名');
    h.edit('outline', '手写大纲');
    h.edit('tone', '保留基调');
    h.lock('tone').emit('click');
    h.elements['btn-draft-all'].emit('click');
    await flush();
    assert.equal(h.confirmations.length, 1);
    assert.match(h.confirmations[0], /覆盖未保留的已有内容/);
    assert.equal(h.elements.novel_title.value, '手写书名');
    assert.equal(h.elements.outline.value, '手写大纲');
    assert.equal(h.elements.tone.value, '保留基调');
    assert.equal(h.lock('tone').attributes['aria-pressed'], 'true');
    assert.equal(h.requests.length, 0);
    assert.equal(h.timers.length, 0);
});

test('确认整份重生成后保留项不被覆盖，且请求携带 lockedFields', async () => {
    const h = harness();
    h.edit('outline', '旧大纲');
    h.edit('tone', '已保留基调');
    h.lock('tone').emit('click');
    Object.assign(h.response, { outline: '新大纲', tone: '不应覆盖', regenerated: ['outline', 'tone'] });
    h.elements['btn-draft-all'].emit('click');
    await flush();
    assert.equal(h.confirmations.length, 1);
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].url, '/api/setting-draft');
    assert.deepEqual(h.requests[0].body.lockedFields, ['tone']);
    assert.equal(h.elements.outline.value, '新大纲');
    assert.equal(h.elements.tone.value, '已保留基调');
    assert.match(h.elements['draft-status'].textContent, /已回填 1 个字段/);
});

test('草稿失败时保留手改内容、恢复按钮且不提交作业', async () => {
    const h = harness({ error: '503 模型服务暂不可用' });
    h.edit('outline', '手写大纲');
    h.elements['btn-draft-outline'].emit('click');
    await flush();
    assert.equal(h.requests.length, 1);
    assert.equal(h.elements.outline.value, '手写大纲');
    assert.equal(h.elements['btn-draft-all'].disabled, false);
    assert.equal(h.elements['btn-draft-outline'].disabled, false);
    assert.match(h.elements['draft-status'].textContent, /生成失败：503 模型服务暂不可用/);
});

test('首次手填偏好约束整份草稿，但不冒充上一版机器内容', () => {
    const h = harness();
    h.edit('style', '轻喜剧');
    h.edit('tone', '温暖治愈');
    h.api.requestSettingDraft(null);
    const body = h.requests[0].body;
    assert.equal(body.style, '轻喜剧');
    assert.equal(body.tone, '温暖治愈');
    assert.equal(body.previous, null);
    assert.deepEqual(body.lockedFields, []);
});

test('AI 回填不自动加锁，也不把机器值冒充用户偏好', () => {
    const h = harness();
    h.api.applyDraft({ style: '机器风格', tone: '机器基调', regenerated: ['style', 'tone'] }, null);
    assert.equal(h.api.draftLockedFields.size, 0);
    assert.equal(h.lock('style').attributes['aria-pressed'], 'false');
    h.api.requestSettingDraft(null);
    assert.equal(h.requests[0].body.style, null);
    assert.equal(h.requests[0].body.tone, null);
    assert.equal(h.requests[0].body.previous.style, '机器风格');
});

test('显式保留会阻止冲突的单字段重生成，并随整份请求发送', () => {
    const h = harness();
    h.api.applyDraft({ tone: '温暖', regenerated: ['tone'] }, null);
    h.lock('tone').emit('click');
    h.api.applyDraft({ tone: '不应覆盖', outline: '新大纲', regenerated: ['tone', 'outline'] }, null);
    assert.equal(h.elements.tone.value, '温暖');
    h.api.requestSettingDraft(['tone']);
    assert.equal(h.requests.length, 0);
    assert.match(h.elements['draft-status'].textContent, /取消「保留此项」/);
    h.api.requestSettingDraft(null);
    assert.deepEqual(h.requests[0].body.lockedFields, ['tone']);
    assert.equal(h.requests[0].body.previous.tone, '温暖');
});

test('全部字段保留时零请求', () => {
    const h = harness();
    Object.keys(fieldIds).forEach(key => h.lock(key).emit('click'));
    h.api.requestSettingDraft(null);
    assert.equal(h.requests.length, 0);
    assert.match(h.elements['draft-status'].textContent, /无需调用 AI/);
});

test('显式保留空文本时仍发送空 previous 值', () => {
    const h = harness();
    h.lock('outline').emit('click');
    h.api.requestSettingDraft(null);
    assert.equal(h.requests[0].body.previous.outline, '');
    assert.deepEqual(h.requests[0].body.lockedFields, ['outline']);
});

test('迟到草稿在表单已修改时被忽略，且恢复按钮状态', async () => {
    const h = harness();
    // fetchJson 在本 harness 中立即完成；在 then 回调执行前修改表单，模拟响应迟到。
    h.api.requestSettingDraft(['outline']);
    h.elements.outline.value = '用户后来手改的大纲';
    h.elements.outline.emit('input');
    await flush();
    assert.equal(h.elements.outline.value, '用户后来手改的大纲');
    assert.match(h.elements['draft-status'].textContent, /已忽略旧结果/);
});

test('切换故事后旧草稿响应不覆盖新表单', async () => {
    const h = harness();
    // 请求发出后立即切换故事；响应回来时 storyDir 已不匹配，应放弃回填。
    h.api.requestSettingDraft(['outline']);
    h.resumeSelect.value = 'other-story';
    await flush();
    assert.equal(h.elements.outline.value, '');
});
