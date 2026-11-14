'use strict';
// 书内工作区的离线结构测试：不启动服务、不访问网络、不读写真实故事目录。
// 只验证组件契约——请求路径、CAS 前置条件、脏状态与失败语义。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../../..');
const staticRoot = path.join(root, 'novel_factory-app/src/main/resources/static');
const source = fs.readFileSync(path.join(staticRoot, 'book-workspace.js'), 'utf8');

function loadModule() {
    // 组件是 UMD：在独立上下文里以 CommonJS 方式求值，避免污染全局
    const module = { exports: {} };
    new Function('module', 'exports', source)(module, module.exports);
    return module.exports;
}

const api = loadModule();

test('工作区导出稳定契约，缺少任一导出都会让 app.js 接线静默失效', () => {
    for (const name of ['create', 'readSettings', 'readAuthoring', 'readSnapshot', 'entriesOf',
        'sourcesOf', 'modeOf', 'characterNames', 'mergeEntry', 'makeEntry', 'previewChanges',
        'readIssue', 'qualityActionBody', 'readQualitySaved']) {
        assert.equal(typeof api[name], 'function', '缺少导出：' + name);
    }
    assert.deepEqual(api.TABS, ['overview', 'chapters', 'authoring', 'runs']);
});

test('设定读取拒绝非文本字段，不把数字或对象静默转成字符串', () => {
    const settings = api.readSettings({ novel_title: '书', theme: '都市' });
    assert.equal(settings.novel_title, '书');
    assert.equal(settings.theme, '都市');
    assert.equal(settings.style, '');
    assert.throws(() => api.readSettings({ novel_title: 42 }), /不是文本/);
    assert.throws(() => api.readSettings(null), /格式无效/);
});

test('创作维护视图缺少版本或来源时拒绝，不按 HTTP 200 猜测可用', () => {
    assert.throws(() => api.readAuthoring({}, 'view'), /缺少版本或来源/);
    const valid = {
        settings: {}, revision: null, snapshot: null, activeSnapshot: null, basis: 'a'.repeat(64),
        sources: [], effectiveFromChapter: 1
    };
    assert.doesNotThrow(() => api.readAuthoring(valid, 'view'));
    // 保存响应必须带 saved=true，预览响应必须带 previewHash；两者缺一即不可写
    assert.throws(() => api.readAuthoring({ ...valid, saved: false }, 'save'), /未确认保存/);
    assert.throws(() => api.readAuthoring(valid, 'preview'), /未返回预览凭据/);
});

test('维护快照校验 schemaVersion、条目操作与人物深度模式', () => {
    const base = {
        schemaVersion: 1, revision: 'rev-1', hash: 'h'.repeat(64), effectiveFromChapter: 1,
        settingsOverrides: {}, entries: [], characterDepthMode: null
    };
    assert.doesNotThrow(() => api.readSnapshot(base));
    assert.throws(() => api.readSnapshot({ ...base, schemaVersion: 3 }), /不完整/);
    assert.doesNotThrow(() => api.readSnapshot({ ...base, schemaVersion: 2, characterExecution: null }));
    assert.throws(() => api.readSnapshot({ ...base, characterDepthMode: 'APPLY_ALL' }), /无法识别/);
    assert.throws(() => api.readSnapshot({
        ...base, entries: [{ kind: 'CHARACTER', entityName: '甲', operation: 'UNKNOWN', after: 'x', reason: 'r' }]
    }), /格式无效/);
});

test('人物执行引用校验版本和完整字段，缺失来源不能当成已建档', () => {
    const settings = {mode: 'APPLY', contextMaxChars: 4800, feedbackMaxChars: 600, maxNewProfilesPerPass: 4, policyVersion: 'character-depth-v1'};
    const profile = {characterId: 'character-a', displayName: '甲', locked: false, want: '得到机会', need: '信任同伴',
        fearAndDefense: null, valuePriorities: null, boundaries: null, independentConcerns: null, decisionPattern: null,
        source: 'AUTHOR', adoption: 'AUTHOR_CONFIRMED'};
    const execution = {basisVersion: 2, settings, profiles: {schemaVersion: 1, revision: 'rev-p', hash: 'a'.repeat(64), effectiveFromChapter: 2, profiles: [profile]}};
    const snapshot = {schemaVersion: 2, revision: 'rev-a', hash: 'b'.repeat(64), effectiveFromChapter: 2,
        settingsOverrides: {}, entries: [], characterDepthMode: 'APPLY', characterExecution: execution};
    assert.equal(api.readSnapshot(snapshot).characterExecution.profiles.profiles[0].want, '得到机会');
    assert.throws(() => api.readSnapshot({...snapshot, characterExecution: {...execution, profiles: {...execution.profiles, revision: ''}}}), /人物/);
    assert.throws(() => api.readSnapshot({...snapshot, characterExecution: {...execution, settings: {...settings, mode: 'ON'}}}), /模式/);
});

test('人物卡请求只提交可编辑设计，不自授来源和身份', () => {
    const cards = api.editableCharacterProfiles([{displayName: '甲', locked: true, want: '找药', need: '接受帮助',
        source: 'MODEL_DESIGN', adoption: 'AUTO_APPLIED', characterId: '伪身份', unknown: '忽略'}]);
    assert.equal(cards[0].want, '找药');
    assert.equal(cards[0].locked, true);
    for (const key of ['source', 'adoption', 'characterId', 'unknown']) assert.equal(Object.hasOwn(cards[0], key), false);
    assert.throws(() => api.editableCharacterProfiles([{displayName: '' , want: '找药'}]), /姓名/);
    assert.throws(() => api.editableCharacterProfiles([{displayName: '甲'}, {displayName: '甲'}]), /同名/);
});

test('人物卡保存差异可见，不只展示模式变更', () => {
    const base = {settings: api.readSettings({}), snapshot: null};
    const changed = {settings: api.readSettings({}), snapshot: {characterDepthMode: 'APPLY', entries: [], characterExecution: {
        settings: {mode: 'APPLY'}, profiles: {profiles: [{displayName: '甲', want: '找药'}]}}}};
    const differences = api.previewChanges(base, changed);
    assert.ok(differences.some(item => item.field === '人物决策卡' && item.after.includes('找药')));
});

test('自动补卡独立执行引用优先于作者旧工作副本', () => {
    const settings = {mode: 'APPLY', contextMaxChars: 4800, feedbackMaxChars: 600, maxNewProfilesPerPass: 4, policyVersion: 'character-depth-v1'};
    const profile = {characterId: 'character-a', displayName: '甲', locked: false, want: '阶段新目标',
        source: 'MODEL_DESIGN', adoption: 'AUTO_APPLIED'};
    const execution = {basisVersion: 2, settings, profiles: {schemaVersion: 1, revision: 'rev-new', hash: 'a'.repeat(64), effectiveFromChapter: 6, profiles: [profile]}};
    const raw = {settings: {}, revision: null, basis: 'b'.repeat(64), snapshot: null, activeSnapshot: null, sources: [], effectiveFromChapter: 6, characterExecution: execution};
    const view = api.readAuthoring(raw, 'view');
    assert.equal(api.modeOf(view), 'APPLY');
    assert.equal(api.characterProfilesOf(view)[0].want, '阶段新目标');
    assert.ok(api.characterNames([], [], view).includes('甲'));
});

test('品质问题处置请求体只带作者判断，不携带正文或质量债字段', () => {
    const issue = api.readIssue({
        id: 'issue-1', chapterNo: 3, sourceHash: 's'.repeat(64), dimension: 'continuity',
        severity: 'MINOR', description: '描述', evidence: '证据', suggestion: '建议',
        systemResolved: false,
        disposition: { state: 'OPEN', note: '', revision: '0' },
        recheck: null,
        currentContentHash: 'c'.repeat(64), currentManuscriptHash: 'm'.repeat(64),
        acceptedManuscriptHash: null, planHash: null
    });
    const body = api.qualityActionBody(issue, 'DEFER', '先放一放');
    assert.equal(body.action, 'DEFER');
    assert.equal(body.reason, '先放一放');
    assert.equal(body.expectedRevision, '0');
    assert.equal(body.expectedSourceHash, 's'.repeat(64));
    for (const forbidden of ['content', 'manuscriptHash', 'debts', 'resolved']) {
        assert.equal(Object.prototype.hasOwnProperty.call(body, forbidden), false,
            '处置请求不得携带：' + forbidden);
    }
});

test('保存响应只认显式 saved=true，不把 200 当成落盘成功', () => {
    const issue = {
        id: 'i', chapterNo: 1, sourceHash: 's'.repeat(64), dimension: 'continuity', severity: 'MINOR',
        description: 'd', evidence: 'e', suggestion: 's', systemResolved: false,
        disposition: { state: 'OPEN', note: '', revision: '0' }, recheck: null,
        currentContentHash: null, currentManuscriptHash: null, acceptedManuscriptHash: null, planHash: null
    };
    assert.throws(() => api.readQualitySaved({ saved: false, issue }, 'i'), /未确认问题处置已保存/);
    assert.throws(() => api.readQualitySaved({ issue }, 'i'), /未确认问题处置已保存/);
    assert.throws(() => api.readQualitySaved({ saved: true, issue: { ...issue, id: 'other' } }, 'i'), /不匹配/);
    assert.equal(api.readQualitySaved({ saved: true, issue }, 'i').id, 'i');
});

test('提取纠错必须绑定来源章与正文版本，且纠正内容须是证据原句片段', () => {
    const evidence = '甲承认自己拿走了信。';
    const sourceEntry = {
        id: 'src-1', kind: 'CHARACTER', entityName: '甲', currentState: '否认',
        sourceChapter: 3, sourceHash: 'a'.repeat(64), evidence
    };
    // 缺来源：拒绝，不允许凭空写纠正
    assert.throws(() => api.makeEntry('CHARACTER', {
        operation: 'EXTRACTION_CORRECTION', after: '承认拿走信', reason: '原文如此'
    }, null, 4), /缺少可核对的正文来源章或版本/);
    // 纠正内容不在证据原句内：拒绝，不允许据引文推断新事实
    assert.throws(() => api.makeEntry('CHARACTER', {
        operation: 'EXTRACTION_CORRECTION', after: '甲主动交代了全部经过', reason: '原文如此'
    }, sourceEntry, 4), /证据原句内含对象名/);
    // 合规：纠正取自证据原句，来源章与哈希原样带入
    const entry = api.makeEntry('CHARACTER', {
        operation: 'EXTRACTION_CORRECTION', after: '甲承认自己拿走了信', reason: '原文如此'
    }, sourceEntry, 4);
    assert.equal(entry.sourceChapter, 3);
    assert.equal(entry.sourceHash, 'a'.repeat(64));
    assert.equal(entry.before, '否认');
});

test('静态资源加载顺序：工作区脚本必须先于 app.js 接线', () => {
    const html = fs.readFileSync(path.join(staticRoot, 'index.html'), 'utf8');
    const workspaceAt = html.indexOf('book-workspace.js');
    const appAt = html.indexOf('src="app.js"');
    assert.ok(workspaceAt > 0, 'index.html 必须引用 book-workspace.js');
    assert.ok(appAt > 0, 'index.html 必须引用 app.js');
    assert.ok(workspaceAt < appAt, '工作区脚本必须在 app.js 之前加载，否则 create 时取不到全局');
    assert.ok(html.includes('book-workspace.css'), 'index.html 必须引用工作区样式');
    for (const id of ['workbench-overview', 'chapter-list', 'chapter-view', 'btn-back-stories', 'checkpoint-list']) {
        assert.equal([...html.matchAll(new RegExp('\\bid="' + id + '"', 'g'))].length, 1, id);
    }
    const app = fs.readFileSync(path.join(staticRoot, 'app.js'), 'utf8');
    assert.ok(!app.includes('insertBefore(workbenchOverview'), '概要卡片必须留在概要标签页');
});
