(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && module.exports) module.exports = factory();
    else root.ReferenceIndexManager = factory();
}(typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const BASE = '/api/reference-index';
    const ACTIONS = { disable: '停用资料', enable: '恢复资料', delete: '删除集合', rebuild: '重建集合' };
    const KINDS = { reference: '参考资料', story: '小说私有记忆', world: '世界记忆' };
    const STATES = { current: '当前', historical: '历史', legacy: '旧版', orphan: '无源集合', error: '错误', unknown: '未知' };
    const TASKS = { queued: '排队中', running: '执行中', completed: '已完成', failed: '失败' };
    const RESULTS = { completed: '已完成', failed: '失败', skipped: '跳过' };
    const NOTES = {
        disable: '停用资料会停止参与检索，并清理该资料在全部参考版本中的索引分块；原文保留。',
        enable: '恢复资料参与检索；是否需要嵌入调用以本次服务端预览为准。',
        delete: '仅删除集合的向量索引，不删除小说正文、摘要、story-bible（设定集）或参考资料原文，也不改变资料启用状态。仍有来源的索引可能在后续构建时再次生成。',
        rebuild: '按当前可用来源重建索引，可能调用 Embedding 并产生费用，不改写正文、摘要、story-bible 或参考资料原文。历史 reference 重建是按当前启用来源重建当前索引，并非还原历史；同一当前索引由服务端去重。'
    };
    const text = value => value == null ? '' : String(value);
    const count = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
    const version = value => typeof value === 'string' && value.length > 0 || count(value);
    const identifier = value => typeof value === 'string' && value.trim().length > 0;
    const has = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
    const collectionAction = action => action === 'delete' || action === 'rebuild';
    const permitted = (collection, action) => !!collection && has(KINDS, collection.kind)
        && collection[action === 'delete' ? 'canDelete' : 'canRebuild'] === true
        && !(action === 'rebuild' && collection.state === 'orphan');
    const selectable = collection => permitted(collection, 'delete') || permitted(collection, 'rebuild');
    const activeTask = task => !!task && (task.status === 'queued' || task.status === 'running');
    const incomplete = results => results.some(item => item.status !== 'completed');
    const numberLabel = value => count(value) ? value.toLocaleString('zh-CN') : '未知';
    const ownerName = owner => (owner.novelTitle || owner.storyDirName) + ' [' + owner.storyDirName + ']';
    function ownerLabel(collection) {
        const names = collection.owners.map(ownerName).join('、');
        if (collection.kind === 'reference') return '参考资料 · 全局共享' + (names ? ' · ' + names : '（无所属小说）');
        if (collection.kind === 'world') return (collection.owners.length > 1 ? '共享世界 · ' : '世界记忆 · ') + (names || '无当前所属小说');
        if (collection.kind === 'story') return '小说私有 · ' + (names || '无当前所属小说');
        return '未知类型 · ' + (names || '无所属小说信息');
    }
    function readResults(value) {
        if (value === undefined) return [];
        if (!Array.isArray(value)) throw new Error('逐项执行结果无效，未确认全部完成');
        const ids = new Set();
        return value.map(item => {
            if (!item || !identifier(item.id) || ids.has(item.id) || typeof item.name !== 'string'
                    || !has(RESULTS, item.status) || typeof item.message !== 'string') throw new Error('逐项执行结果不完整');
            ids.add(item.id);
            return { id: item.id, name: item.name, status: item.status, message: item.message };
        });
    }
    function readTask(value, expectedId) {
        if (!value || !identifier(value.id) || !has(TASKS, value.status)
                || expectedId && value.id !== expectedId) throw new Error('任务状态响应无效，未确认完成');
        return { id: value.id, status: value.status, message: text(value.message), results: readResults(value.results) };
    }
    function readSummary(value) {
        if (!value || !version(value.version) || typeof value.busy !== 'boolean'
                || !Array.isArray(value.collections) || !Array.isArray(value.sources)
                || !has(value, 'task')) throw new Error('索引状态响应不完整');
        const collectionIds = new Set(), sourceIds = new Set();
        value.collections.forEach(item => {
            if (!item || !identifier(item.id) || collectionIds.has(item.id) || !Array.isArray(item.owners)
                    || typeof item.canDelete !== 'boolean' || typeof item.canRebuild !== 'boolean'
                    || item.owners.some(owner => !owner || !identifier(owner.storyDirName) || typeof owner.novelTitle !== 'string')) {
                throw new Error('集合逻辑标识、所属小说或权限响应无效');
            }
            collectionIds.add(item.id);
        });
        value.sources.forEach(item => {
            if (!item || !identifier(item.id) || sourceIds.has(item.id) || typeof item.enabled !== 'boolean'
                    || !Array.isArray(item.collections)) throw new Error('资料源状态响应无效');
            sourceIds.add(item.id);
        });
        if (value.task !== null) readTask(value.task);
        return value;
    }
    function readPreview(value, action, data) {
        if (!value || !identifier(value.token) || value.action !== action || !version(value.version)
                || text(value.version) !== text(data.version) || typeof value.title !== 'string' || typeof value.message !== 'string'
                || !Array.isArray(value.collections) || !Array.isArray(value.sources)
                || !count(value.embeddingChars) || !count(value.embeddingBatches)
                || collectionAction(action) && !value.collections.length) {
            throw new Error('操作预览缺少凭据、影响或成本信息，或索引版本已变化，未执行');
        }
        const ids = new Set(), sourceIds = new Set();
        value.collections.forEach(item => {
            if (!item || !identifier(item.id) || ids.has(item.id)
                    || !(count(item.pointCount) || item.pointCount === null || item.pointCount === -1)) throw new Error('预览集合信息无效，未执行');
            ids.add(item.id);
            const known = data.collections.find(collection => collection.id === item.id);
            if (!known) throw new Error('预览包含未知集合逻辑 ID，拒绝执行');
            if (collectionAction(action) ? !permitted(known, action) : known.kind !== 'reference') {
                throw new Error('预览包含无相应操作权限的集合，拒绝执行');
            }
        });
        value.sources.forEach(item => {
            if (!item || !identifier(item.id) || sourceIds.has(item.id) || !data.sources.some(source => source.id === item.id)) {
                throw new Error('预览资料源信息无效或未知，未执行');
            }
            sourceIds.add(item.id);
        });
        return value;
    }

    function create(options) {
        const doc = options.document, request = options.fetchJson;
        const ask = options.confirm || function () { return false; };
        const notify = options.notify || function () {};
        const el = id => doc.getElementById(id);
        const root = el('reference-index-manager');
        if (!root || typeof request !== 'function') throw new Error('索引管理容器或请求接线缺失');
        const node = (tag, value, className) => {
            const item = doc.createElement(tag);
            if (value != null) item.textContent = text(value);
            if (className) item.className = className;
            return item;
        };
        const button = (label, handler) => {
            const item = node('button', label, 'btn-secondary');
            item.type = 'button'; item.addEventListener('click', handler); return item;
        };
        const state = { data: null, ready: false, loading: false, acting: false, preview: null, task: null, results: null,
            taskPollError: '', selected: new Set(), kind: '', owner: '', collectionId: null,
            cursors: [''], page: 0, nextCursor: null, pointsLoading: false };
        let collectionButtons = [], sourceButtons = [], rowChecks = [], previewButtons = [], visibleCheck = null;
        let refreshPromise = null, pointsEpoch = 0, pollEpoch = 0, previewEpoch = 0;
        let pollTimer = null, pollId = null, pollInFlight = false;
        const locked = () => !state.ready || state.loading || state.acting || !!(state.data && state.data.busy) || activeTask(state.task);
        const visibleCollections = () => state.data ? state.data.collections.filter(item => (!state.kind || item.kind === state.kind)
            && (!state.owner || state.owner === 'reference' && item.kind === 'reference' && !item.owners.length
                || state.owner.startsWith('novel:') && item.owners.some(owner => 'novel:' + owner.storyDirName === state.owner))) : [];
        const selectedCollections = () => state.data ? state.data.collections.filter(item => state.selected.has(item.id)) : [];
        const batchAllowed = action => {
            const selected = selectedCollections();
            return selected.length > 0 && selected.length <= 100 && selected.every(item => permitted(item, action));
        };
        function status(message, type) {
            const box = el('reference-index-status');
            box.textContent = message || '';
            box.classList.toggle('hidden', !message);
            box.classList.toggle('is-error', type === 'error');
            box.classList.toggle('is-success', type === 'success');
        }
        function sync() {
            root.setAttribute('aria-busy', String(state.loading || state.acting || activeTask(state.task) || !!(state.data && state.data.busy)));
            el('btn-refresh-reference-index').disabled = state.loading || state.acting;
            el('btn-delete-reference-index').disabled = locked() || !batchAllowed('delete');
            el('btn-rebuild-reference-index').disabled = locked() || !batchAllowed('rebuild');
            collectionButtons.forEach(item => { item.button.disabled = locked() || !permitted(item.collection, item.action); });
            sourceButtons.forEach(item => { item.disabled = locked(); });
            previewButtons.forEach(item => { item.disabled = locked(); });
            const selectionLocked = !state.ready || state.loading || state.acting;
            rowChecks.forEach(item => {
                item.input.checked = state.selected.has(item.collection.id);
                item.input.disabled = selectionLocked || !selectable(item.collection);
                item.row.classList.toggle('is-selected', item.input.checked);
            });
            const visible = visibleCollections().filter(selectable), selectedVisible = visible.filter(item => state.selected.has(item.id)).length;
            if (visibleCheck) {
                visibleCheck.checked = visible.length > 0 && selectedVisible === visible.length;
                visibleCheck.indeterminate = selectedVisible > 0 && selectedVisible < visible.length;
                visibleCheck.disabled = selectionLocked || !visible.length;
            }
            el('btn-reference-select-visible').disabled = selectionLocked || !visible.length;
            el('btn-reference-clear-selection').disabled = selectionLocked || !state.selected.size;
            el('reference-index-kind-filter').disabled = selectionLocked;
            el('reference-index-owner-filter').disabled = selectionLocked;
            el('reference-index-selection').textContent = '已选择 ' + state.selected.size + ' 个 · 当前可见 ' + visibleCollections().length
                + ' 个 · 每批最多 100 个' + (state.selected.size > 100 ? '；请减少选择后再预览。' : '')
                + (state.selected.size && !batchAllowed('rebuild') ? '；部分选择不可重建。' : '');
            el('reference-index-inspect-collection').disabled = !state.ready || state.loading;
            el('btn-read-reference-points').disabled = !state.ready || state.loading || !el('reference-index-inspect-collection').value;
            el('btn-reference-points-prev').disabled = !state.ready || state.loading || state.pointsLoading || state.page === 0;
            el('btn-reference-points-next').disabled = !state.ready || state.loading || state.pointsLoading || state.nextCursor === null;
        }
        function discardPreview() {
            ++previewEpoch; state.preview = null; previewButtons = [];
            el('reference-index-preview').textContent = '';
            el('reference-index-preview').classList.add('hidden');
        }
        function renderItems(box, results) {
            if (!results.length) return;
            const list = node('ul', null, 'reference-index-result-list');
            results.forEach(item => list.appendChild(node('li', (item.name || item.id) + ' [' + item.id + '] · '
                + RESULTS[item.status] + '：' + (item.message || '无补充说明'), item.status === 'completed' ? '' : 'reference-index-error')));
            box.appendChild(list);
        }
        function renderResults() {
            const box = el('reference-index-results'); box.textContent = ''; box.classList.toggle('hidden', !state.results);
            if (!state.results) return;
            box.classList.toggle('is-error', state.results.failed); box.classList.toggle('is-success', !state.results.failed);
            box.appendChild(node('h3', state.results.failed ? '执行结果 · 未全部完成' : '执行结果 · 已完成'));
            box.appendChild(node('p', state.results.message)); renderItems(box, state.results.items);
        }
        function renderTask() {
            const box = el('reference-index-task'); box.textContent = ''; box.classList.toggle('hidden', !state.task);
            const partial = !!state.task && (incomplete(state.task.results) || state.task.status === 'completed' && !state.task.results.length);
            box.classList.toggle('is-error', !!state.task && state.task.status === 'failed' || partial || !!state.taskPollError);
            if (!state.task) return;
            box.appendChild(node('strong', '集合索引任务 · ' + (state.task.status === 'completed' && partial ? '未全部完成' : TASKS[state.task.status])));
            box.appendChild(node('p', '任务 ' + state.task.id + ' · ' + (state.task.message || '暂无任务说明')));
            renderItems(box, state.task.results);
            if (state.taskPollError) box.appendChild(node('p', '状态查询失败：' + state.taskPollError + '。尚未确认完成，请刷新状态恢复查询。'));
        }
        function stopPolling() {
            ++pollEpoch;
            if (pollTimer !== null) clearTimeout(pollTimer);
            pollTimer = null; pollId = null; pollInFlight = false;
        }
        function watchTask() {
            if (!activeTask(state.task)) { stopPolling(); return; }
            if (pollId !== state.task.id) { stopPolling(); pollId = state.task.id; }
            if (pollTimer === null && !pollInFlight) schedulePoll(0);
        }
        function resultCount(results) {
            return '完成 ' + results.filter(item => item.status === 'completed').length + ' 项，失败 '
                + results.filter(item => item.status === 'failed').length + ' 项，跳过 ' + results.filter(item => item.status === 'skipped').length + ' 项';
        }
        function schedulePoll(delay) {
            const id = pollId, epoch = pollEpoch;
            pollTimer = setTimeout(function () {
                pollTimer = null;
                if (epoch !== pollEpoch || id !== pollId) return;
                pollInFlight = true;
                Promise.resolve().then(() => request(BASE + '/tasks/' + encodeURIComponent(id)))
                    .then(value => {
                        if (epoch !== pollEpoch || id !== pollId) return;
                        state.task = readTask(value, id); state.taskPollError = ''; pollInFlight = false;
                        renderTask(); sync();
                        if (activeTask(state.task)) { schedulePoll(1500); return; }
                        stopPolling();
                        const failed = state.task.status === 'failed' || !state.task.results.length || incomplete(state.task.results);
                        const message = (failed ? '索引任务未全部完成：' : '索引任务已完成：') + (state.task.message || id)
                            + (state.task.results.length ? ' · ' + resultCount(state.task.results) : '');
                        status(message, failed ? 'error' : 'success'); notify(message); refresh();
                    })
                    .catch(error => {
                        if (epoch !== pollEpoch || id !== pollId) return;
                        pollInFlight = false; state.taskPollError = text(error.message || error);
                        renderTask(); sync(); status('任务状态查询失败；未确认完成，变更仍锁定。请刷新状态继续查询。', 'error');
                    });
            }, delay);
        }
        function selectVisible(checked) {
            if (!state.ready || state.loading || state.acting) return;
            visibleCollections().filter(selectable).forEach(item => state.selected[checked ? 'add' : 'delete'](item.id));
            discardPreview(); sync();
        }
        function addOption(select, label, value) {
            const option = node('option', label); option.value = value; select.appendChild(option);
        }
        function renderFilters() {
            const owners = new Map(), filter = el('reference-index-owner-filter'), inspect = el('reference-index-inspect-collection');
            state.data.collections.forEach(item => item.owners.forEach(owner => owners.set(owner.storyDirName, owner)));
            filter.textContent = ''; addOption(filter, '全部小说 / 参考资料', ''); addOption(filter, '全局参考资料（无所属小说）', 'reference');
            [...owners.values()].sort((a, b) => ownerName(a).localeCompare(ownerName(b), 'zh-CN'))
                .forEach(owner => addOption(filter, ownerName(owner), 'novel:' + owner.storyDirName));
            if (state.owner && state.owner !== 'reference' && ![...owners.keys()].some(id => 'novel:' + id === state.owner)) state.owner = '';
            filter.value = state.owner; el('reference-index-kind-filter').value = state.kind;
            const prior = inspect.value; inspect.textContent = ''; addOption(inspect, '选择集合，只读查看分块', '');
            state.data.collections.forEach(item => addOption(inspect, (item.name || item.id) + ' · ' + ownerLabel(item), item.id));
            inspect.value = state.data.collections.some(item => item.id === prior) ? prior : '';
        }
        function renderCollections() {
            const box = el('reference-index-collections'); box.textContent = ''; collectionButtons = []; rowChecks = [];
            const table = node('table', null, 'reference-index-table'), head = node('thead'), row = node('tr');
            const selectHead = node('th'); selectHead.setAttribute('scope', 'col'); visibleCheck = node('input');
            visibleCheck.type = 'checkbox'; visibleCheck.setAttribute('aria-label', '可见集合全选 / 取消');
            visibleCheck.addEventListener('change', () => selectVisible(visibleCheck.checked)); selectHead.appendChild(visibleCheck); row.appendChild(selectHead);
            ['集合名称 / 逻辑 ID', '所属小说 / reference / 共享 world', '维度', '分块数', '状态', '操作'].forEach(label => {
                const cell = node('th', label); cell.setAttribute('scope', 'col'); row.appendChild(cell);
            });
            head.appendChild(row); table.appendChild(head); const body = node('tbody');
            visibleCollections().forEach(collection => {
                const line = node('tr'); line.setAttribute('data-collection-id', collection.id);
                const checkCell = node('td'), check = node('input'); check.type = 'checkbox';
                check.setAttribute('aria-label', '选择集合 ' + (collection.name || collection.id));
                check.addEventListener('change', () => {
                    if (!state.ready || state.loading || state.acting || !selectable(collection)) return;
                    state.selected[check.checked ? 'add' : 'delete'](collection.id); discardPreview(); sync();
                });
                checkCell.appendChild(check); line.appendChild(checkCell); rowChecks.push({ input: check, collection, row: line });
                const name = node('td'); name.appendChild(node('strong', collection.name || collection.id, 'reference-index-name'));
                name.appendChild(node('p', collection.id, 'reference-index-meta')); line.appendChild(name);
                const owner = node('td', ownerLabel(collection), 'reference-index-owner');
                owner.appendChild(node('p', KINDS[collection.kind] || '未知类型（不可操作）', 'reference-index-meta')); line.appendChild(owner);
                line.appendChild(node('td', numberLabel(collection.dimensions), 'reference-index-number'));
                line.appendChild(node('td', numberLabel(collection.pointCount), 'reference-index-number'));
                const stateCell = node('td', STATES[collection.state] || STATES.unknown);
                if (collection.error) stateCell.appendChild(node('p', collection.error, 'reference-index-error'));
                if (collection.state === 'orphan') stateCell.appendChild(node('p', '无当前来源，不可重建', 'reference-index-meta'));
                line.appendChild(stateCell);
                const actions = node('td'), actionsBox = node('div', null, 'reference-index-row-actions');
                ['delete', 'rebuild'].forEach(action => {
                    const item = button('预览' + ACTIONS[action], () => previewAction(action, { collectionIds: [collection.id] }));
                    item.title = permitted(collection, action) ? '先查看影响范围与成本，再确认' : '服务端未授予此操作权限或无可重建来源';
                    collectionButtons.push({ button: item, collection, action }); actionsBox.appendChild(item);
                });
                actions.appendChild(actionsBox); line.appendChild(actions); body.appendChild(line);
            });
            table.appendChild(body); box.appendChild(table);
            if (!body.children.length) box.appendChild(node('p', state.data.collections.length ? '当前筛选下没有集合。' : '暂无索引集合。', 'empty-hint'));
        }
        function renderSources() {
            const box = el('reference-index-sources'); box.textContent = ''; sourceButtons = [];
            state.data.sources.forEach(source => {
                const card = node('article', null, 'reference-index-entry'); card.appendChild(node('h4', source.title || source.id));
                card.appendChild(node('p', (source.builtin ? '内置' : '用户资料') + ' · ' + (source.group || '未分组')
                    + ' · ' + (source.enabled ? '已启用' : '已停用') + ' · 分块：' + numberLabel(source.blockCount), 'reference-index-meta'));
                card.appendChild(node('p', '逻辑 ID：' + source.id, 'reference-index-meta'));
                card.appendChild(node('p', '所在集合：' + (source.collections.map(id => {
                    const collection = state.data.collections.find(item => item.id === id);
                    return collection ? collection.name || collection.id : text(id);
                }).join('、') || '暂无'), 'reference-index-meta'));
                const actions = node('div', null, 'reference-index-actions');
                const item = button(source.enabled ? '预览停用' : '预览恢复', () => previewAction(source.enabled ? 'disable' : 'enable', { sourceId: source.id }));
                sourceButtons.push(item); actions.appendChild(item); card.appendChild(actions); box.appendChild(card);
            });
            if (!state.data.sources.length) box.appendChild(node('p', '暂无资料源。', 'empty-hint'));
        }
        function refresh() {
            if (state.acting) return Promise.resolve(false);
            if (refreshPromise) return refreshPromise;
            state.loading = true; ++pointsEpoch; state.pointsLoading = false; discardPreview(); sync();
            refreshPromise = Promise.resolve().then(() => request(BASE))
                .then(value => {
                    state.data = readSummary(value); state.ready = true;
                    const before = state.selected.size;
                    state.selected.forEach(id => { if (!value.collections.some(item => item.id === id && selectable(item))) state.selected.delete(id); });
                    el('reference-index-summary').textContent = '后端：' + (text(value.backend) || '未知') + ' · 索引版本：'
                        + text(value.version) + ' · ' + value.collections.length + ' 个集合 · ' + value.sources.length + ' 份辅助资料源'
                        + (value.busy ? '\n当前忙碌：' + (text(value.busyReason) || '索引操作中') + '；变更已锁定，仍可筛选和只读查看。' : '\n集合操作均先预览，再确认；不会自动重建。')
                        + (before !== state.selected.size ? '\n已取消 ' + (before - state.selected.size) + ' 个失效或无权限的选择。' : '');
                    renderFilters(); renderCollections(); renderSources(); renderResults();
                    // task=null 不能将尚未结束的本地任务冒充完成；迟到快照也不能倒退同一任务的终态。
                    if (value.task !== null) {
                        const task = readTask(value.task);
                        if (!state.task || state.task.id !== task.id || activeTask(state.task) || !activeTask(task)) {
                            state.task = task; state.taskPollError = '';
                        }
                    }
                    renderTask(); watchTask();
                    if (state.collectionId) {
                        if (value.collections.some(item => item.id === state.collectionId)) selectCollection(state.collectionId);
                        else clearPoints();
                    }
                    return true;
                })
                .catch(error => {
                    state.ready = false;
                    el('reference-index-summary').textContent = '索引状态不可用；所有变更已锁定，请刷新重试。';
                    status('读取资料索引失败：' + text(error.message || error) + '。未执行任何变更。', 'error'); return false;
                })
                .finally(() => { state.loading = false; refreshPromise = null; sync(); });
            return refreshPromise;
        }
        function actionBody(action, body) {
            if (!has(ACTIONS, action)) throw new Error('不支持的集合操作');
            if (collectionAction(action)) {
                if (!body || !Array.isArray(body.collectionIds) || !body.collectionIds.length || body.collectionIds.length > 100
                        || new Set(body.collectionIds).size !== body.collectionIds.length || body.collectionIds.some(id => !identifier(id)
                            || !permitted(state.data.collections.find(item => item.id === id), action))) {
                    throw new Error('每批只能选择 1–100 个已知且有相应权限的集合');
                }
                return { collectionIds: body.collectionIds.slice() };
            }
            const source = state.data.sources.find(item => body && item.id === body.sourceId);
            if (!source || source.enabled !== (action === 'disable')) throw new Error('资料源未知或启用状态已变化');
            return { sourceId: source.id };
        }
        function previewLines(value) {
            const known = id => state.data.collections.find(item => item.id === id);
            const selected = (value.requestedIds || []).map(known).filter(Boolean);
            const affected = value.collections.map(item => known(item.id));
            return [value.title, value.message, NOTES[value.action], '索引版本：' + text(value.version),
                '本次选择：' + (selected.map(item => (item.name || item.id) + ' [' + item.id + '] · ' + ownerLabel(item)).join('\n') || '辅助资料源操作'),
                '影响集合：\n' + (value.collections.map(item => {
                    const collection = known(item.id);
                    return (collection.name || collection.id) + ' [' + item.id + '] · ' + ownerLabel(collection) + ' · ' + numberLabel(item.pointCount) + ' 块';
                }).join('\n') || '无'),
                '影响范围：\n' + (affected.map(item => item.kind === 'world' ? '世界索引影响全部所属小说：' + ownerLabel(item)
                    : item.kind === 'story' ? '小说记忆索引：' + ownerLabel(item) : '全局参考资料检索及使用该索引的小说；原文保留。').join('\n') || '以服务端说明为准'),
                '影响资料：' + (value.sources.map(item => {
                    const source = state.data.sources.find(source => source.id === item.id);
                    return (source.title || source.id) + ' [' + item.id + ']';
                }).join('；') || '无'),
                '预计 Embedding：' + numberLabel(value.embeddingChars) + ' 字符，' + numberLabel(value.embeddingBatches) + ' 批调用。',
                value.embeddingChars || value.embeddingBatches ? '可能产生费用，实际费用按嵌入服务商计费；这里只提供调用规模估算。' : '本次估算无需嵌入调用；仅修改索引或检索开关。'];
        }
        function renderPreview() {
            const box = el('reference-index-preview'), value = state.preview;
            box.textContent = ''; box.classList.remove('hidden'); previewButtons = [];
            box.appendChild(node('h3', value.title || ACTIONS[value.action]));
            previewLines(value).slice(1).forEach(line => box.appendChild(node('p', line)));
            const actions = node('div', null, 'reference-index-actions');
            const execute = button('确认并执行', confirmPreview); execute.id = 'btn-confirm-reference-index';
            const cancel = button('取消预览', () => { if (!state.acting) { discardPreview(); status('已取消预览，未执行变更。'); sync(); } });
            cancel.id = 'btn-cancel-reference-index'; previewButtons.push(execute, cancel);
            actions.appendChild(execute); actions.appendChild(cancel); box.appendChild(actions);
        }
        function previewAction(action, body) {
            if (locked()) return Promise.resolve(false);
            let payload;
            try { payload = actionBody(action, body); }
            catch (error) { discardPreview(); status(text(error.message), 'error'); sync(); return Promise.resolve(false); }
            state.acting = true; discardPreview(); const epoch = previewEpoch, snapshot = state.data.version;
            status('正在读取操作影响与成本…'); sync();
            return Promise.resolve().then(() => request(BASE + '/preview', {
                method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action, ...payload })
            })).then(value => {
                if (epoch !== previewEpoch || text(snapshot) !== text(state.data.version)) return false;
                state.preview = { ...readPreview(value, action, state.data), requestedIds: payload.collectionIds || [], requestBody: payload };
                renderPreview(); status('请检查全部所属小说、影响范围与预计成本，再点击「确认并执行」。'); return true;
            }).catch(error => {
                if (epoch === previewEpoch) { discardPreview(); status('预览失败：' + text(error.message || error) + '。未执行变更。', 'error'); }
                return false;
            }).finally(() => { state.acting = false; sync(); });
        }
        function confirmPreview() {
            if (locked() || !state.preview) return Promise.resolve(false);
            const preview = state.preview;
            try { actionBody(preview.action, preview.requestBody); readPreview(preview, preview.action, state.data); }
            catch (error) { discardPreview(); status('预览已失效：' + text(error.message), 'error'); sync(); return Promise.resolve(false); }
            state.acting = true; sync(); let needsRefresh = false;
            return Promise.resolve().then(() => ask(previewLines(preview).join('\n\n') + '\n\n确认执行以上操作？'))
                .then(accepted => {
                    if (!accepted) { discardPreview(); status('已取消，未执行任何变更。'); return false; }
                    // 一次性 token：超时或部分失败也不复用；写请求仅发送预览凭据。
                    discardPreview(); state.results = null; state.task = null; stopPolling(); renderTask(); renderResults();
                    status('操作已提交，等待服务端逐项确认结果…');
                    return request(BASE + '/operations', {
                        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: preview.token })
                    }).then(result => {
                        needsRefresh = true;
                        if (!result || typeof result.completed !== 'boolean') throw new Error('服务端未确认操作完成，请刷新状态后重新预览');
                        const items = readResults(result.results);
                        if (preview.action === 'rebuild' && result.completed === false && identifier(result.taskId)) {
                            state.task = { id: result.taskId, status: 'queued', message: text(result.message) || '重建已受理，尚未完成', results: items };
                            state.taskPollError = ''; renderTask(); watchTask(); needsRefresh = false;
                            status('重建任务已受理，尚未完成。可只读查看进度；刷新页面后进入「索引管理」恢复查询。'); return true;
                        }
                        // 多个 reference 重建落到同一个当前索引，服务端可能只返回实际构建目标一项。
                        const referenceRebuilt = preview.action === 'rebuild' && items.some(item => state.data.collections.some(collection => collection.id === item.id && collection.kind === 'reference'));
                        const covered = !collectionAction(preview.action) || preview.collections.every(collection => items.some(item => item.id === collection.id)
                            || referenceRebuilt && state.data.collections.some(item => item.id === collection.id && item.kind === 'reference'));
                        const failed = result.completed !== true || incomplete(items) || !covered || result.status === 'failed' || result.status === 'partial';
                        if (failed && !items.length) throw new Error('服务端未提供完整逐项结果，未确认操作完成');
                        const message = (failed ? '操作未全部完成：' : '操作已完成：') + (text(result.message) || ACTIONS[preview.action])
                            + (items.length ? ' · ' + resultCount(items) : '') + (!covered ? '；部分影响集合缺少执行结果。' : '');
                        state.results = { failed, message, items }; renderResults(); status(message, failed ? 'error' : 'success'); notify(message);
                        return !failed;
                    });
                }).catch(error => {
                    discardPreview(); status('操作未确认完成：' + text(error.message || error), 'error'); return false;
                }).finally(() => { state.acting = false; sync(); if (needsRefresh) refresh(); });
        }
        function clearPoints() {
            ++pointsEpoch; state.collectionId = null; state.cursors = ['']; state.page = 0;
            state.nextCursor = null; state.pointsLoading = false;
            el('reference-index-points-panel').classList.add('hidden'); el('reference-index-points').textContent = ''; sync();
        }
        function selectCollection(id) {
            const collection = state.data && state.data.collections.find(item => item.id === id);
            if (!collection) return Promise.resolve(false);
            state.collectionId = id; state.cursors = ['']; state.page = 0; state.nextCursor = null;
            el('reference-index-points-panel').classList.remove('hidden');
            el('reference-index-points-title').textContent = '只读分块预览 · ' + (collection.name || id);
            el('reference-index-points').textContent = ''; el('reference-index-points-page').textContent = '第 1 页 · 每页 25 块';
            return loadPoints(0, '');
        }
        function loadPoints(page, cursor) {
            const id = state.collectionId, epoch = ++pointsEpoch; state.pointsLoading = true;
            el('reference-index-points-status').textContent = '正在读取分块（不读取向量，不提供点级修改或删除）…';
            el('reference-index-points-status').classList.remove('is-error'); sync();
            return Promise.resolve().then(() => request(BASE + '/collections/' + encodeURIComponent(id)
                + '/points?cursor=' + encodeURIComponent(cursor) + '&limit=25'))
                .then(value => {
                    if (epoch !== pointsEpoch || id !== state.collectionId) return false;
                    if (!value || !Array.isArray(value.points) || !has(value, 'nextCursor')
                            || value.nextCursor != null && typeof value.nextCursor !== 'string' && !count(value.nextCursor)) throw new Error('分块分页响应无效');
                    const next = value.nextCursor == null || value.nextCursor === '' ? null : String(value.nextCursor);
                    if (next !== null && (next === String(cursor) || state.cursors.slice(0, page).includes(next))) throw new Error('分页游标重复，已停止翻页');
                    const box = el('reference-index-points'); box.textContent = '';
                    value.points.forEach(point => {
                        const payload = point && point.payload || {}, card = node('article', null, 'reference-index-point');
                        card.appendChild(node('h4', payload.sectionTitle || payload.fileName || '未命名分块'));
                        card.appendChild(node('p', '分块 ID：' + text(point && point.id) + ' · 文件：' + (text(payload.fileName) || '未知'), 'reference-index-meta'));
                        const heading = Array.isArray(payload.headingPath) ? payload.headingPath.map(text).join(' / ') : text(payload.headingPath);
                        card.appendChild(node('p', '标题路径：' + (heading || '未知') + ' · 资料版本：' + (text(payload.corpusVersion) || '未知'), 'reference-index-meta'));
                        card.appendChild(node('pre', typeof payload.text === 'string' && payload.text.trim() ? payload.text : '旧索引无分块正文', 'reference-index-point-text'));
                        box.appendChild(card);
                    });
                    if (!value.points.length) box.appendChild(node('p', '本页暂无分块。', 'empty-hint'));
                    state.page = page; state.cursors = state.cursors.slice(0, page); state.cursors[page] = cursor; state.nextCursor = next;
                    el('reference-index-points-page').textContent = '第 ' + (page + 1) + ' 页 · 每页 25 块';
                    el('reference-index-points-status').textContent = '本页 ' + value.points.length + ' 块' + (next === null ? ' · 已到末页' : ' · 可继续翻页'); return true;
                }).catch(error => {
                    if (epoch !== pointsEpoch || id !== state.collectionId) return false;
                    el('reference-index-points-status').textContent = '读取分块失败：' + text(error.message || error);
                    el('reference-index-points-status').classList.add('is-error'); return false;
                }).finally(() => { if (epoch === pointsEpoch) { state.pointsLoading = false; sync(); } });
        }

        el('btn-refresh-reference-index').addEventListener('click', refresh);
        el('btn-delete-reference-index').addEventListener('click', () => previewAction('delete', { collectionIds: selectedCollections().map(item => item.id) }));
        el('btn-rebuild-reference-index').addEventListener('click', () => previewAction('rebuild', { collectionIds: selectedCollections().map(item => item.id) }));
        el('btn-reference-select-visible').addEventListener('click', () => selectVisible(true));
        el('btn-reference-clear-selection').addEventListener('click', () => { if (!state.acting) { state.selected.clear(); discardPreview(); sync(); } });
        [['reference-index-kind-filter', 'kind'], ['reference-index-owner-filter', 'owner']].forEach(([id, key]) => {
            el(id).addEventListener('change', () => { if (!state.acting && state.ready && !state.loading) { state[key] = el(id).value; discardPreview(); renderCollections(); sync(); } });
        });
        el('reference-index-inspect-collection').addEventListener('change', sync);
        el('btn-read-reference-points').addEventListener('click', () => selectCollection(el('reference-index-inspect-collection').value));
        el('btn-reference-points-prev').addEventListener('click', () => {
            if (!state.pointsLoading && state.page > 0) return loadPoints(state.page - 1, state.cursors[state.page - 1]);
        });
        el('btn-reference-points-next').addEventListener('click', () => {
            if (!state.pointsLoading && state.nextCursor !== null) return loadPoints(state.page + 1, state.nextCursor);
        });
        sync();
        // create 不访问网络；仅进入独立 tab 或显式刷新读取状态，从不自动提交收费操作。
        return { refresh };
    }

    return { create };
}));
