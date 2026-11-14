(function (root, factory) {
    'use strict';
    const api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else root.BookWorkspace = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    'use strict';

    const SETTINGS = [
        ['novel_title', '书名'], ['theme', '题材'], ['style', '风格'], ['perspective', '视角'],
        ['targetAudience', '目标人群'], ['tone', '基调'], ['worldSetting', '世界观'],
        ['protagonist', '主人公'], ['outline', '故事大纲'], ['chapterGoal', '章节目标']
    ];
    const SETTING_KEYS = SETTINGS.map(pair => pair[0]).concat('characterProfiles');
    const KINDS = {CHARACTER: '人物', ITEM: '物品', FACTION: '势力', FORESHADOW: '伏笔'};
    const KIND_IDS = {CHARACTER: 'character', ITEM: 'item', FACTION: 'faction', FORESHADOW: 'foreshadow'};
    const TABS = ['overview', 'chapters', 'authoring', 'runs'];
    const AUTHORING_TABS = ['settings', 'characters', 'items', 'factions', 'foreshadow', 'issues', 'depth'];
    const text = value => typeof value === 'string' ? value : '';
    const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
    const positive = value => Number.isInteger(value) && value > 0;
    const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
    const copy = value => JSON.parse(JSON.stringify(value));
    const CHARACTER_FIELDS = [['want', '当前欲望'], ['need', '内在需要'], ['fearAndDefense', '恐惧与防御'],
        ['valuePriorities', '价值排序'], ['boundaries', '行为底线'], ['independentConcerns', '独立牵挂'], ['decisionPattern', '决策习惯']];
    const DEPTH_MODES = ['OFF', 'OBSERVE', 'APPLY'];

    function editableCharacterProfiles(profiles) {
        if (!Array.isArray(profiles)) throw new Error('人物决策卡格式无效。');
        if (profiles.length > 64) throw new Error('人物数量不能超过64。');
        const names = new Set();
        return profiles.map(profile => {
            if (!object(profile) || !text(profile.displayName).trim()) throw new Error('请填写人物姓名。');
            const name = profile.displayName.trim();
            if (names.has(name)) throw new Error('同名人物存在歧义：' + name);
            names.add(name);
            const result = {displayName: name, locked: profile.locked === true};
            CHARACTER_FIELDS.forEach(([key]) => {
                if (profile[key] != null && typeof profile[key] !== 'string') throw new Error('人物字段不是文本：' + key);
                if (text(profile[key]).length > 2048) throw new Error('人物字段过长：' + key);
                result[key] = text(profile[key]);
            });
            return result;
        });
    }
    function characterExecutionOf(view) {
        if (view && own(view, 'characterExecution')) return view.characterExecution;
        return view && view.snapshot ? view.snapshot.characterExecution : null;
    }
    function characterProfilesOf(view) {
        const execution = characterExecutionOf(view);
        return execution && execution.profiles ? execution.profiles.profiles : [];
    }
    function readCharacterExecution(raw) {
        if (raw == null) return null;
        if (!object(raw) || raw.basisVersion !== 2 || !object(raw.settings) || !DEPTH_MODES.includes(raw.settings.mode)) {
            throw new Error('人物执行模式或版本无法识别，不可保存。');
        }
        const settings = raw.settings;
        if (!Number.isInteger(settings.contextMaxChars) || settings.contextMaxChars < 800 || settings.contextMaxChars > 16000
                || !Number.isInteger(settings.feedbackMaxChars) || settings.feedbackMaxChars < 0 || settings.feedbackMaxChars > 4000
                || !Number.isInteger(settings.maxNewProfilesPerPass) || settings.maxNewProfilesPerPass < 1 || settings.maxNewProfilesPerPass > 8
                || !text(settings.policyVersion)) throw new Error('人物执行预算或策略版本不完整。');
        if (raw.profiles != null) {
            const set = raw.profiles;
            if (!object(set) || set.schemaVersion !== 1 || !text(set.revision) || !/^[a-f0-9]{64}$/.test(text(set.hash))
                    || !positive(set.effectiveFromChapter)) throw new Error('人物卡缺少可核对版本与生效章。');
            editableCharacterProfiles(set.profiles);
            set.profiles.forEach(profile => {
                if (!text(profile.characterId) || !['AUTHOR', 'MODEL_DESIGN'].includes(profile.source)
                        || !['AUTHOR_CONFIRMED', 'AUTO_APPLIED', 'DRAFT'].includes(profile.adoption)) throw new Error('人物卡来源不完整。');
            });
        }
        return copy(raw);
    }

    function readSettings(raw) {
        if (!object(raw)) throw new Error('书级设定格式无效，不可保存。');
        const settings = {};
        SETTING_KEYS.forEach(key => {
            if (own(raw, key) && raw[key] != null && typeof raw[key] !== 'string') {
                throw new Error('设定字段不是文本：' + key + '。当前输入保留。');
            }
            settings[key] = text(raw[key]);
        });
        return settings;
    }

    function readSnapshot(raw) {
        if (raw == null) return null;
        if (!object(raw) || ![1, 2].includes(raw.schemaVersion) || !Array.isArray(raw.entries)
                || !object(raw.settingsOverrides) || !text(raw.revision) || !text(raw.hash)) {
            throw new Error('创作维护版本不完整，不可保存。');
        }
        raw.entries.forEach(entry => {
            if (!object(entry) || !KINDS[entry.kind] || !text(entry.entityName)
                    || !['EXTRACTION_CORRECTION', 'FUTURE_INTENT'].includes(entry.operation)
                    || typeof entry.after !== 'string' || !text(entry.reason)) {
                throw new Error('维护记录格式无效，不可用空记录替代。');
            }
        });
        if (raw.characterDepthMode != null && !DEPTH_MODES.includes(raw.characterDepthMode)) {
            throw new Error('书级人物深度模式无法识别，不可保存。');
        }
        readCharacterExecution(raw.characterExecution);
        return copy(raw);
    }

    // DTO 适配唯一入口：GET / preview / PUT 都是同一顶层形状，不根据 HTTP 200 猜测保存成功。
    function readAuthoring(raw, purpose) {
        if (!object(raw) || !own(raw, 'revision') || (raw.revision !== null && !text(raw.revision))
                || !own(raw, 'snapshot') || !own(raw, 'activeSnapshot') || !text(raw.basis) || !Array.isArray(raw.sources)
                || !positive(raw.effectiveFromChapter)) throw new Error('创作管理接口缺少版本或来源，不可保存。');
        if (purpose === 'save' && raw.saved !== true) throw new Error('服务器未确认保存，当前输入保留。');
        if (purpose === 'preview' && !text(raw.previewHash)) throw new Error('服务器未返回预览凭据，不可保存。');
        const settings = readSettings(raw.settings), snapshot = readSnapshot(raw.snapshot);
        const activeSnapshot = readSnapshot(raw.activeSnapshot);
        if (purpose === 'preview' && !snapshot) throw new Error('服务器未返回候选维护版本，不可保存。');
        if (purpose === 'save' && (!snapshot || !activeSnapshot || raw.revision !== snapshot.revision || snapshot.hash !== activeSnapshot.hash
                || snapshot.revision !== activeSnapshot.revision)) {
            throw new Error('服务器未确认当前维护版本已激活，当前输入保留，请重新读取核对。');
        }
        const sources = raw.sources.map(source => {
            if (!object(source) || !KINDS[source.kind] || !text(source.entityName)) {
                throw new Error('来源元数据格式无效，不可保存。');
            }
            return copy(source);
        });
        return {settings, revision: raw.revision, basis: raw.basis, snapshot, activeSnapshot, sources,
            ...(own(raw, 'characterExecution') ? {characterExecution: readCharacterExecution(raw.characterExecution)} : {}),
            effectiveFromChapter: raw.effectiveFromChapter, previewHash: text(raw.previewHash),
            warnings: Array.isArray(raw.warnings) ? raw.warnings.map(text).filter(Boolean) : []};
    }

    function entriesOf(view, kind, name) {
        const entries = view && view.snapshot ? view.snapshot.entries : [];
        return entries.filter(entry => (!kind || entry.kind === kind) && (!name || entry.entityName === name));
    }
    function sourcesOf(view, kind, name) {
        return (view ? view.sources : []).filter(source => source.kind === kind && (!name || source.entityName === name));
    }
    function modeOf(view) {
        const execution = characterExecutionOf(view);
        return execution ? execution.settings.mode : view && view.snapshot ? text(view.snapshot.characterDepthMode) : '';
    }
    function characterNames(cards, ledger, view) {
        const names = [];
        (cards || []).concat(characterProfilesOf(view)).forEach(card => { if (text(card.displayName).trim()) names.push(card.displayName.trim()); });
        (ledger || []).forEach(entry => { if (text(entry.name)) names.push(entry.name); });
        sourcesOf(view, 'CHARACTER').forEach(source => names.push(source.entityName));
        entriesOf(view, 'CHARACTER').forEach(entry => names.push(entry.entityName));
        return Array.from(new Set(names));
    }
    function mergeEntry(view, entry, index) {
        const entries = copy(entriesOf(view));
        if (index == null) entries.push(entry);
        else if (index >= 0 && index < entries.length) entries[index] = entry;
        else throw new Error('维护记录已变更，请重新选择。');
        return entries;
    }
    function makeEntry(kind, draft, source, earliestChapter) {
        if (!KINDS[kind] || !['EXTRACTION_CORRECTION', 'FUTURE_INTENT'].includes(draft.operation)) {
            throw new Error('请选择维护类型。');
        }
        const after = text(draft.after), reason = text(draft.reason);
        if (!after.trim() || !reason.trim()) throw new Error('请填写修改后内容和理由。');
        if (draft.operation === 'EXTRACTION_CORRECTION') {
            if (!source || source.kind !== kind || !positive(source.sourceChapter) || !text(source.sourceHash)) {
                throw new Error('缺少可核对的正文来源章或版本，不能保存提取纠错。请先刷新来源。');
            }
            const evidence = text(draft.evidence).trim() || text(source.evidence);
            if (!evidence.trim() || !evidence.includes(source.entityName) || !after.includes(source.entityName) || !evidence.includes(after)) {
                throw new Error('纠正内容必须是证据原句内含对象名的连续片段。请对照正文填写，不据引文推断新事实。');
            }
            return {kind, entityName: source.entityName, operation: draft.operation, before: text(source.currentState),
                after, reason, sourceChapter: source.sourceChapter, sourceHash: source.sourceHash, evidence};
        }
        const effectiveFromChapter = Number(draft.effectiveFromChapter);
        if (!text(draft.entityName).trim()) throw new Error('请填写实体名称。');
        if (!positive(effectiveFromChapter) || effectiveFromChapter < earliestChapter) {
            throw new Error('未来意图必须指定有效生效章，不能早于第 ' + earliestChapter + ' 章。');
        }
        return {kind, entityName: draft.entityName.trim(), operation: draft.operation, before: '', after, reason,
            effectiveFromChapter, sourceChapter: null, sourceHash: null, evidence: null};
    }
    function previewChanges(before, after) {
        const result = [];
        SETTING_KEYS.forEach(key => {
            if (before.settings[key] !== after.settings[key]) result.push({field: key, before: before.settings[key], after: after.settings[key]});
        });
        const oldMode = modeOf(before), newMode = modeOf(after);
        if (oldMode !== newMode) result.push({field: '人物深度模式', before: oldMode || '沿用服务端默认', after: newMode || '沿用服务端默认'});
        const oldProfiles = characterProfilesOf(before), newProfiles = characterProfilesOf(after);
        if (JSON.stringify(oldProfiles) !== JSON.stringify(newProfiles)) {
            result.push({field: '人物决策卡', before: JSON.stringify(oldProfiles, null, 2), after: JSON.stringify(newProfiles, null, 2)});
        }
        const oldEntries = entriesOf(before), newEntries = entriesOf(after);
        if (JSON.stringify(oldEntries) !== JSON.stringify(newEntries)) {
            result.push({field: '维护记录', before: JSON.stringify(oldEntries, null, 2), after: JSON.stringify(newEntries, null, 2)});
        }
        return result;
    }
    function readIssue(raw) {
        if (!object(raw) || !text(raw.id) || !positive(raw.chapterNo) || !text(raw.sourceHash)
                || !object(raw.disposition) || !text(raw.disposition.revision)
                || !['OPEN', 'DEFERRED', 'FALSE_POSITIVE'].includes(raw.disposition.state)
                || typeof raw.systemResolved !== 'boolean') throw new Error('质量问题缺少处置版本或来源，不可提交操作。');
        return copy(raw);
    }
    function qualityActionBody(issue, action, reason) {
        if (!['NOTE', 'DEFER', 'FALSE_POSITIVE'].includes(action) || !text(reason).trim()) throw new Error('请填写处置理由或备注。');
        return {action, reason, expectedRevision: issue.disposition.revision, expectedSourceHash: issue.sourceHash};
    }
    function readQualitySaved(response, id) {
        if (!object(response) || response.saved !== true) throw new Error('服务器未确认问题处置已保存，备注保留。');
        const issue = readIssue(response.issue);
        if (issue.id !== id) throw new Error('返回的质量问题不匹配，未覆盖当前内容。');
        return issue;
    }

    function create(options) {
        const doc = options.document, request = options.fetchJson;
        const ask = options.confirm, notify = options.notify || function () {};
        const el = id => doc.getElementById(id);
        const node = (tag, value, className) => {
            const item = doc.createElement(tag);
            if (value != null) item.textContent = value;
            if (className) item.className = className;
            return item;
        };
        const button = (label, action, className) => {
            const item = node('button', label, className || 'btn-secondary'); item.type = 'button';
            item.addEventListener('click', action); return item;
        };
        const state = {storyDir: '', epoch: 0, version: 0, readRequest: 0, historyRequest: 0, qualityRequest: 0,
            tab: 'chapters', authoringTab: 'settings', characterTab: 'basic', selectedCharacter: '', cards: [], ledger: [],
            authoring: null, readState: 'empty', settings: readSettings({}), mode: '', settingsEditing: false, factsEditing: false,
            entryDrafts: {}, preview: null, busy: false, issues: [], issuesRead: false, issueNotes: Object.create(null), issueStatus: Object.create(null), qualityBusy: false,
            history: Object.create(null), workbench: null, characterDraft: null};
        const settingInputs = {};
        const extraBusy = () => !!(options.isExtraBusy && options.isExtraBusy());
        const busy = () => state.busy || state.qualityBusy || extraBusy();
        const settingsDirty = () => !!state.authoring && SETTING_KEYS.some(key => state.settings[key] !== state.authoring.settings[key]);
        const dirty = () => settingsDirty() || !!state.characterDraft || !!state.preview || (state.authoring && state.mode !== modeOf(state.authoring))
            || Object.values(state.entryDrafts).some(draft => draft.dirty)
            || Object.values(state.issueNotes).some(value => value.trim());
        const current = (storyDir, epoch) => state.storyDir === storyDir && state.epoch === epoch;
        const url = suffix => '/api/stories/' + encodeURIComponent(state.storyDir) + suffix;
        function status(message, ok) {
            ['book-authoring-status', 'book-run-status'].forEach(id => {
                const box = el(id); box.textContent = message || '';
                box.classList.toggle('hidden', !message); box.classList.toggle('draft-status-error', ok === false);
                box.classList.toggle('has-config', ok === true);
            });
        }
        function invalidatePreview() {
            state.preview = null; el('book-authoring-preview').classList.add('hidden');
        }
        function touched() { state.version++; invalidatePreview(); sync(); }
        function sync() {
            const locked = busy(), ready = state.readState === 'ready' && !!state.authoring;
            el('book-unsaved').classList.toggle('hidden', !dirty() && !(options.isExtraDirty && options.isExtraDirty()));
            ['btn-edit-book-settings', 'btn-edit-book-facts', 'btn-preview-book-settings', 'btn-preview-book-facts', 'btn-preview-book-mode']
                .forEach(id => { el(id).disabled = locked || !ready; });
            ['btn-reload-authoring', 'btn-cancel-book-settings', 'btn-cancel-book-facts', 'btn-refresh-quality-issues', 'btn-cancel-book-preview', 'btn-new-checkpoint', 'btn-refresh-checkpoints', 'btn-refresh-runs']
                .forEach(id => { el(id).disabled = locked; });
            el('btn-confirm-book-authoring').disabled = locked || !state.preview;
            el('book-character-depth-mode').disabled = locked || !ready;
            ['btn-add-character-card', 'btn-save-character-cards', 'btn-reload-character-cards'].forEach(id => { el(id).disabled = locked || !ready; });
            el('btn-save-character-cards').disabled = locked || !ready || !state.characterDraft;
            el('btn-draft-character-cards').disabled = true;
            doc.querySelectorAll('#character-decision-cards input, #character-decision-cards textarea, #character-decision-cards button')
                .forEach(control => { control.disabled = locked || !ready; });
            Object.values(settingInputs).forEach(input => { input.readOnly = locked || !state.settingsEditing || !ready; });
            el('book-character-facts').readOnly = locked || !ready;
            doc.querySelectorAll('[data-book-tab], [data-authoring-tab], [data-character-tab]').forEach(item => { item.disabled = locked; });
            el('btn-back-stories').disabled = locked;
            el('btn-back-chapters').disabled = locked;
            el('book-workspace').setAttribute('aria-busy', String(locked));
            Object.values(state.entryDrafts).forEach(draft => {
                draft.controls.forEach(control => { control.disabled = locked || !ready; });
            });
            doc.querySelectorAll('.book-record button, .book-character-list button, .book-quality-issue button').forEach(control => { control.disabled = locked; });
            if (options.onStateChange) options.onStateChange();
        }
        function selectTab(tab) {
            if (!TABS.includes(tab) || (busy() && state.tab !== tab)) { if (busy()) notify('保存或处置请求中，请等待结果再切换。'); return false; }
            state.tab = tab;
            if (tab !== 'authoring' && tab !== 'runs') el('book-authoring-preview').classList.add('hidden');
            else if (state.preview && (state.preview.scope === 'mode' ? tab === 'runs' : tab === 'authoring')) el('book-authoring-preview').classList.remove('hidden');
            TABS.forEach(name => el('book-panel-' + name).classList.toggle('hidden', name !== tab));
            doc.querySelectorAll('[data-book-tab]').forEach(item => {
                const active = item.dataset.bookTab === tab; item.classList.toggle('active', active); item.setAttribute('aria-selected', String(active));
            });
            if (options.onTabChange) options.onTabChange(tab);
            return true;
        }
        function selectAuthoringTab(tab) {
            if (!AUTHORING_TABS.includes(tab) || (busy() && state.authoringTab !== tab)) return false;
            state.authoringTab = tab;
            AUTHORING_TABS.forEach(name => el('book-authoring-' + name).classList.toggle('hidden', name !== tab));
            if (state.preview && state.preview.scope !== 'mode') {
                    const groups = {settings: 'settings', facts: 'characters', profiles: 'characters', 'entry:CHARACTER': 'characters', 'entry:ITEM': 'items', 'entry:FACTION': 'factions', 'entry:FORESHADOW': 'foreshadow'};
                el('book-authoring-preview').classList.toggle('hidden', groups[state.preview.scope] !== tab);
            }
            doc.querySelectorAll('[data-authoring-tab]').forEach(item => {
                const active = item.dataset.authoringTab === tab; item.classList.toggle('active', active); item.setAttribute('aria-selected', String(active));
            });
            if (tab === 'issues' && !state.issuesRead && state.storyDir) loadQuality();
            return true;
        }
        function selectCharacterTab(tab) {
            if (!['basic', 'motivation', 'state', 'history'].includes(tab) || (busy() && state.characterTab !== tab)) return false;
            state.characterTab = tab;
            ['basic', 'motivation', 'state', 'history'].forEach(name => el('book-character-' + name).classList.toggle('hidden', name !== tab));
            doc.querySelectorAll('[data-character-tab]').forEach(item => {
                const active = item.dataset.characterTab === tab; item.classList.toggle('active', active); item.setAttribute('aria-selected', String(active));
            });
            if (tab === 'history') loadHistory();
            return true;
        }
        function selectCharacter(name) {
            if (busy()) return false;
            state.selectedCharacter = name; renderCharacterList(); renderSelectedCharacter();
            if (options.onCharacterSelection) options.onCharacterSelection(name);
            if (state.characterTab === 'history') loadHistory();
            return true;
        }
        function renderCharacterList() {
            const names = characterNames(state.cards, state.ledger, state.authoring);
            if (!names.includes(state.selectedCharacter)) state.selectedCharacter = names[0] || '';
            const list = el('book-character-list'); list.replaceChildren();
            names.forEach(name => {
                const item = button(name, () => selectCharacter(name));
                item.classList.toggle('active', name === state.selectedCharacter); item.setAttribute('aria-pressed', String(name === state.selectedCharacter));
                list.appendChild(item);
            });
            if (!names.length) list.appendChild(node('p', '暂无人物，先在性格动机中添加。', 'empty-hint'));
            if (options.onCharacterSelection) options.onCharacterSelection(state.selectedCharacter);
        }
        function renderSource(container, source) {
            const row = node('article', null, 'book-record');
            row.appendChild(node('h4', source.entityName)); row.appendChild(node('p', source.currentState || '状态未记录', 'book-long-text'));
            row.appendChild(node('p', '来源：第 ' + source.sourceChapter + ' 章 · ' + (source.verified === true ? '原提取是原文片段' : '原提取尚未通过核对，可对照原文纠正'), 'character-card-source'));
            row.appendChild(node('blockquote', source.evidence || '证据未提供'));
            row.appendChild(node('p', '来源版本（只读）：' + (text(source.sourceHash) || '未提供'), 'character-card-source'));
            if (positive(source.sourceChapter)) row.appendChild(button('定位正文', () => options.openChapter(source.sourceChapter, {evidence: text(source.evidence), description: source.entityName})));
            container.appendChild(row);
        }
        function renderSelectedCharacter() {
            const box = el('workbench-characters'); box.replaceChildren();
            const sources = sourcesOf(state.authoring, 'CHARACTER', state.selectedCharacter);
            if (state.selectedCharacter && sources.length) sources.forEach(source => renderSource(box, source));
            else {
                const rows = state.ledger.filter(entry => entry.name === state.selectedCharacter);
                rows.forEach(entry => {
                    const row = node('article', null, 'book-record'); row.appendChild(node('h4', entry.name));
                    row.appendChild(node('p', text(entry.status) || '状态未记录', 'book-long-text'));
                    row.appendChild(node('p', '最近来源章：' + (entry.lastChapterNo || '未提供')));
                    row.appendChild(node('blockquote', text(entry.evidence) || '证据未提供'));
                    box.appendChild(row);
                });
                if (!rows.length) box.appendChild(node('p', state.workbench ? '暂无该人物已发生状态；不从性格动机推断事实。' : '状态待读取，不能当作无变化。', 'empty-hint'));
            }
            renderRecords('CHARACTER', el('book-character-state-records'), state.selectedCharacter);
            renderHistory();
        }
        function renderHistory() {
            const box = el('book-character-history-list'); box.replaceChildren();
            const history = state.history[state.selectedCharacter];
            if (!state.selectedCharacter) box.appendChild(node('p', '请先选择人物。', 'empty-hint'));
            else if (!history) box.appendChild(node('p', '变化历史待读取。', 'empty-hint'));
            else if (history.error) box.appendChild(node('p', '历史读取失败：' + history.error, 'empty-hint'));
            else if (!history.rows.length) box.appendChild(node('p', '暂无经核对的章节轨迹。', 'empty-hint'));
            else history.rows.forEach(row => {
                const item = node('article', null, 'book-record');
                item.appendChild(node('h4', '第 ' + row.chapterNo + ' 章 · ' + text(row.name)));
                item.appendChild(node('p', text(row.status), 'book-long-text')); item.appendChild(node('blockquote', text(row.evidence) || '未提供证据'));
                item.appendChild(button('查看原文', () => options.openChapter(row.chapterNo, {evidence: text(row.evidence), description: text(row.name)})));
                box.appendChild(item);
            });
            const records = node('div'); renderRecords('CHARACTER', records, state.selectedCharacter); box.appendChild(records);
        }
        async function loadHistory() {
            const name = state.selectedCharacter, storyDir = state.storyDir, epoch = state.epoch;
            if (!name || !storyDir || state.history[name]) { renderHistory(); return; }
            const token = ++state.historyRequest;
            try {
                const rows = await request(url('/facts/' + encodeURIComponent(name)));
                if (!current(storyDir, epoch) || token !== state.historyRequest || state.selectedCharacter !== name) return;
                if (!Array.isArray(rows)) throw new Error('轨迹响应格式无效');
                state.history[name] = {rows: rows.filter(row => object(row) && row.name === name && row.factType === '角色')};
            } catch (err) {
                if (!current(storyDir, epoch) || token !== state.historyRequest) return;
                state.history[name] = {error: err.message};
            }
            renderHistory();
        }
        function renderRecords(kind, container, name) {
            container.replaceChildren(); container.appendChild(node('h4', '作者维护记录'));
            const records = entriesOf(state.authoring, kind, name);
            if (!records.length) container.appendChild(node('p', '暂无维护记录。', 'empty-hint'));
            records.forEach(entry => {
                const row = node('article', null, 'book-record');
                row.appendChild(node('h4', entry.entityName + ' · ' + (entry.operation === 'FUTURE_INTENT' ? '未来意图' : '提取纠错')));
                row.appendChild(node('p', '原记录：' + (text(entry.before) || '无'), 'book-long-text'));
                row.appendChild(node('p', '维护内容：' + entry.after, 'book-long-text')); row.appendChild(node('p', '理由：' + entry.reason, 'book-long-text'));
                row.appendChild(node('p', '生效：第 ' + entry.effectiveFromChapter + ' 章 · 状态：' + (text(entry.status) || '未提供') + ' · 编号：' + (text(entry.id) || '未提供'), 'character-card-source'));
                if (entry.sourceChapter != null) row.appendChild(node('p', '来源第 ' + entry.sourceChapter + ' 章 · ' + text(entry.sourceHash) + '\n证据：' + text(entry.evidence), 'character-card-source'));
                const index = entriesOf(state.authoring).indexOf(entry);
                row.appendChild(button('修订维护项', () => openEntry(kind, entry, index)));
                row.appendChild(button('移除维护项', () => {
                    if (busy() || !ask('移除这条作者维护记录？只改变未来维护引用，不删除原文或账本；先预览，再确认保存。')) return;
                    const entries = copy(entriesOf(state.authoring)); entries.splice(index, 1);
                    preview({entries}, 'entry:' + kind);
                }));
                container.appendChild(row);
            });
        }
        function mountSettings() {
            const fields = el('book-settings-fields');
            SETTINGS.forEach(pair => {
                const group = node('div', null, 'form-group' + (['worldSetting', 'protagonist', 'outline', 'chapterGoal', 'creativeNotes'].includes(pair[0]) ? ' full-width' : ''));
                const id = 'book-setting-' + pair[0], label = node('label', pair[1]); label.htmlFor = id;
                const read = node('p', '', 'book-setting-read book-long-text'); read.id = id + '-read';
                const input = node('textarea'); input.id = id; input.rows = ['outline', 'worldSetting'].includes(pair[0]) ? 8 : 3; input.classList.add('hidden');
                input.addEventListener('input', () => { state.settings[pair[0]] = input.value; touched(); });
                settingInputs[pair[0]] = input; group.appendChild(label); group.appendChild(read); group.appendChild(input); fields.appendChild(group);
            });
        }
        function renderSettings() {
            SETTINGS.forEach(pair => {
                const input = settingInputs[pair[0]], read = el(input.id + '-read');
                input.value = state.settings[pair[0]]; read.textContent = state.settings[pair[0]] || '未设置';
                input.classList.toggle('hidden', !state.settingsEditing); read.classList.toggle('hidden', state.settingsEditing);
            });
            el('book-settings-actions').classList.toggle('hidden', !state.settingsEditing);
            el('btn-edit-book-settings').classList.toggle('hidden', state.settingsEditing);
            el('book-character-facts').value = state.settings.characterProfiles;
            el('book-character-facts-read').textContent = state.settings.characterProfiles || '未建立人物硬事实原文。';
            el('book-character-facts').classList.toggle('hidden', !state.factsEditing);
            el('book-character-facts-read').classList.toggle('hidden', state.factsEditing);
            el('book-facts-actions').classList.toggle('hidden', !state.factsEditing);
            el('btn-edit-book-facts').classList.toggle('hidden', state.factsEditing);
            el('book-character-depth-mode').value = state.mode;
            el('book-character-depth-mode').querySelector('option[value=""]').disabled = !!modeOf(state.authoring);
            const modeHelp = {OFF: '关闭人物决策设计，不自动删改人物档案。', OBSERVE: '只观察，不把观察当作事实或写作指令。', APPLY: '参考已保存决策卡；为明确核心人物自动建档可能增加模型调用。因果核验是独立开关。'};
            el('character-mode-hint').textContent = modeHelp[state.mode] || '未单独设置，将沿用服务端默认；不可把未知模式当作 OFF 保存。';
            const execution = characterExecutionOf(state.authoring);
            el('book-current-mode').textContent = execution
                ? '保存执行模式 ' + execution.settings.mode + ' · 策略 ' + execution.settings.policyVersion
                    + (execution.profiles ? ' · 档案 ' + execution.profiles.revision + ' · 第 ' + execution.profiles.effectiveFromChapter + ' 章起' : ' · 无档案')
                    + '。已启动任务沿冻结版本执行；因果核验状态请单独核对，不由 APPLY 推断。'
                : '无完整人物执行引用；当前配置／运行模式以服务端为准，不把未建档当成已启用。';
            sync();
        }
        function mountEntry(kind) {
            const container = el('book-' + KIND_IDS[kind] + '-entry'); container.replaceChildren();
            const add = button('新增' + KINDS[kind] + '维护记录', () => openEntry(kind)); container.appendChild(add);
            state.entryDrafts[kind] = {dirty: false, controls: [add], container, add, panel: null};
        }
        function openEntry(kind, previous, index) {
            if (busy() || state.readState !== 'ready') return;
            const holder = state.entryDrafts[kind];
            if (holder.dirty && !ask('替换当前未保存的维护输入？')) return;
            if (holder.panel) holder.panel.remove();
            const panel = node('section', null, 'book-entry-editor'), controls = [holder.add];
            const inputs = {};
            const field = (key, labelText, tag, values) => {
                const group = node('div', null, 'form-group'), label = node('label', labelText), input = node(tag || 'textarea');
                input.id = 'book-entry-' + kind.toLowerCase() + '-' + key; label.htmlFor = input.id;
                if (tag === 'select') values.forEach(pair => { const option = node('option', pair[1]); option.value = pair[0]; input.appendChild(option); });
                else if (tag === 'input') input.type = key === 'effectiveFromChapter' ? 'number' : 'text';
                else input.rows = 3;
                input.addEventListener('input', () => { holder.dirty = true; touched(); });
                inputs[key] = input; controls.push(input); group.appendChild(label); group.appendChild(input); panel.appendChild(group); return group;
            };
            field('operation', '维护类型', 'select', [['EXTRACTION_CORRECTION', '提取纠错 · 对照已发生原文'], ['FUTURE_INTENT', '未来意图 · 从指定章生效']]);
            const sources = sourcesOf(state.authoring, kind);
            const sourceGroup = field('source', '正文来源（姓名 / 来源章）', 'select', [['', '请选择可核对来源']].concat(sources.map((source, i) => [String(i), source.entityName + ' · 第 ' + source.sourceChapter + ' 章' + (text(source.sourceHash) ? (source.verified ? '' : '（提取待核对）') : '（缺来源版本，不可纠错）')])));
            const metadata = node('p', '', 'character-card-source'); sourceGroup.appendChild(metadata);
            const nameGroup = field('entityName', KINDS[kind] + '名称', 'input');
            field('after', '维护后内容'); field('reason', '理由');
            const evidenceGroup = field('evidence', '正文证据原句');
            const effectiveGroup = field('effectiveFromChapter', '从第几章生效', 'input');
            inputs.effectiveFromChapter.min = String(state.authoring.effectiveFromChapter);
            inputs.effectiveFromChapter.value = String(state.authoring.effectiveFromChapter);
            inputs.operation.value = previous ? previous.operation : 'EXTRACTION_CORRECTION';
            if (previous) {
                ['entityName', 'after', 'reason', 'evidence'].forEach(key => { inputs[key].value = text(previous[key]); });
                inputs.effectiveFromChapter.value = String(previous.effectiveFromChapter);
                const i = sources.findIndex(source => source.entityName === previous.entityName && source.sourceChapter === previous.sourceChapter && source.sourceHash === previous.sourceHash);
                inputs.source.value = i < 0 ? '' : String(i);
            } else if (kind === 'CHARACTER') inputs.entityName.value = state.selectedCharacter;
            function sourceInfo(fillEvidence) {
                const source = inputs.source.value === '' ? null : sources[Number(inputs.source.value)];
                metadata.textContent = source ? '原状态：' + text(source.currentState) + '\n来源版本（只读）：' + text(source.sourceHash)
                    + '\n' + (text(source.sourceHash) ? '来源正文版本可核对；纠正内容须为下方证据原文内含对象名的连续片段。' : '缺少来源正文版本，禁止保存提取纠错') : '没有有效来源时不能提取纠错；不会生成或猜测来源哈希。';
                if (source && fillEvidence) inputs.evidence.value = text(source.evidence);
            }
            function operationInfo() {
                const future = inputs.operation.value === 'FUTURE_INTENT';
                sourceGroup.classList.toggle('hidden', future); evidenceGroup.classList.toggle('hidden', future);
                nameGroup.classList.toggle('hidden', !future); effectiveGroup.classList.toggle('hidden', !future);
            }
            inputs.source.addEventListener('change', () => { sourceInfo(true); holder.dirty = true; touched(); });
            inputs.operation.addEventListener('change', () => { operationInfo(); holder.dirty = true; touched(); });
            const submit = button('预览维护变更', () => {
                try {
                    const draft = Object.fromEntries(Object.entries(inputs).map(([key, input]) => [key, input.value]));
                    const source = inputs.source.value === '' ? null : sources[Number(inputs.source.value)];
                    const entry = makeEntry(kind, draft, source, state.authoring.effectiveFromChapter);
                    if (previous && previous.id) entry.id = previous.id;
                    preview({entries: mergeEntry(state.authoring, entry, index)}, 'entry:' + kind);
                } catch (err) { status(err.message, false); }
            }, 'btn-primary');
            const cancel = button('取消维护输入', () => {
                if (busy() || (holder.dirty && !ask('放弃此维护记录的未保存输入？'))) return;
                holder.dirty = false; panel.remove(); holder.panel = null; holder.controls = [holder.add]; touched();
            });
            const actions = node('div', null, 'form-actions'); actions.appendChild(submit); actions.appendChild(cancel); panel.appendChild(actions);
            controls.push(submit, cancel); Object.assign(holder, {panel, controls, inputs, dirty: false}); holder.container.appendChild(panel);
            sourceInfo(!previous); operationInfo(); sync();
        }
        function renderEntities(kind) {
            const id = {ITEM: 'workbench-items', FACTION: 'workbench-factions', FORESHADOW: 'workbench-foreshadow'}[kind];
            const container = el(id), sources = sourcesOf(state.authoring, kind);
            const key = {ITEM: 'items', FACTION: 'factions', FORESHADOW: 'foreshadowing'}[kind];
            const ledger = state.workbench && state.workbench[key] || [];
            container.replaceChildren();
            sources.forEach(source => renderSource(container, source));
            const seen = new Set(sources.map(source => source.entityName));
            ledger.filter(entry => !seen.has(kind === 'FORESHADOW' ? entry.content : entry.name)).forEach(entry => {
                const row = node('article', null, 'book-record');
                row.appendChild(node('h4', kind === 'FORESHADOW' ? '第 ' + entry.chapterNo + ' 章 · ' + text(entry.status) : text(entry.name)));
                row.appendChild(node('p', text(kind === 'FORESHADOW' ? entry.content : entry.status), 'book-long-text'));
                if (text(entry.evidence)) row.appendChild(node('blockquote', entry.evidence));
                if (positive(entry.lastChapterNo)) row.appendChild(node('p', '最近来源章：' + entry.lastChapterNo, 'character-card-source'));
                container.appendChild(row);
            });
            if (!container.children.length) container.appendChild(node('p', state.workbench || state.authoring ? '暂无' + KINDS[kind] + '记录。' : '正在读取…', 'empty-hint'));
        }
        function characterDraft() {
            if (!state.characterDraft) state.characterDraft = copy(characterProfilesOf(state.authoring));
            return state.characterDraft;
        }
        function renderCharacterCards() {
            const execution = characterExecutionOf(state.authoring);
            const profiles = state.characterDraft || characterProfilesOf(state.authoring);
            const box = el('character-decision-cards'); box.replaceChildren();
            el('character-profile-meta').textContent = execution
                ? '人物策略 ' + execution.settings.policyVersion + ' · 模式 ' + execution.settings.mode
                    + (execution.profiles ? ' · 档案版本 ' + execution.profiles.revision + ' · 第 ' + execution.profiles.effectiveFromChapter + ' 章起生效' : ' · 尚无决策卡，生成时会明确记录降级')
                : '未建立决策卡执行引用；已有事实和人物状态仍按原链路使用。';
            if (!profiles.length) box.appendChild(node('p', '暂无决策卡。可手工添加；显式 APPLY 允许为明确核心人物自动建档。', 'empty-hint'));
            profiles.forEach((profile, index) => {
                const card = node('article', null, 'character-decision-card');
                const header = node('div', null, 'character-card-header'); header.appendChild(node('h4', profile.displayName || '新人物'));
                card.appendChild(header);
                if (!state.characterDraft) {
                    card.appendChild(node('p', '来源：' + (text(profile.source) || '未提供') + ' · 采纳：' + (text(profile.adoption) || '未提供')
                        + ' · ' + (profile.locked ? '已锁定' : '未锁定'), 'character-card-source'));
                    const fields = node('div', null, 'character-card-fields');
                    CHARACTER_FIELDS.forEach(([key, label]) => {
                        const group = node('div'); group.appendChild(node('strong', label)); group.appendChild(node('p', text(profile[key]) || '未设计')); fields.appendChild(group);
                    });
                    card.appendChild(fields);
                    card.appendChild(button('编辑', () => { characterDraft(); touched(); renderCharacterCards(); }));
                } else {
                    const fields = node('div', null, 'character-card-fields');
                    const nameLabel = node('label', '人物姓名'), name = node('input'); name.type = 'text'; name.maxLength = 120;
                    name.id = 'character-name-' + index; nameLabel.htmlFor = name.id; name.value = text(profile.displayName);
                    name.addEventListener('input', () => { profile.displayName = name.value; header.querySelector('h4').textContent = name.value || '新人物'; touched(); });
                    fields.appendChild(nameLabel); fields.appendChild(name);
                    const wasLocked = characterProfilesOf(state.authoring).some(previous => previous.displayName === profile.displayName && previous.locked === true);
                    const lockLabel = node('label', '锁定此卡，自动建档不得覆盖'), lock = node('input'); lock.type = 'checkbox'; lock.checked = profile.locked === true;
                    lockLabel.appendChild(lock); fields.appendChild(lockLabel);
                    lock.addEventListener('change', () => { profile.locked = lock.checked; touched(); renderCharacterCards(); });
                    CHARACTER_FIELDS.forEach(([key, label]) => {
                        const group = node('div'), fieldLabel = node('label', label), input = node('textarea'); input.rows = 3; input.maxLength = 2048;
                        input.id = 'character-' + index + '-' + key; fieldLabel.htmlFor = input.id; input.value = text(profile[key]);
                        input.readOnly = wasLocked && profile.locked === true;
                        input.addEventListener('input', () => { profile[key] = input.value; touched(); });
                        group.appendChild(fieldLabel); group.appendChild(input); fields.appendChild(group);
                    });
                    name.readOnly = wasLocked && profile.locked === true; card.appendChild(fields);
                    if (wasLocked && profile.locked === true) card.appendChild(node('p', '先解除锁定，再修改或移除这张卡。', 'settings-hint'));
                    else card.appendChild(button('移除此卡', () => {
                        if (!ask('移除这张人物决策卡？先预览再保存，不删除正文或人物事实。')) return;
                        state.characterDraft.splice(index, 1); touched(); renderCharacterCards();
                    }));
                }
                box.appendChild(card);
            });
            if (state.characterDraft) box.appendChild(button('取消档案编辑', () => {
                if (!ask('放弃人物决策卡的未保存修改？')) return;
                state.characterDraft = null; touched(); renderCharacterCards();
            }));
            el('character-save-hint').textContent = '保存前先预览差异；手工保存不调用模型、不切换模式。APPLY 自动建档可能增加调用；人工草稿按钮尚未开放。';
            sync();
        }
        function renderAuthoring() {
            const view = state.authoring;
            el('book-authoring-meta').textContent = view ? '维护版本 ' + (view.revision || '未建立') + ' · 生效起点：第 ' + view.effectiveFromChapter
                + ' 章 · 当前激活版本 ' + (view.activeSnapshot ? view.activeSnapshot.revision : '未建立') + ' · 依据 ' + view.basis : '尚未读取有效创作依据，不可保存';
            renderSettings(); renderCharacterCards(); renderCharacterList(); renderSelectedCharacter();
            ['ITEM', 'FACTION', 'FORESHADOW'].forEach(kind => { renderEntities(kind); renderRecords(kind, el('book-' + KIND_IDS[kind] + '-records')); });
        }
        async function loadAuthoring(manual) {
            if (!state.storyDir || busy()) return false;
            if (manual && dirty() && !ask('重新读取会放弃未保存的书级设定、硬事实、人物决策卡、模式和维护输入（正文不受影响）。继续？')) return false;
            const storyDir = state.storyDir, epoch = state.epoch, token = ++state.readRequest, version = state.version;
            state.readState = 'loading'; status('正在读取当前书的创作依据…'); sync();
            try {
                const raw = await request(url('/authoring'));
                if (!current(storyDir, epoch) || token !== state.readRequest) return false;
                if (version !== state.version) { state.readState = 'stale'; status('读取期间已有修改，输入未覆盖。请复制保留内容后重新读取合并。', false); return false; }
                const view = readAuthoring(raw);
                    state.authoring = view;
                state.settings = copy(view.settings); state.mode = modeOf(view); state.characterDraft = null;
                state.readState = 'ready'; state.version++; state.settingsEditing = false; state.factsEditing = false;
                Object.keys(KINDS).forEach(mountEntry); invalidatePreview(); renderAuthoring();
                status('已读取当前书。编辑只保留在本页，预览并明确保存后才改变服务器。', true);
                return true;
            } catch (err) {
                if (!current(storyDir, epoch) || token !== state.readRequest) return false;
                state.readState = 'error'; status('创作管理不可保存：' + err.message + '。当前输入保留，不使用生成表单或其他书数据。', false); return false;
            } finally { if (current(storyDir, epoch) && token === state.readRequest) sync(); }
        }
        async function preview(patch, scope) {
            if (busy() || state.readState !== 'ready' || !state.authoring) return false;
            const storyDir = state.storyDir, epoch = state.epoch, version = state.version;
            const body = {expectedRevision: state.authoring.revision, expectedBasis: state.authoring.basis, ...patch};
            state.busy = true; invalidatePreview(); status('正在核对变更并获取保存预览；未保存、不调用模型。'); sync();
            try {
                const raw = await request(url('/authoring/preview'), {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
                if (!current(storyDir, epoch)) return false;
                if (version !== state.version) throw new Error('输入已修改，旧预览失效，请重新预览。');
                const candidate = readAuthoring(raw, 'preview');
                if (candidate.revision !== body.expectedRevision || candidate.basis !== body.expectedBasis) throw new Error('预览依据已变更，请重新读取核对；输入保留。');
                state.preview = {body, hash: candidate.previewHash, version, scope};
                const box = el('book-authoring-preview-content'); box.replaceChildren();
                previewChanges(state.authoring, candidate).forEach(change => {
                    const row = node('article', null, 'book-preview-change'); row.appendChild(node('h4', (SETTINGS.find(pair => pair[0] === change.field) || [null, change.field === 'characterProfiles' ? '人物硬事实' : change.field])[1]));
                    const grid = node('div', null, 'book-diff');
                    const before = node('div'); before.appendChild(node('b', '保存前')); before.appendChild(node('pre', change.before || '（空）'));
                    const after = node('div'); after.appendChild(node('b', '保存后')); after.appendChild(node('pre', change.after || '（空）'));
                    grid.appendChild(before); grid.appendChild(after); row.appendChild(grid); box.appendChild(row);
                });
                if (!box.children.length) box.appendChild(node('p', '服务器未返回可见内容差异；仍须确认是否保存此版本。'));
                box.appendChild(node('p', '服务器生效边界：第 ' + candidate.effectiveFromChapter + ' 章。既有正文和已启动任务冻结依据不改写。'));
                candidate.warnings.forEach(warning => box.appendChild(node('p', warning, 'verification-warning')));
                const panel = el('book-authoring-preview');
                const previewSlots = {settings: 'book-authoring-settings', facts: 'book-character-basic', profiles: 'book-character-motivation', mode: 'book-run-preview-slot',
                    'entry:CHARACTER': 'book-authoring-characters', 'entry:ITEM': 'book-authoring-items',
                    'entry:FACTION': 'book-authoring-factions', 'entry:FORESHADOW': 'book-authoring-foreshadow'};
                el(previewSlots[scope]).appendChild(panel);
                panel.classList.remove('hidden'); panel.scrollIntoView({block: 'nearest'});
                status('预览已生成，尚未保存。核对差异后点「确认保存」。', true); return true;
            } catch (err) {
                if (current(storyDir, epoch)) { invalidatePreview(); status('预览失败，不可保存：' + err.message + ' 当前输入保留。', false); }
                return false;
            } finally { if (current(storyDir, epoch)) { state.busy = false; sync(); } }
        }
        async function savePreview() {
            const pending = state.preview;
            if (busy() || !pending || pending.version !== state.version) return false;
            const storyDir = state.storyDir, epoch = state.epoch;
            state.busy = true; status('正在保存已确认的维护版本…不调用模型。'); sync();
            try {
                const raw = await request(url('/authoring'), {method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({...pending.body, previewHash: pending.hash})});
                if (!current(storyDir, epoch)) return false;
                const view = readAuthoring(raw, 'save');
                const local = copy(state.settings), localMode = state.mode;
                state.authoring = view; state.readState = 'ready'; state.settings = copy(view.settings);
                if (pending.scope !== 'settings') SETTINGS.forEach(pair => { state.settings[pair[0]] = local[pair[0]]; });
                if (pending.scope !== 'facts') state.settings.characterProfiles = local.characterProfiles;
                state.mode = pending.scope === 'mode' ? modeOf(view) : localMode;
                if (pending.scope === 'settings') state.settingsEditing = false;
                if (pending.scope === 'facts') state.factsEditing = false;
                if (pending.scope === 'profiles') state.characterDraft = null;
                if (pending.scope.startsWith('entry:')) mountEntry(pending.scope.slice(6));
                state.version++; invalidatePreview(); renderAuthoring();
                status('已保存并激活维护版本 ' + view.revision + '，从第 ' + view.effectiveFromChapter + ' 章生效。未调用模型，其他未保存输入仍保留。', true);
                if (options.onSaved) options.onSaved(); return true;
            } catch (err) {
                if (current(storyDir, epoch)) { invalidatePreview(); status('保存未确认：' + err.message + ' 当前输入保留；请重新读取核对版本，不会假装已保存。', false); }
                return false;
            } finally { if (current(storyDir, epoch)) { state.busy = false; sync(); } }
        }
        function issueStatusText(issue) {
            const human = {OPEN: '未暂缓', DEFERRED: '作者暂缓', FALSE_POSITIVE: '作者标记误报'};
            const rechecks = {VERIFIED_RESOLVED: '复检确认解决', STILL_PRESENT: '复检仍存在', RECHECK_UNCONFIRMED: '复检未确认', STALE: '复检依据过期'};
            return (issue.systemResolved ? '系统已解决' : '系统未解决') + ' · ' + (human[issue.disposition.state] || issue.disposition.state)
                + (issue.recheck && issue.recheck.status ? ' · ' + (rechecks[issue.recheck.status] || issue.recheck.status) : '');
        }
        function renderQuality() {
            const box = el('book-quality-issues'); box.replaceChildren();
            if (!state.issues.length) box.appendChild(node('p', state.issuesRead ? '当前接口没有返回质量问题。' : '质量问题待读取，不等于没有问题。', 'empty-hint'));
            state.issues.forEach(issue => {
                const row = node('article', null, 'book-quality-issue'); row.dataset.issueId = issue.id;
                row.appendChild(node('h4', '第 ' + issue.chapterNo + ' 章 · ' + (text(issue.dimension) || '维度未提供') + ' · ' + text(issue.severity)));
                row.appendChild(node('p', issueStatusText(issue), 'character-profile-meta'));
                row.appendChild(node('p', text(issue.description) || '描述未提供', 'book-long-text'));
                row.appendChild(node('blockquote', text(issue.evidence) || '证据未提供'));
                row.appendChild(node('p', '建议：' + (text(issue.suggestion) || '未提供'), 'book-long-text'));
                row.appendChild(node('p', '问题版本凭据：' + issue.sourceHash + ' · 处置版本：' + issue.disposition.revision + '\n已有备注：' + (text(issue.disposition.note) || '无'), 'character-card-source'));
                row.appendChild(button('跳到正文修改', () => options.openChapter(issue.chapterNo, {issueId: issue.id, evidence: text(issue.evidence), description: text(issue.description)})));
                const label = node('label', '备注 / 处置理由'), input = node('textarea'); input.rows = 2; input.id = 'book-issue-note-' + state.issues.indexOf(issue);
                label.htmlFor = input.id; input.value = state.issueNotes[issue.id] || '';
                input.addEventListener('input', () => { state.issueNotes[issue.id] = input.value; sync(); }); row.appendChild(label); row.appendChild(input);
                const actions = node('div', null, 'form-actions');
                [['NOTE', '保存备注'], ['DEFER', '暂缓'], ['FALSE_POSITIVE', '标记误报']].forEach(pair => {
                    const action = button(pair[1], () => actOnIssue(issue.id, pair[0])); action.disabled = busy(); actions.appendChild(action);
                });
                const recheck = button('复检（可能调用模型）', () => actOnIssue(issue.id, 'RECHECK')); recheck.disabled = busy(); actions.appendChild(recheck); row.appendChild(actions);
                if (state.issueStatus[issue.id]) row.appendChild(node('p', state.issueStatus[issue.id], 'book-issue-status'));
                box.appendChild(row);
            });
        }
        async function loadQuality() {
            if (!state.storyDir || busy()) return false;
            const storyDir = state.storyDir, epoch = state.epoch, token = ++state.qualityRequest;
            if (!state.issuesRead) el('book-quality-issues').textContent = '正在读取质量问题…';
            try {
                const raw = await request(url('/quality-issues'));
                if (!current(storyDir, epoch) || token !== state.qualityRequest) return false;
                if (!raw || !Array.isArray(raw.issues)) throw new Error('质量问题列表格式无效');
                const issues = raw.issues.map(readIssue);
                state.issues = issues; state.issuesRead = true; renderQuality(); return true;
            } catch (err) {
                if (current(storyDir, epoch) && token === state.qualityRequest) {
                    if (state.issuesRead) status('问题刷新失败：' + err.message + '。旧问题与本地备注已保留。', false);
                    else el('book-quality-issues').textContent = '问题不可读取或处置：' + err.message + '。不能当作零问题。';
                }
                return false;
            } finally { sync(); }
        }
        async function actOnIssue(id, action) {
            if (busy()) return false;
            const issue = state.issues.find(item => item.id === id);
            if (!issue) return false;
            let body;
            try {
                if (action === 'RECHECK') {
                    if (!ask('明确复检第 ' + issue.chapterNo + ' 章的此问题？可能调用模型并产生成本。只有服务器复检确认才能改变系统解决状态。')) return false;
                    body = {expectedRevision: issue.disposition.revision, expectedSourceHash: issue.sourceHash, confirmModelCall: true};
                } else body = qualityActionBody(issue, action, state.issueNotes[id] || '');
            } catch (err) { state.issueStatus[id] = err.message; renderQuality(); return false; }
            const storyDir = state.storyDir, epoch = state.epoch, submittedNote = state.issueNotes[id];
            state.qualityRequest++; state.qualityBusy = true; state.issueStatus[id] = action === 'RECHECK' ? '复检中，尚未确认解决…' : '正在记录处置，不改变系统解决状态…'; renderQuality(); sync();
            try {
                const raw = await request(url('/quality-issues/' + encodeURIComponent(id) + (action === 'RECHECK' ? '/recheck' : '/actions')),
                    {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
                if (!current(storyDir, epoch)) return false;
                const saved = readQualitySaved(raw, id); state.issues = state.issues.map(item => item.id === id ? saved : item);
                if (action !== 'RECHECK' && state.issueNotes[id] === submittedNote) delete state.issueNotes[id];
                state.issueStatus[id] = action === 'RECHECK' ? '复检结果已读取：' + issueStatusText(saved) : '作者处置已保存，不表示问题已被系统解决。'; return true;
            } catch (err) {
                if (current(storyDir, epoch)) state.issueStatus[id] = '结果未确认：' + err.message + '。本地备注与问题状态保留。';
                return false;
            } finally { if (current(storyDir, epoch)) { state.qualityBusy = false; renderQuality(); sync(); } }
        }
        function updateWorkbench(data) {
            state.workbench = data; state.ledger = Array.isArray(data.characters) ? data.characters : [];
            const observations = el('workbench-character-observation'); observations.replaceChildren();
            const statuses = {OBSERVED: '已绑定原稿的选择与后果摘录', STALE: '观察依据已过期', NOT_EVALUATED: '没有足够的可核对观察', ERROR: '观察未完成'};
            (Array.isArray(data.characterObservations) ? data.characterObservations : []).forEach(observation => {
                const row = node('article', null, 'book-record');
                row.appendChild(node('h4', '第 ' + observation.chapterNo + ' 章 · ' + (statuses[observation.status] || observation.status)));
                row.appendChild(node('p', '这不是文学评分或已发生事实；仅对应所列正文与人物卡版本。', 'settings-hint'));
                (observation.observations || []).forEach(line => row.appendChild(node('p', text(line), 'book-long-text')));
                row.appendChild(node('p', '正文 ' + text(observation.manuscriptHash) + '\n人物卡 ' + text(observation.profileHash), 'character-card-source'));
                observations.appendChild(row);
            });
            if (!observations.children.length) observations.appendChild(node('p', '暂无可核对的独立人物观察，不等于没有问题。', 'empty-hint'));
            if (data.causalityPlanMode || data.causalityAuditMode) observations.appendChild(node('p', '因果核验：规划 ' + text(data.causalityPlanMode)
                    + ' · 审校 ' + text(data.causalityAuditMode) + '；与人物 APPLY 独立。', 'settings-hint'));
            renderCharacterList(); renderSelectedCharacter();
            ['ITEM', 'FACTION', 'FORESHADOW'].forEach(renderEntities);
            const pending = el('book-pending-facts'); pending.replaceChildren();
            (data.pendingFacts || []).forEach(entry => {
                const row = node('article', null, 'book-record'); row.appendChild(node('h4', text(entry.name)));
                row.appendChild(node('p', text(entry.status), 'book-long-text')); row.appendChild(node('blockquote', text(entry.evidence) || '证据未通过或未提供'));
                pending.appendChild(row);
            });
            if (!pending.children.length) pending.appendChild(node('p', '暂无未入账事实。', 'empty-hint'));
        }
        function open(storyDir) {
            state.epoch++; state.version++; state.readRequest++; state.qualityRequest++; state.historyRequest++;
            Object.assign(state, {storyDir, authoring: null, readState: 'empty', settings: readSettings({}), mode: '',
                settingsEditing: false, factsEditing: false, selectedCharacter: '', cards: [], ledger: [],
                issues: [], issuesRead: false, issueNotes: Object.create(null), issueStatus: Object.create(null), qualityBusy: false, busy: false, history: Object.create(null), workbench: null, characterDraft: null});
            invalidatePreview(); Object.keys(KINDS).forEach(mountEntry); renderAuthoring(); renderQuality();
            el('workbench-character-observation').textContent = '深度建议待读取；不代表没有问题。';
            el('book-current-mode').textContent = '当前执行模式待读取';
            ['workbench-items', 'workbench-factions', 'workbench-foreshadow', 'book-pending-facts'].forEach(id => { el(id).textContent = '正在读取…'; });
            el('book-workspace').classList.toggle('hidden', !storyDir);
            selectTab('chapters'); selectAuthoringTab('settings'); selectCharacterTab('basic');
            if (storyDir) return loadAuthoring(false);
            return Promise.resolve(false);
        }
        mountSettings(); Object.keys(KINDS).forEach(mountEntry);
        doc.querySelectorAll('[data-book-tab]').forEach(item => item.addEventListener('click', () => selectTab(item.dataset.bookTab)));
        doc.querySelectorAll('[data-authoring-tab]').forEach(item => item.addEventListener('click', () => selectAuthoringTab(item.dataset.authoringTab)));
        doc.querySelectorAll('[data-character-tab]').forEach(item => item.addEventListener('click', () => selectCharacterTab(item.dataset.characterTab)));
        el('btn-reload-authoring').addEventListener('click', () => loadAuthoring(true));
        el('btn-edit-book-settings').addEventListener('click', () => { if (busy() || state.readState !== 'ready') return; state.settingsEditing = true; renderSettings(); });
        el('btn-edit-book-facts').addEventListener('click', () => { if (busy() || state.readState !== 'ready') return; state.factsEditing = true; renderSettings(); });
        el('book-character-facts').addEventListener('input', () => { state.settings.characterProfiles = el('book-character-facts').value; touched(); });
        el('book-character-depth-mode').addEventListener('change', () => { state.mode = el('book-character-depth-mode').value; touched(); });
        el('btn-cancel-book-settings').addEventListener('click', () => {
            if (busy() || (settingsDirty() && !ask('放弃故事设定的未保存修改？人物硬事实不受影响。'))) return;
            SETTINGS.forEach(pair => { state.settings[pair[0]] = state.authoring.settings[pair[0]]; }); state.settingsEditing = false; touched(); renderSettings();
        });
        el('btn-cancel-book-facts').addEventListener('click', () => {
            if (busy() || (state.settings.characterProfiles !== state.authoring.settings.characterProfiles && !ask('放弃人物硬事实的未保存修改？'))) return;
            state.settings.characterProfiles = state.authoring.settings.characterProfiles; state.factsEditing = false; touched(); renderSettings();
        });
        el('btn-preview-book-settings').addEventListener('click', () => {
            const settings = copy(state.authoring.settings); SETTINGS.forEach(pair => { settings[pair[0]] = state.settings[pair[0]]; }); preview({settings}, 'settings');
        });
        el('btn-preview-book-facts').addEventListener('click', () => preview({settings: {...state.authoring.settings, characterProfiles: state.settings.characterProfiles}}, 'facts'));
        el('btn-add-character-card').addEventListener('click', () => {
            if (busy() || state.readState !== 'ready') return;
            characterDraft().push({displayName: '', locked: false}); touched(); selectCharacterTab('motivation'); renderCharacterCards();
        });
        el('btn-save-character-cards').addEventListener('click', () => {
            if (!state.characterDraft || busy()) return;
            try { preview({characterProfiles: editableCharacterProfiles(state.characterDraft)}, 'profiles'); }
            catch (err) { status(err.message, false); }
        });
        el('btn-reload-character-cards').addEventListener('click', () => loadAuthoring(true));
        el('btn-preview-book-mode').addEventListener('click', () => {
            if (!['OFF', 'OBSERVE', 'APPLY'].includes(state.mode)) { status('请选择明确的模式后再预览。', false); return; }
            preview({characterDepthMode: state.mode}, 'mode');
        });
        el('btn-confirm-book-authoring').addEventListener('click', savePreview);
        el('btn-cancel-book-preview').addEventListener('click', () => { if (!busy()) { invalidatePreview(); sync(); } });
        el('btn-refresh-quality-issues').addEventListener('click', loadQuality);
        return {state, open, selectTab, selectAuthoringTab, selectCharacterTab, selectCharacter,
            isDirty: dirty, isBusy: busy, sync, loadAuthoring, loadQuality, preview, savePreview, actOnIssue, updateWorkbench,
            setCharacters(cards) { state.cards = cards; renderCharacterList(); renderSelectedCharacter(); sync(); },
            draftSettings() { if (state.readState !== 'ready' || !state.authoring) throw new Error('请先读取当前书的创作设定；不会使用生成表单。'); return copy(state.settings); },
            onCharacterSaved() { invalidatePreview(); if (!dirty()) loadAuthoring(false); },
            canLeave(message) { if (busy()) { notify('保存或处置请求中，请等待结果，不切换上下文。'); return false; } return !(dirty() || (options.isExtraDirty && options.isExtraDirty())) || ask(message || '离开当前书将丢弃未保存的创作管理输入，继续？'); }
        };
    }

    return {SETTINGS, SETTING_KEYS, KINDS, TABS, AUTHORING_TABS, readSettings, readAuthoring, readSnapshot, entriesOf, sourcesOf,
        modeOf, characterNames, mergeEntry, makeEntry, previewChanges, readIssue, qualityActionBody, readQualitySaved,
        CHARACTER_FIELDS, editableCharacterProfiles, characterExecutionOf, characterProfilesOf, readCharacterExecution, create};
}));
