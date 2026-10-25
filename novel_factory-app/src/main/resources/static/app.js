(function () {
    'use strict';

    // 后端实际端口可由 server.port 覆盖，因此正常部署一律使用页面同源地址。
    // 只有 IDEA 内置静态预览（63342）需要转发到默认的本地后端端口。
    var API_BASE = location.port === '63342' ? 'http://localhost:8080' : '';

    const THEME_KEY = 'novel_factory_theme';

    // 作业 ID 持久化：章节计划裁决的等待窗口可达数小时，刷新页面不应把进行中的作业"弄丢"
    const JOB_KEY = 'novel_factory_job_id';

    // ===== 主题切换（深/浅，localStorage 持久化） =====
    function currentTheme() {
        return document.body.classList.contains('light') ? 'light' : 'dark';
    }

    function applyTheme(theme, persist) {
        document.body.classList.toggle('light', theme === 'light');
        document.querySelectorAll('.theme-toggle').forEach(function (btn) {
            btn.textContent = theme === 'light' ? '深色' : '浅色';
        });
        if (window.recolorParticles) window.recolorParticles();
        if (persist !== false) {
            try { localStorage.setItem(THEME_KEY, theme); } catch (e) {}
        }
    }

    function toggleTheme() {
        applyTheme(currentTheme() === 'light' ? 'dark' : 'light');
    }

    document.querySelectorAll('.theme-toggle').forEach(function (btn) {
        btn.addEventListener('click', toggleTheme);
    });

    // ===== 状态管理 =====
    let currentJobId = null;
    let pollTimer = null;
    let pollFailures = 0;
    let currentStoryDir = null;
    // 故事视图的请求序号：连点两个故事时，先发后到的旧响应必须丢弃，
    // 否则列表属于 A 而 currentStoryDir 已是 B，保存会把 A 的正文写进 B
    let storyViewToken = 0;
    // 章节计划裁决：待裁决计划的本地快照（用于比对"用户是否改过"）+ 倒计时定时器
    let pendingPlanSnapshot = null;
    let pendingPlanSignature = null;
    let approvalTimer = null;

    // ===== DOM 元素 =====
    const btnLogout = document.getElementById('btn-logout');

    // 单体本地应用无登录态：直接进入工作台
    const appPage = document.getElementById('app-page');
    appPage.classList.add('active');

    function logout() {
        currentJobId = null;
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
        window.location.reload();
    }

    btnLogout.addEventListener('click', logout);

    // ===== Tab 切换 =====
    document.querySelectorAll('.nav-tab').forEach(function (btn) {
        btn.addEventListener('click', function () {
            document.querySelectorAll('.nav-tab').forEach(function (b) { b.classList.remove('active'); });
            document.querySelectorAll('.tab-content').forEach(function (c) { c.classList.remove('active'); });
            btn.classList.add('active');
            document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
            if (btn.dataset.tab === 'library') { loadStories(); }
            // 03 项目模型配置与 05 设置共用同一份配置视图（GET /api/config/llm）：一次拉取，两页同时刷新
            if (btn.dataset.tab === 'models' || btn.dataset.tab === 'settings') { loadLlmConfig(); }
            // 04 资料设置进入时同步「我的参考资料 / 文风 / 资料检索方式」卡片
            if (btn.dataset.tab === 'assets') { loadLlmConfig(); loadUserAssets(); }
            // 回到生成页时刷新故事列表：刚写完的故事应能立刻被续写
            if (btn.dataset.tab === 'generate') { loadStoryOptions(); loadMetrics(); }
        });
    });

    // ===== 生成表单 =====
    const form = document.getElementById('generate-form');
    const btnSubmit = document.getElementById('btn-submit');
    const btnCancel = document.getElementById('btn-cancel');
    const jobStatusDiv = document.getElementById('job-status');
    const statusDetail = document.getElementById('status-detail');
    const progressCard = document.getElementById('progress-card');
    const progressBarEl = document.getElementById('progress-bar');
    const progressMetaEl = document.getElementById('progress-meta');
    // 高级（可选）：金手指导通开关与子字段。勾选才提交三件套；不勾选时键缺席（后端按 null
    // = 未声明处理）。注意后端 DTO 虽是 Boolean（支持显式 false），但 checkbox 不勾选时
    // FormData 里没有这个键，前端实际只会发出 true / 缺席两态——"明确没有"等价于不勾选
    const hasCheatMechanismInput = document.getElementById('hasCheatMechanism');
    const cheatFieldsDiv = document.getElementById('cheat-fields');

    function syncCheatFields() {
        if (!hasCheatMechanismInput || !cheatFieldsDiv) return;
        cheatFieldsDiv.classList.toggle('hidden', !hasCheatMechanismInput.checked);
    }

    /**
     * 金手指三件套的可编辑性：新建故事时可改（会写进 story-bible 固化）；
     * 续写时由 bible 决定，这里置为只读——否则“本批改了、下批回退”会让人以为设置丢了。
     */
    function setCheatEditingEnabled(enabled) {
        if (!hasCheatMechanismInput) return;
        hasCheatMechanismInput.disabled = !enabled;
        const nameInput = form.elements['cheatMechanismName'];
        const intervalInput = form.elements['cheatUsageInterval'];
        if (nameInput) nameInput.disabled = !enabled;
        if (intervalInput) intervalInput.disabled = !enabled;
        syncCheatFields();
    }

    if (hasCheatMechanismInput) {
        hasCheatMechanismInput.addEventListener('change', syncCheatFields);
        syncCheatFields();
    }

    /** 字符进度条：26 格，后端在每章开始时更新 currentChapter，轮询到即推进一格 */
    var PROGRESS_WIDTH = 26;

    function renderProgressBar(job) {
        var filled = 0;
        var meta;
        if (job.status === 'AWAITING_APPROVAL') {
            // 挂起态：正文尚未开始，进度条冻结在已完成章数上，不进也不退
            if (job.currentChapter != null && job.totalChapters) {
                filled = Math.min(PROGRESS_WIDTH, Math.max(0,
                    Math.round(PROGRESS_WIDTH * job.currentChapter / job.totalChapters)));
            }
            meta = '已挂起：等待章节计划裁决（正文尚未开始）';
        } else if (job.status === 'CANCELLING') {
            // 停止中：进度条冻结在当前进度。必须放在章号分支之前——轮询到 CANCELLING 时
            // currentChapter 已有值，若先命中章号分支，显示会与取消前完全一致（看不出在停）
            if (job.currentChapter != null && job.totalChapters) {
                filled = Math.min(PROGRESS_WIDTH, Math.max(0,
                    Math.round(PROGRESS_WIDTH * job.currentChapter / job.totalChapters)));
            }
            meta = '停止中：正在等当前步骤结束（已生成章节均已落盘）...';
        } else if (job.currentChapter != null && job.totalChapters) {
            filled = Math.min(PROGRESS_WIDTH, Math.max(0,
                Math.round(PROGRESS_WIDTH * job.currentChapter / job.totalChapters)));
            var pct = Math.min(100, Math.round(job.currentChapter / job.totalChapters * 100));
            meta = '第 ' + job.currentChapter + ' / ' + job.totalChapters + ' 章 · ' + pct + '%';
        } else if (job.status === 'RUNNING') {
            meta = '章节规划中，暂未进入逐章生成...';
        } else {
            meta = '等待生成...';
        }
        var statusNames = { COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' };
        if (statusNames[job.status]) {
            meta = statusNames[job.status] + ' · ' + meta;
        }
        var bar = '';
        for (var i = 0; i < PROGRESS_WIDTH; i++) { bar += i < filled ? '#' : '-'; }
        progressBarEl.textContent = '[' + bar + ']';
        progressMetaEl.textContent = meta;
    }

    // ===== 文本框自适应高度 =====
    // rows 只当"最小高度"用；内容变长就把框撑高，省得在几百字的大纲/世界观里来回滚动。
    // 超过上限才出现滚动条——否则一篇超长正文会把整页顶到天际。
    var AUTO_GROW_MAX_PX = 460;

    function autoGrow(el) {
        if (!el || el.tagName !== 'TEXTAREA') return;
        // 隐藏元素（切到别的 tab、卡片尚未展开）量不出内容高度，量到的是 0，会把框压扁
        if (el.offsetParent === null) return;
        el.style.height = 'auto';
        var full = el.scrollHeight;
        el.style.height = Math.min(full, AUTO_GROW_MAX_PX) + 'px';
        el.style.overflowY = full > AUTO_GROW_MAX_PX ? 'auto' : 'hidden';
    }

    function autoGrowAll() {
        Array.prototype.forEach.call(
            document.querySelectorAll('#draft-card textarea, #generate-form textarea'), autoGrow);
    }

    // 事件委托：任何 textarea 输入时自动撑高（含后来动态生成的裁决卡片里的计划字段）
    document.addEventListener('input', function (e) {
        autoGrow(e.target);
    });
    // 换主题/改窗口宽度后每行能容纳的字数变了，需要重算
    window.addEventListener('resize', autoGrowAll);

    /**
     * 把一套「提交体形状」的字段回填到表单：键就是控件的 name。
     *
     * <p>空值一律跳过——宁可留着用户原本写的内容，也不要因为服务端漏返回一个字段就把输入框清空。
     * 草稿（camelCase）与续写（提交体形状）各自映射后都走这里，避免回填逻辑写两份。
     *
     * @returns 实际回填的字段数
     */
    function applyFormValues(values, skipKeys) {
        let applied = 0;
        if (!values) return applied;
        const skip = skipKeys || [];
        Object.keys(values).forEach(function (key) {
            if (skip.indexOf(key) >= 0) return;
            const el = form.elements[key];
            // 没有对应控件的键（如前端未开放的金手指字段）直接跳过：续写时它们由后端从 bible 自行恢复
            if (!el) return;
            const value = values[key];
            if (value === null || value === undefined || String(value).trim() === '') return;
            el.value = value;
            autoGrow(el);
            flashField(el);
            applied++;
        });
        return applied;
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        submitGenerate();
    });

    /**
     * 收集表单 → POST /api/jobs。
     * 抽成独立函数是为了让"作业中断后继续生成"能直接复用同一条提交路径，
     * 而不是把表单收集逻辑抄第二遍（抄一遍就多一处会漂移的地方）。
     */
    function submitGenerate() {
        const data = {};
        const fd = new FormData(form);
        fd.forEach(function (val, key) {
            if (val && val.trim() !== '') {
                // 后端这几项是 Integer/Boolean，别当字符串发过去：
                // checkbox 的默认值是 "on"，Jackson 按 Boolean 解析会直接 400
                if (key === 'chapterCount' || key === 'maxChapterCount' || key === 'cheatUsageInterval') {
                    data[key] = parseInt(val, 10);
                } else if (key === 'hasCheatMechanism') {
                    data[key] = true;
                } else {
                    data[key] = val;
                }
            }
        });
        // 未勾选金手指时，隐藏字段里的残留值不得发出——否则会写出“未声明却有名字”的脏组合
        if (!hasCheatMechanismInput || !hasCheatMechanismInput.checked) {
            delete data.cheatMechanismName;
            delete data.cheatUsageInterval;
        }

        btnSubmit.disabled = true;
        btnCancel.disabled = false;
        jobStatusDiv.classList.remove('hidden');
        hideResumeAction();
        statusDetail.innerHTML = '<div class="status-row"><span class="status-label">状态</span><span>提交中...</span></div>';
        // 进度专属区块随作业一起出现，先重置为空条
        progressCard.classList.remove('hidden');
        renderProgressBar({ status: 'PENDING', currentChapter: null, totalChapters: null });

        fetchJson('/api/jobs', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        })
        .then(function (job) {
            currentJobId = job.jobId;
            saveJobId(job.jobId);
            hideApprovalCard();   // 新作业：清掉上一轮可能残留的裁决卡片
            pollStatus();
            startPolling();
        })
        .catch(function (err) {
            statusDetail.innerHTML = '<div class="status-row"><span class="status-label">错误</span><span>提交失败：' + esc(err.message) + '</span></div>';
            btnSubmit.disabled = false;
            btnCancel.disabled = true;
        });
    }

    btnCancel.addEventListener('click', function () {
        if (!currentJobId) return;
        const jobId = currentJobId;
        // 取消是协作式的：后端置位信号后由 worker 在下一个检查点收敛终态，
        // 正在飞的模型请求无法撤回（单次可达数分钟）。所以按钮必须立刻给出
        // "已受理、正在停"的反馈，否则用户看到的只有按钮变灰，与没反应无异
        btnCancel.disabled = true;
        showToast('取消已受理，正在停止当前步骤…');
        fetchJson('/api/jobs/' + jobId + '/cancel', { method: 'POST' })
        .then(function (job) {
            if (job) renderJobStatus(job);
            // 立即拉一次状态：状态已是 CANCELLED（排队期/审批挂起中取消）时马上收尾
            pollStatus();
        })
        .catch(function (err) {
            // 受理失败必须把按钮放开，否则用户既停不了作业也没法再点
            btnCancel.disabled = false;
            showToast('取消失败：' + err.message);
        });
    });

    function startPolling() {
        if (pollTimer) return;
        pollTimer = setInterval(pollStatus, 2000);
    }

    function stopPolling() {
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    function isTerminal(status) {
        return ['COMPLETED', 'FAILED', 'CANCELLED'].indexOf(status) >= 0;
    }

    function saveJobId(id) {
        try { localStorage.setItem(JOB_KEY, id); } catch (e) {}
    }

    function clearSavedJobId() {
        try { localStorage.removeItem(JOB_KEY); } catch (e) {}
    }

    function pollStatus() {
        if (!currentJobId) return;
        fetchJson('/api/jobs/' + currentJobId)
        .then(function (job) {
            pollFailures = 0;
            renderJobStatus(job);
            // AWAITING_APPROVAL 刻意不在终态里：挂起期间必须继续轮询，
            // 否则用户裁决完 / 超时放行后，前端不会跟进
            if (isTerminal(job.status)) {
                stopPolling();
                clearSavedJobId();
                btnSubmit.disabled = false;
                btnCancel.disabled = true;
                // 刚落盘的故事要能立刻出现在"续写已有故事"下拉里（中断的场景下这尤其重要）
                loadStoryOptions();
            }
        })
        .catch(function (err) {
            // 作业已不在内存注册表（后端重启）：清掉本地记录并复位，避免永远空转
            if (err && err.status === 404) {
                stopPolling();
                clearSavedJobId();
                currentJobId = null;
                hideApprovalCard();
                jobStatusDiv.classList.add('hidden');
                progressCard.classList.add('hidden');
                btnSubmit.disabled = false;
                btnCancel.disabled = true;
                return;
            }
            // 其他失败（500 / 断网 / 网关返回 HTML）此前被静默吞掉：轮询每 2s 继续、
            // 按钮永久 disabled、用户看不到任何错误。连续失败 5 次（≈10s）后停轮询并复位
            pollFailures++;
            if (pollFailures >= 5) {
                stopPolling();
                btnSubmit.disabled = false;
                btnCancel.disabled = true;
                statusDetail.innerHTML = '<div class="status-row"><span class="status-label">错误</span><span>'
                    + esc('作业状态查询持续失败：' + (err && err.message ? err.message : '网络不可达'))
                    + '</span></div>';
                showToast('作业状态查询失败，已停止轮询；可刷新页面或点「继续生成」接着写');
            }
        });
    }

    /** 刷新页面后恢复进行中的作业：裁决等待窗口可达数小时，不能因为一次 F5 就把作业"弄丢" */
    function restoreJob() {
        let saved = null;
        try { saved = localStorage.getItem(JOB_KEY); } catch (e) {}
        if (!saved) return;
        currentJobId = saved;
        jobStatusDiv.classList.remove('hidden');
        progressCard.classList.remove('hidden');
        btnSubmit.disabled = true;
        btnCancel.disabled = false;
        renderProgressBar({ status: 'PENDING', currentChapter: null, totalChapters: null });
        progressMetaEl.textContent = '正在恢复作业状态...';
        pollStatus();
        startPolling();
    }

    function renderJobStatus(job) {
        // 进度条在专属区块渲染（#progress-card），作业状态卡只放文本信息
        renderProgressBar(job);
        const lines = [];
        lines.push(row('状态', statusBadge(job.status)));
        if (job.storyDirName) lines.push(row('故事目录', job.storyDirName));
        if (job.currentStage) lines.push(row('阶段', esc(job.currentStage)));
        if (job.status === 'AWAITING_APPROVAL' && job.awaitingPlanApprovalAtMs) {
            lines.push(row('挂起于', new Date(job.awaitingPlanApprovalAtMs).toLocaleTimeString()));
        }
        if (job.approvalRound) lines.push(row('人工裁决', '已提交 ' + job.approvalRound + ' 轮'));
        if (job.errorMessage) lines.push(row('错误', '<span class="error-text">' + esc(job.errorMessage) + '</span>'));
        if (job.chapterDurations && Object.keys(job.chapterDurations).length > 0) {
            const durs = Object.entries(job.chapterDurations).map(function (e) {
                return '第' + e[0] + '章: ' + (e[1] / 1000).toFixed(1) + 's';
            }).join('，');
            lines.push(row('耗时', durs));
        }
        statusDetail.innerHTML = lines.join('');
        // 裁决卡片随状态联动：进入挂起态出现，离开即收起
        renderApprovalCard(job);
        // 中断（失败/取消）时给出"接着往下写"的入口
        renderResumeAction(job);
        // 批末体检随轮询更新（每批结束出现；无体检报告时隐藏）
        renderHealthReport(job.healthReport);
    }

    function statusBadge(status) {
        const colors = {
            'PENDING': '#8b93a7',
            'RUNNING': '#7dd3fc',
            'CANCELLING': '#fbbf24',
            'AWAITING_APPROVAL': '#fbbf24',
            'COMPLETED': '#34d399',
            'FAILED': '#f87171',
            'CANCELLED': '#fbbf24'
        };
        const names = { 'AWAITING_APPROVAL': '待裁决', 'CANCELLING': '停止中' };
        const color = colors[status] || '#8b93a7';
        const label = names[status] ? names[status] + ' (' + status + ')' : status;
        return '<span style="color:' + color + ';font-weight:600">' + esc(label) + '</span>';
    }

    function row(label, value) {
        return '<div class="status-row"><span class="status-label">' + label + '</span><span>' + value + '</span></div>';
    }

    // ===== 续写已有故事（把中断的作业接着写完） =====
    // 后端要的是整套 11 个字段（ValidateUserInputNode 对它们做全非空校验），而这些设定在服务端
    // 唯一被持久化下来的地方就是 story-bible.txt（story-meta 只存总章数）。
    // 所以"继续"= 挑一个已有故事 → 取回它的设定与"已经写到第几章" → 回填表单 → 照常提交
    // （带上 resumeStoryDir 后端就从末章往后续写，已落盘章节一律不重写）。

    const resumeSelect = document.getElementById('resumeStoryDir');
    const resumeHintDiv = document.getElementById('resume-hint');
    const btnReloadStories = document.getElementById('btn-reload-stories');
    const resumeAction = document.getElementById('resume-action');
    const resumeActionHint = document.getElementById('resume-action-hint');
    const btnResumeFailed = document.getElementById('btn-resume-failed');

    /** 最近一次中断作业的故事目录，"继续生成"按钮据此续写 */
    let interruptedStoryDir = null;

    function renderResumeHint(message, ok) {
        if (!message) {
            resumeHintDiv.classList.add('hidden');
            resumeHintDiv.textContent = '';
            return;
        }
        resumeHintDiv.classList.remove('hidden');
        resumeHintDiv.classList.toggle('resume-hint-ok', ok === true);
        resumeHintDiv.classList.toggle('resume-hint-error', ok === false);
        resumeHintDiv.textContent = message;
    }

    function hideResumeAction() {
        resumeAction.classList.add('hidden');
        interruptedStoryDir = null;
    }

    /**
     * 中断后的续写入口，刻意放在作业状态卡里：用户刚看到失败，入口就在眼前，
     * 不用跑去故事库里翻，也不用记住目录名。
     */
    function renderResumeAction(job) {
        const interrupted = job.status === 'FAILED' || job.status === 'CANCELLED';
        if (!interrupted || !job.storyDirName) {
            hideResumeAction();
            return;
        }
        interruptedStoryDir = job.storyDirName;
        // 已落盘章数以 chapterDurations 为准：规划期取消时 currentChapter 已是 1 但正文一章没写，
        // 按 currentChapter 提示"已推进到第 1 章"会把"零章落盘"说成"写了一章"
        const doneChapters = job.chapterDurations ? Object.keys(job.chapterDurations).length : 0;
        const at = doneChapters > 0
                ? '本次已落盘 ' + doneChapters + ' 章' : '本次未新增章节（规划阶段中断）';
        resumeActionHint.textContent = '「' + job.storyDirName + '」这次没写完（' + at + '）。'
                + '续写会从该故事已落盘的最后一章接着往后写（已落盘章节不会重写）；'
                + '若表单已被清空，会先把该故事的设定载回来。';
        resumeAction.classList.remove('hidden');
    }

    /** 拉取故事列表填充下拉；尽量保留当前选中项（刷新列表不该把用户选的那条跳掉） */
    function loadStoryOptions() {
        const keep = resumeSelect.value;
        return fetchJson('/api/stories')
        .then(function (stories) {
            resumeSelect.innerHTML = '';
            resumeSelect.appendChild(new Option('（新建故事 · 不续写）', ''));
            Array.prototype.forEach.call(stories || [], function (story) {
                const label = (story.novelTitle || story.storyDirName)
                        + '（已 ' + story.chapterCount + ' 章）'
                        + (story.activeJob ? ' · 生成中' : '');
                resumeSelect.appendChild(new Option(label, story.storyDirName));
            });
            resumeSelect.value = keep;
            return stories;
        })
        .catch(function () {
            // 列表拉不到不影响"新建故事"，静默降级即可
        });
    }

    /** 载入某个故事的设定；resolve(true) 表示可以接着提交 */
    function loadResume(storyDirName) {
        renderResumeHint('正在读取「' + storyDirName + '」的设定…', null);
        return fetchJson('/api/stories/' + encodeURIComponent(storyDirName) + '/resume')
        .then(function (info) {
            // chapterCount 不回填：后端固定给 DEFAULT_RESUME_BATCH_SIZE=5（“这次写几章”是用户自己的选择），
            // 无差别回填会把用户刚改的 20 静默改回 5。
            // 金手指三件套也不走 applyFormValues：checkbox 只设 value 不设 checked，必须显式回填
            const setting = info.setting || {};
            const applied = applyFormValues(setting,
                    ['chapterCount', 'hasCheatMechanism', 'cheatMechanismName', 'cheatUsageInterval']);
            if (hasCheatMechanismInput) {
                hasCheatMechanismInput.checked = Boolean(setting.hasCheatMechanism);
                const nameInput = form.elements['cheatMechanismName'];
                const intervalInput = form.elements['cheatUsageInterval'];
                if (nameInput && setting.cheatMechanismName) nameInput.value = setting.cheatMechanismName;
                if (intervalInput && setting.cheatUsageInterval != null) {
                    intervalInput.value = setting.cheatUsageInterval;
                }
                setCheatEditingEnabled(false);   // 续写：以 story-bible 固化为准
            }
            let message = (info.latestChapterNo
                    ? '已有 ' + info.existingChapterCount + ' 章，将从第 ' + info.nextChapterNo + ' 章接着写'
                    : '该故事还没有落盘章节，将从第 1 章开始')
                    + '；设定已载入 ' + applied + ' 项';
            const missing = info.missingFields || [];
            if (missing.length > 0) {
                // 入参校验是一条 or 链、不报具体字段，只能在这里点名，
                // 否则用户提交后只看到一句笼统的失败，完全不知道该补什么
                message += '\n注意：bible 里缺少「' + missing.join('、') + '」，请手动补齐后再提交';
            }
            renderResumeHint(message, missing.length === 0);
            return true;
        })
        .catch(function (err) {
            renderResumeHint('读取失败：' + err.message, false);
            return false;
        });
    }

    resumeSelect.addEventListener('change', function () {
        const storyDirName = resumeSelect.value;
        if (!storyDirName) {
            renderResumeHint(null);
            setCheatEditingEnabled(true);   // 切回“新建故事”：金手指三件套恢复可编辑
            return;
        }
        loadResume(storyDirName);
    });

    btnReloadStories.addEventListener('click', function (e) {
        // 按钮挂在 label 里，不拦的话点击会顺带把焦点丢给下拉
        e.preventDefault();
        e.stopPropagation();
        loadStoryOptions();
    });

    btnResumeFailed.addEventListener('click', function () {
        const storyDirName = interruptedStoryDir;
        if (!storyDirName) return;
        resumeSelect.value = storyDirName;
        if (String(form.elements['novel_title'].value || '').trim() === '') {
            // 表单已被清空（比如关机重开）→ 先取回设定再提交，否则必撞"全字段非空"校验
            loadResume(storyDirName).then(function (ok) {
                if (ok) submitGenerate();
            });
            return;
        }
        submitGenerate();
    });

    loadStoryOptions();
    // 运行观测卡在生成页，首屏即加载（观测接口 fail-soft，读不到只是空统计）
    loadMetrics();

    // ===== 一键生成设定集（草稿：只给题材 → 整套字段） =====
    // 后端 /api/setting-draft 是同步的单次 LLM 调用、零落盘副作用；结果只回填表单，
    // 用户改完 / 重生成完之后，仍然走 /api/jobs 提交。
    // 字段名与后端 SettingDraftResponseDTO 的 schema 一致（camelCase），这里统一做一次到表单控件的映射。

    /** 草稿字段 → 表单控件 id。totalChapters 落到 maxChapterCount（全书总章数，首批提交后即 sticky 固化） */
    const DRAFT_FIELD_INPUTS = {
        novelTitle: 'novel_title',
        style: 'style',
        worldSetting: 'worldSetting',
        perspective: 'perspective',
        targetAudience: 'targetAudience',
        tone: 'tone',
        protagonist: 'protagonist',
        outline: 'outline',
        chapterGoal: 'chapterGoal',
        totalChapters: 'maxChapterCount'
    };

    /** 可作为「用户已定偏好」上报的字段；其余字段只能通过 targets / previous 表达 */
    const DRAFT_PINNABLE = ['style', 'targetAudience', 'tone', 'perspective'];

    /**
     * 常用题材。刻意都命中 GenreTypeVO 的关键词表（玄幻 / 悬疑 / 言情三族）——
     * 后端题材资料策略据此路由到对应 genres 资料包，不命中的题材只能落到 styles/default.md。
     */
    const THEME_PRESETS = ['都市异能', '玄幻修真', '悬疑推理', '刑侦犯罪', '甜宠言情', '校园青春', '科幻末世', '无限流'];

    const FIELD_LABELS = {
        novelTitle: '书名', style: '风格', worldSetting: '世界观', perspective: '视角',
        targetAudience: '目标人群', tone: '基调', protagonist: '主人公',
        outline: '故事大纲', chapterGoal: '章节目标', totalChapters: '总章数'
    };

    const themePresetsDiv = document.getElementById('theme-presets');
    const draftHintsInput = document.getElementById('draft-hints');
    const draftStatusDiv = document.getElementById('draft-status');
    const btnDraftAll = document.getElementById('btn-draft-all');
    const btnDraftOutline = document.getElementById('btn-draft-outline');
    const themeInput = document.getElementById('theme');

    let draftTimer = null;

    function fieldInput(key) {
        return document.getElementById(DRAFT_FIELD_INPUTS[key]);
    }

    function flashField(el) {
        if (!el || !el.classList) return;
        el.classList.remove('field-flash');
        // 强制一次重排：否则连续给同一个元素加同一个 class 不会重放动画
        void el.offsetWidth;
        el.classList.add('field-flash');
    }

    function renderThemePresets() {
        themePresetsDiv.innerHTML = '';
        THEME_PRESETS.forEach(function (theme) {
            const chip = document.createElement('button');
            chip.type = 'button';
            chip.className = 'draft-preset';
            chip.textContent = theme;
            chip.addEventListener('click', function () {
                themeInput.value = theme;
                flashField(themeInput);
                highlightActivePreset();
                draftHintsInput.focus();
            });
            themePresetsDiv.appendChild(chip);
        });
        highlightActivePreset();
    }

    function highlightActivePreset() {
        const current = String(themeInput.value || '').trim();
        Array.prototype.forEach.call(themePresetsDiv.children, function (chip) {
            chip.classList.toggle('active', chip.textContent === current);
        });
    }

    /** 表单现值 → 后端 previous（键同 schema）：既是"锁定字段的取值来源"，也是"本轮必须与它不同"的对照 */
    function collectDraftPrevious() {
        const previous = {};
        Object.keys(DRAFT_FIELD_INPUTS).forEach(function (key) {
            const el = fieldInput(key);
            const value = el ? String(el.value || '').trim() : '';
            if (value !== '') previous[key] = value;
        });
        return previous;
    }

    /**
     * 用户已定偏好：只在"本次不重写它"时上报。
     * 否则会和 prompt 里"本次必须与上一版不同"打架——同一字段既被要求保持、又被要求改写。
     */
    function collectPinnedFields(targets) {
        const pinned = {};
        DRAFT_PINNABLE.forEach(function (key) {
            if (targets && targets.indexOf(key) >= 0) {
                // 本次要重写它，就绝不能再声称"用户已定"——显式置 null 而不是省略该键，
                // 否则 prompt 里同一字段会既被要求保持、又被要求改写
                pinned[key] = null;
                return;
            }
            const el = fieldInput(key);
            const value = el ? String(el.value || '').trim() : '';
            pinned[key] = value === '' ? null : value;
        });
        return pinned;
    }

    function setDraftBusy(busy) {
        btnDraftAll.disabled = busy;
        btnDraftOutline.disabled = busy;
        Array.prototype.forEach.call(document.querySelectorAll('.regen-btn'), function (btn) {
            btn.disabled = busy;
        });
    }

    function renderDraftStatus(message, ok) {
        if (!message) {
            draftStatusDiv.classList.add('hidden');
            draftStatusDiv.textContent = '';
            return;
        }
        draftStatusDiv.classList.remove('hidden');
        draftStatusDiv.classList.toggle('has-config', ok === true);
        draftStatusDiv.classList.toggle('draft-status-error', ok === false);
        draftStatusDiv.textContent = message;
    }

    /** 单次调用可能等 20-60 秒，给个秒表比只显示"加载中"有用得多 */
    function startDraftStatus(what) {
        const startedAt = Date.now();
        setDraftBusy(true);
        const tick = function () {
            const sec = Math.round((Date.now() - startedAt) / 1000);
            renderDraftStatus('正在' + what + '…已等待 ' + sec + ' 秒（单次模型调用，通常 20-60 秒）', null);
        };
        tick();
        draftTimer = setInterval(tick, 1000);
    }

    function stopDraftStatus() {
        if (draftTimer) {
            clearInterval(draftTimer);
            draftTimer = null;
        }
        setDraftBusy(false);
    }

    /**
     * 请求设定集草稿。
     * @param targets 要重生成的字段；null/空数组 = 全部重生成
     */
    function requestSettingDraft(targets) {
        const theme = String(themeInput.value || '').trim();
        if (theme === '') {
            renderDraftStatus('请先在下方「题材」栏填一个题材，或点上面任一常用题材', false);
            themeInput.focus();
            return;
        }

        const previous = collectDraftPrevious();
        const body = {
            theme: theme,
            extraHints: String(draftHintsInput.value || '').trim() || null,
            targets: targets && targets.length ? targets : null,
            previous: Object.keys(previous).length ? previous : null
        };
        Object.assign(body, collectPinnedFields(targets));

        const isFull = !targets || targets.length === 0;
        stopDraftStatus();
        startDraftStatus(isFull ? '构思整份设定集'
                : '重写' + targets.map(function (k) { return FIELD_LABELS[k] || k; }).join('、'));

        fetchJson('/api/setting-draft', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        })
        .then(function (draft) {
            stopDraftStatus();
            const applied = applyDraft(draft, targets);
            renderDraftStatus('已回填 ' + applied + ' 个字段'
                    + (isFull ? '（可以直接改，也可以点任意字段旁的 ↻ 单独重生成）' : '')
                    + (draft.rationale ? '\nAI 的取舍说明：' + draft.rationale : ''), true);
        })
        .catch(function (err) {
            stopDraftStatus();
            renderDraftStatus('生成失败：' + err.message, false);
        });
    }

    /**
     * 回填表单。
     * 只回填"本次要求重生成"的字段（targets 为空即全部），且空值一律跳过——
     * 宁可留着用户原本写的内容，也不要因为后端漏返回一个字段就把输入框清空。
     */
    function applyDraft(draft, targets) {
        const only = targets && targets.length ? targets : null;
        const values = {};
        Object.keys(DRAFT_FIELD_INPUTS).forEach(function (key) {
            if (only && only.indexOf(key) < 0) return;
            values[DRAFT_FIELD_INPUTS[key]] = draft[key];   // 草稿字段名 → 表单控件 name
        });
        const applied = applyFormValues(values);
        if (applied > 0) highlightActivePreset();
        return applied;
    }

    btnDraftAll.addEventListener('click', function () {
        const existing = collectDraftPrevious();
        // 总章数不算"已有创作内容"，不该因为它非空就弹覆盖确认
        delete existing.totalChapters;
        if (Object.keys(existing).length > 0
                && !window.confirm('这会用新的一版覆盖下方表单里已有的内容（题材与额外要求除外）。继续？')) {
            return;
        }
        requestSettingDraft(null);
    });

    btnDraftOutline.addEventListener('click', function () {
        requestSettingDraft(['outline']);
    });

    themeInput.addEventListener('input', highlightActivePreset);

    /** 给每个可重生成字段的 label 挂一个 ↻：点它只换这一个字段，其余字段由后端锁定不变 */
    function mountRegenButtons() {
        Object.keys(DRAFT_FIELD_INPUTS).forEach(function (key) {
            const el = fieldInput(key);
            if (!el) return;
            const label = document.querySelector('label[for="' + el.id + '"]');
            if (!label) return;
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'regen-btn';
            btn.dataset.field = key;
            btn.textContent = '↻';
            btn.title = '只重新生成「' + (FIELD_LABELS[key] || key) + '」，其余字段保持当前内容';
            btn.addEventListener('click', function (e) {
                // 按钮挂在 label 里，不拦的话点击会顺带把焦点丢给输入框
                e.preventDefault();
                e.stopPropagation();
                requestSettingDraft([key]);
            });
            label.appendChild(btn);
        });
    }

    renderThemePresets();
    mountRegenButtons();

    // ===== 章节计划裁决（human-in-the-loop） =====
    // 后端在「计划已校验、正文未写」处把作业挂起并透出 pendingChapterPlan；
    // 前端只做两件事：把计划渲染成可编辑表单、把裁决结果 POST 回去。
    // 注意：AWAITING_APPROVAL 不是终态，轮询必须继续跑（超时后由后端自动放行或中止）。
    const approvalCard = document.getElementById('approval-card');
    const approvalPlanDiv = document.getElementById('approval-plan');
    const approvalCountdown = document.getElementById('approval-countdown');
    const approvalHint = document.getElementById('approval-hint');
    const approvalError = document.getElementById('approval-error');
    const btnApprovePlan = document.getElementById('btn-approve-plan');
    const btnRejectPlan = document.getElementById('btn-reject-plan');

    const CHAPTER_TYPE_OPTIONS = [
        { value: 'normal', label: 'normal · 常规推进章' },
        { value: 'transition', label: 'transition · 过渡章' },
        { value: 'climax', label: 'climax · 高潮章' },
        { value: 'finale', label: 'finale · 卷末收束章' }
    ];

    let countdownDeadline = null;

    function renderApprovalCard(job) {
        if (!job || job.status !== 'AWAITING_APPROVAL') {
            if (!approvalCard.classList.contains('hidden')) hideApprovalCard();
            return;
        }
        approvalCard.classList.remove('hidden');
        const plan = job.pendingChapterPlan || null;
        const signature = JSON.stringify(plan);
        // 轮询每 2s 到一次，而计划本身不会变；只有签名变化才重建 DOM，
        // 否则用户正在编辑的输入框会被反复清空
        if (signature !== pendingPlanSignature) {
            pendingPlanSignature = signature;
            pendingPlanSnapshot = plan;
            buildApprovalPlan(plan);
        }
        startApprovalCountdown(job.planApprovalDeadlineMs);
    }

    function hideApprovalCard() {
        stopApprovalCountdown();
        pendingPlanSnapshot = null;
        pendingPlanSignature = null;
        approvalPlanDiv.innerHTML = '';
        approvalCountdown.textContent = '';
        setApprovalError(null);
        setApprovalBusy(false);
        approvalCard.classList.add('hidden');
    }

    function setApprovalError(msg) {
        if (!msg) {
            approvalError.classList.add('hidden');
            approvalError.textContent = '';
            return;
        }
        approvalError.classList.remove('hidden');
        approvalError.textContent = msg;
    }

    function setApprovalBusy(busy) {
        btnApprovePlan.disabled = busy;
        btnRejectPlan.disabled = busy;
        btnApprovePlan.textContent = busy ? '提 交 中...' : '采 纳 并 通 过';
    }

    function startApprovalCountdown(deadlineMs) {
        if (!deadlineMs) {
            stopApprovalCountdown();
            approvalCountdown.textContent = '';
            return;
        }
        // 每次轮询都会调到这里；同一截止点已在倒计时则不重启（否则会不断重置 1s 定时器）
        if (approvalTimer && countdownDeadline === deadlineMs) return;
        stopApprovalCountdown();
        countdownDeadline = deadlineMs;
        const tick = function () {
            const left = deadlineMs - Date.now();
            if (left <= 0) {
                approvalCountdown.textContent = '已到截止时刻，等待后端处理…';
                stopApprovalCountdown();
                return;
            }
            const totalSec = Math.floor(left / 1000);
            const mm = String(Math.floor(totalSec / 60)).padStart(2, '0');
            const ss = String(totalSec % 60).padStart(2, '0');
            approvalCountdown.textContent = '裁决剩余 ' + mm + ':' + ss;
        };
        tick();
        approvalTimer = setInterval(tick, 1000);
    }

    function stopApprovalCountdown() {
        if (approvalTimer) {
            clearInterval(approvalTimer);
            approvalTimer = null;
        }
        countdownDeadline = null;
    }

    function makeText(tag, cls, text) {
        const el = document.createElement(tag);
        if (cls) el.className = cls;
        el.textContent = text;
        return el;
    }

    function makeField(labelText, control, fullWidth) {
        const wrap = document.createElement('div');
        wrap.className = fullWidth ? 'form-group full-width' : 'form-group';
        const label = document.createElement('label');
        label.textContent = labelText;
        wrap.appendChild(label);
        wrap.appendChild(control);
        return wrap;
    }

    function buildApprovalPlan(plan) {
        approvalPlanDiv.innerHTML = '';
        setApprovalError(null);
        if (!plan || !plan.chapters || plan.chapters.length === 0) {
            approvalHint.textContent = '后端未透出计划内容，可直接采纳——后端将使用已落盘的计划继续生成正文。';
            return;
        }
        approvalHint.textContent = 'AI 已生成下列 ' + plan.chapters.length
            + ' 章计划（当前规划段），正文尚未开始。可直接采纳，也可就地修订后通过。';
        plan.chapters.forEach(function (ch) {
            approvalPlanDiv.appendChild(buildApprovalChapter(ch));
        });
        if (plan.risks && plan.risks.length > 0) {
            const riskBox = document.createElement('div');
            riskBox.className = 'approval-risks';
            riskBox.appendChild(makeText('h4', 'plan-risks-title', '规划风险自评'));
            plan.risks.forEach(function (r) {
                riskBox.appendChild(makeText('p', 'approval-risk-item', '· ' + r));
            });
            approvalPlanDiv.appendChild(riskBox);
        }
    }

    function buildApprovalChapter(ch) {
        const box = document.createElement('div');
        box.className = 'approval-chapter';
        box.dataset.chapterNo = ch.chapterNo;

        const head = document.createElement('div');
        head.className = 'approval-chapter-head';
        head.appendChild(makeText('span', 'approval-chapter-no', 'CH ' + ch.chapterNo));
        const title = document.createElement('input');
        title.type = 'text';
        title.className = 'ap-title';
        title.value = ch.title || '';
        head.appendChild(title);
        box.appendChild(head);

        const grid = document.createElement('div');
        grid.className = 'approval-grid';

        const goal = document.createElement('textarea');
        goal.className = 'ap-goal';
        goal.rows = 3;
        goal.value = ch.goal || '';
        grid.appendChild(makeField('本章目标 *', goal));

        const typeSel = document.createElement('select');
        typeSel.className = 'ap-type';
        const current = normalizeType(ch.chapterType);
        CHAPTER_TYPE_OPTIONS.forEach(function (t) {
            const opt = document.createElement('option');
            opt.value = t.value;
            opt.textContent = t.label;
            if (t.value === current) opt.selected = true;
            typeSel.appendChild(opt);
        });
        grid.appendChild(makeField('章型', typeSel));

        const chars = document.createElement('textarea');
        chars.className = 'ap-characters';
        chars.rows = 2;
        chars.value = (ch.characters || []).join('、');
        grid.appendChild(makeField('出场角色（顿号分隔）', chars));

        const hook = document.createElement('textarea');
        hook.className = 'ap-hook';
        hook.rows = 2;
        hook.value = ch.endingHook || '';
        grid.appendChild(makeField('章末钩子', hook));

        const events = document.createElement('textarea');
        events.className = 'ap-events';
        events.rows = 4;
        events.value = (ch.keyEvents || []).join('\n');
        grid.appendChild(makeField('关键事件 *（每行一条）', events, true));

        box.appendChild(grid);
        return box;
    }

    /** 与后端 ChapterTypeVO.of 同一套容错口径：空值/未识别一律按 normal，避免"没改却判成改过" */
    function normalizeType(v) {
        const t = String(v || '').trim().toLowerCase();
        return t === '' ? 'normal' : t;
    }

    function splitBySeparator(raw) {
        return String(raw || '').split(/[、,，;；]/)
            .map(function (s) { return s.trim(); })
            .filter(function (s) { return s !== ''; });
    }

    function splitLines(raw) {
        return String(raw || '').split(/\r?\n/)
            .map(function (s) { return s.trim(); })
            .filter(function (s) { return s !== ''; });
    }

    function sameList(a, b) {
        if (a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) {
            if (a[i] !== b[i]) return false;
        }
        return true;
    }

    function sourceChapter(no) {
        if (!pendingPlanSnapshot || !pendingPlanSnapshot.chapters) return null;
        for (let i = 0; i < pendingPlanSnapshot.chapters.length; i++) {
            if (pendingPlanSnapshot.chapters[i].chapterNo === no) return pendingPlanSnapshot.chapters[i];
        }
        return null;
    }

    function isSameChapter(a, b) {
        return a.title === (b.title || '')
            && a.goal === (b.goal || '')
            && a.endingHook === (b.endingHook || '')
            && a.chapterType === normalizeType(b.chapterType)
            && sameList(a.characters, b.characters || [])
            && sameList(a.keyEvents, b.keyEvents || []);
    }

    /**
     * 表单 → 裁决请求体。
     * 返回 null 表示"用户没改任何东西"：按后端契约，chapters 为空即"采纳 AI 原版"，
     * 比"把原样表单回传一遍"更能表达真实意图，也少一次无谓的机械校验。
     * 改过则必须回传完整章节列表（后端把非空 chapters 整体视为人工修订稿）。
     */
    function collectRevisedPlan() {
        if (!pendingPlanSnapshot || !pendingPlanSnapshot.chapters) return null;
        const rows = approvalPlanDiv.querySelectorAll('.approval-chapter');
        if (rows.length === 0) return null;
        const chapters = [];
        let changed = false;
        Array.prototype.forEach.call(rows, function (row) {
            const no = parseInt(row.dataset.chapterNo, 10);
            const src = sourceChapter(no) || {};
            const item = {
                chapterNo: no,
                title: row.querySelector('.ap-title').value.trim(),
                goal: row.querySelector('.ap-goal').value.trim(),
                characters: splitBySeparator(row.querySelector('.ap-characters').value),
                keyEvents: splitLines(row.querySelector('.ap-events').value),
                endingHook: row.querySelector('.ap-hook').value.trim(),
                chapterType: row.querySelector('.ap-type').value,
                // 裁决卡不展示、但后端会整体替换计划：原样带回，少传即等于清空
                // （时间锚 / 悬念档位 / 主线推进 / 对白承载声明）
                dialogueScenes: src.dialogueScenes || [],
                timeAdvance: src.timeAdvance || null,
                suspenseBeat: src.suspenseBeat || null,
                mainLineAdvance: src.mainLineAdvance || null
            };
            if (!isSameChapter(item, src)) changed = true;
            chapters.push(item);
        });
        if (!changed) return null;
        return {
            storyId: pendingPlanSnapshot.storyId || null,
            chapters: chapters,
            risks: pendingPlanSnapshot.risks || []
        };
    }

    function submitDecision(url, payload) {
        setApprovalError(null);
        setApprovalBusy(true);
        fetchJson(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload || {})
        })
        .then(function (job) {
            setApprovalBusy(false);
            showToast(payload && payload.chapters ? '已按修订稿通过' : '裁决已提交');
            renderJobStatus(job);
            startPolling();   // 兜底：若轮询曾因终态停止，这里恢复
        })
        .catch(function (err) {
            setApprovalBusy(false);
            // 400 = 修订稿没过结构校验，作业仍在挂起，就地改正后重提；
            // 409 = 已超时放行/已取消，下次轮询会让卡片自动收起
            setApprovalError('裁决未生效：' + err.message);
        });
    }

    btnApprovePlan.addEventListener('click', function () {
        if (!currentJobId) return;
        submitDecision('/api/jobs/' + currentJobId + '/chapter-plan/approve', collectRevisedPlan());
    });

    btnRejectPlan.addEventListener('click', function () {
        if (!currentJobId) return;
        if (!window.confirm('驳回将中止本批生成（已落盘的计划仍保留在 run 目录）。确认驳回？')) return;
        submitDecision('/api/jobs/' + currentJobId + '/chapter-plan/reject', null);
    });

    // ===== 故事库 =====
    const storiesDiv = document.getElementById('stories');
    const libraryList = document.getElementById('library-list');
    const chapterListDiv = document.getElementById('chapter-list');
    const chaptersDiv = document.getElementById('chapters');
    const chapterViewDiv = document.getElementById('chapter-view');
    const workbenchOverview = document.getElementById('workbench-overview');
    const workbenchMetrics = document.getElementById('workbench-metrics');
    const storyOverviewIntro = document.getElementById('story-overview-intro');
    const workbenchTitle = document.getElementById('workbench-title');
    const chapterViewTitle = document.getElementById('chapter-view-title');
    const chapterListTitle = document.getElementById('chapter-list-title');

    document.getElementById('btn-back-stories').addEventListener('click', function () {
        chapterListDiv.classList.add('hidden');
        workbenchOverview.classList.add('hidden');
        libraryList.classList.remove('hidden');
        currentStoryDir = null;
    });

    document.getElementById('btn-back-chapters').addEventListener('click', function () {
        chapterViewDiv.classList.add('hidden');
        chapterListDiv.classList.remove('hidden');
        workbenchOverview.classList.remove('hidden');
        hideChapterNav();
    });

    document.getElementById('btn-refresh-workbench').addEventListener('click', function () {
        if (currentStoryDir) loadWorkbench(currentStoryDir);
    });

    const btnEdit = document.getElementById('btn-edit');
    const btnSave = document.getElementById('btn-save');
    const btnCancelEdit = document.getElementById('btn-cancel-edit');
    const contentReadonly = document.getElementById('chapter-content-readonly');
    const contentEdit = document.getElementById('chapter-content-edit');
    const saveResult = document.getElementById('save-result');
    let currentChapterNo = null;
    let chapterSequence = [];
    const btnPrevChapter = document.getElementById('btn-prev-chapter');
    const btnNextChapter = document.getElementById('btn-next-chapter');
    const chapterNav = document.getElementById('chapter-navigation');

    function hideChapterNav() {
        chapterNav.classList.add('hidden');
    }

    function updateChapterNavigation() {
        var index = chapterSequence.indexOf(currentChapterNo);
        var prevVisible = index > 0;
        var nextVisible = index >= 0 && index < chapterSequence.length - 1;
        btnPrevChapter.classList.toggle('hidden', !prevVisible);
        btnNextChapter.classList.toggle('hidden', !nextVisible);
        // 既无上一章又无下一章（如单章故事）时，整体隐藏右侧悬浮导航
        chapterNav.classList.toggle('hidden', !(prevVisible || nextVisible));
    }

    function navigateChapter(direction) {
        if (chapterViewDiv.classList.contains('editing')) {
            showToast('请先保存或取消当前编辑');
            return;
        }
        var index = chapterSequence.indexOf(currentChapterNo);
        var targetIndex = index + direction;
        if (index < 0 || targetIndex < 0 || targetIndex >= chapterSequence.length) return;
        loadChapter(currentStoryDir, chapterSequence[targetIndex]);
    }

    btnPrevChapter.addEventListener('click', function () { navigateChapter(-1); });
    btnNextChapter.addEventListener('click', function () { navigateChapter(1); });

    // 就地编辑：与阅读态共用同一段正文的样式（class=chapter-text），仅变成 contenteditable
    // 进入编辑时把光标放到末尾，避免首字位置突兀
    function focusAtEnd(el) {
        el.focus();
        try {
            const range = document.createRange();
            range.selectNodeContents(el);
            range.collapse(false);
            const sel = window.getSelection();
            sel.removeAllRanges();
            sel.addRange(range);
        } catch (e) { /* 老浏览器兜底，focus 即可 */ }
    }

    // 粘贴只保留纯文本，避免从外部粘贴时把样式/超链接带进来污染正文
    contentEdit.addEventListener('paste', function (e) {
        e.preventDefault();
        const text = (e.clipboardData || window.clipboardData).getData('text/plain');
        if (document.execCommand) {
            document.execCommand('insertText', false, text);
        } else {
            // 现代浏览器兜底：Range + getSelection 插入文本
            const sel = window.getSelection();
            if (sel && sel.rangeCount) {
                sel.getRangeAt(0).insertNode(document.createTextNode(text));
                sel.collapseToEnd();
            }
        }
    });

    btnEdit.addEventListener('click', function () {
        contentReadonly.classList.add('hidden');
        contentEdit.classList.remove('hidden');
        contentEdit.textContent = contentReadonly.textContent;
        btnEdit.classList.add('hidden');
        btnSave.classList.remove('hidden');
        btnCancelEdit.classList.remove('hidden');
        saveResult.classList.add('hidden');
        chapterViewDiv.classList.add('editing');
        focusAtEnd(contentEdit);
    });

    btnCancelEdit.addEventListener('click', function () {
        chapterViewDiv.classList.remove('editing');
        contentEdit.classList.add('hidden');
        contentReadonly.classList.remove('hidden');
        btnEdit.classList.remove('hidden');
        btnSave.classList.add('hidden');
        btnCancelEdit.classList.add('hidden');
        saveResult.classList.add('hidden');
    });

    // 保存结果提示：必须保留基类 save-result，否则 .save-result.warning/success/error
    // 的颜色与字号规则全部失效（旧实现直接 className 覆盖导致提示样式错乱）
    function showSaveResult(kind, text) {
        saveResult.className = 'save-result ' + kind;
        saveResult.textContent = text;
        saveResult.classList.remove('hidden');
    }

    // 后端异常 message 为 null 时 warning 会拼出 "回流失败：null" 之类的尾巴，展示前清理
    function normalizeWarning(w) {
        w = (w || '').trim();
        if (!w) return '';
        return w.replace(/:?\s*null\s*$/, '');
    }

    btnSave.addEventListener('click', function () {
        if (!currentStoryDir || currentChapterNo == null) return;
        btnSave.disabled = true;
        btnCancelEdit.disabled = true;
        saveResult.classList.add('hidden');
        saveResult.textContent = '';
        fetch(API_BASE + '/api/stories/' + encodeURIComponent(currentStoryDir) + '/chapters/' + currentChapterNo, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content: contentEdit.innerText })
        })
        .then(function (r) {
            if (r.status === 409) throw new Error('该故事有运行中的作业，请等待完成后再编辑');
            var ct = (r.headers.get('content-type') || '').toLowerCase();
            if (ct.indexOf('application/json') === -1) {
                throw new Error('服务暂不可用（' + r.status + '），请稍后重试');
            }
            return r.json().then(function (body) {
                if (!r.ok) throw new Error((body && body.message) ? body.message : '请求失败（' + r.status + '）');
                return body;
            });
        })
        .then(function (resp) {
            btnSave.disabled = false;
            btnCancelEdit.disabled = false;
            var warning = normalizeWarning(resp.warning);
            var savedMsg = resp.partial ? '，摘要为残缺记忆（账本/伏笔需复核）'
                    : (resp.summaryUpdated ? '，摘要已更新' : '');
            if (!chapterViewDiv.classList.contains('hidden')) {
                // 仍在章节页：切回阅读态并展示新正文；保存结果提示保留可见，不再被秒藏
                chapterViewDiv.classList.remove('editing');
                contentEdit.classList.add('hidden');
                contentReadonly.classList.remove('hidden');
                contentReadonly.textContent = contentEdit.innerText;
                btnEdit.classList.remove('hidden');
                btnSave.classList.add('hidden');
                btnCancelEdit.classList.add('hidden');
                if (warning) {
                    showSaveResult('warning', '正文已保存' + savedMsg + '，记忆回流未完成：' + warning);
                } else {
                    showSaveResult('success', '正文已保存' + savedMsg);
                }
            }
            // Toast 与页面是否停留在章节页无关，始终给出结论
            if (warning) {
                showToast('正文已保存（记忆回流未完成，正文不受影响）');
            } else {
                showToast('正文已保存');
            }
        })
        .catch(function (err) {
            btnSave.disabled = false;
            btnCancelEdit.disabled = false;
            var msg = err && err.message ? err.message : String(err);
            if (/failed to fetch|networkerror|load failed|net::err/i.test(msg)) {
                msg = '无法连接后端服务，请确认应用已启动；正文本次可能未保存，请重试';
            }
            if (!chapterViewDiv.classList.contains('hidden')) {
                // 请求失败：留在编辑态供重试，原正文仍在输入框内未丢失
                showSaveResult('error', '保存失败：' + msg);
            } else {
                showToast('保存失败：' + msg);
            }
        });
    });

    function loadStories() {
        storiesDiv.innerHTML = '<p class="empty-hint">加载中...</p>';
        fetchJson('/api/stories')
        .then(function (stories) {
            if (stories.length === 0) {
                storiesDiv.innerHTML = '<p class="empty-hint">暂无故事，去创建一个吧</p>';
                return;
            }
            storiesDiv.innerHTML = stories.map(function (s) {
                const date = new Date(s.lastModifiedMs).toLocaleString('zh-CN');
                const badge = s.activeJob ? '<span class="active-badge">生成中</span>' : '';
                // 还没有章节时禁用导出：后端会 404，前端提前拦掉更直观
                const exportDisabled = s.chapterCount > 0 ? '' : ' disabled title="还没有章节可导出"';
                return '<div class="story-card" data-dir="' + esc(s.storyDirName) + '">'
                    + '<div><div class="story-title">' + esc(s.novelTitle) + '</div>'
                    + '<div class="story-meta">' + s.chapterCount + ' 章 · ' + date + '</div>'
                    + '<div class="story-actions">'
                    + '<button type="button" class="btn-export" data-format="txt"' + exportDisabled + ' title="每章一个文件打包下载，解压即文件夹">导出TXT</button>'
                    + '<button type="button" class="btn-export" data-format="md"' + exportDisabled + ' title="每章一个 Markdown 文件打包下载，解压即文件夹">导出MD</button>'
                    + '<button type="button" class="btn-export" data-format="docx"' + exportDisabled + ' title="每章一个 Word 文件打包下载，解压即文件夹">导出Word</button>'
                    + '<button type="button" class="btn-export btn-danger" data-delete="1" data-title="' + esc(s.novelTitle)
                    + '" title="删除这本小说（正文、记忆、向量索引一并删除，不可恢复）">删除</button>'
                    + '</div></div>'
                    + badge + '</div>';
            }).join('');
            storiesDiv.querySelectorAll('.story-card').forEach(function (card) {
                card.addEventListener('click', function () {
                    currentStoryDir = card.dataset.dir;
                    loadChapters(currentStoryDir);
                });
            });
            // 导出按钮在整卡可点的卡片内部：stopPropagation 防止误触发进章节列表
            storiesDiv.querySelectorAll('.btn-export').forEach(function (btn) {
                btn.addEventListener('click', function (e) {
                    e.stopPropagation();
                    if (btn.dataset.delete) {
                        deleteStory(btn.closest('.story-card').dataset.dir, btn.dataset.title, btn);
                        return;
                    }
                    downloadBookExport(btn.closest('.story-card').dataset.dir, btn.dataset.format, btn);
                });
            });
        })
        .catch(function (err) {
            storiesDiv.innerHTML = '<p class="empty-hint">加载失败：' + esc(err.message) + '</p>';
        });
    }

    // 删除故事：二次确认（不可恢复）→ 后端删目录 + 清该故事的记忆向量集合 → 刷新列表。
    // 若当前正打开这本的章节视图，删完回到故事库（否则会停在一个已不存在的故事上）
    function deleteStory(dir, title, btn) {
        var name = title || dir;
        if (!confirm('确认删除《' + name + '》？\n\n'
                + '将删除这本小说的全部正文、章节记忆、蓝图账本与向量索引——'
                + '操作不可恢复，无法撤销。\n\n'
                + '如需留档，请先点「导出TXT/MD/Word」备份。')) {
            return;
        }
        if (btn) { btn.disabled = true; }
        fetchJson('/api/stories/' + encodeURIComponent(dir) + '/delete', { method: 'POST' })
        .then(function () {
            showToast('已删除：' + name);
            // 当前浏览的正是被删的故事：退回故事库，避免章节区停留在已删数据上
            if (currentStoryDir === dir) {
                currentStoryDir = null;
                chapterListDiv.classList.add('hidden');
                workbenchOverview.classList.add('hidden');
                libraryList.classList.remove('hidden');
            }
            loadStories();
        })
        .catch(function (err) {
            showToast('删除失败：' + err.message);
            if (btn) { btn.disabled = false; }
        });
    }

    // ===== 整本导出：不能走 fetchJson（它强制要求 JSON 响应），用原生 fetch + blob 触发下载 =====
    function downloadBookExport(storyDir, format, btn) {
        const url = API_BASE + '/api/stories/' + encodeURIComponent(storyDir) + '/export?format=' + encodeURIComponent(format);
        const original = btn.textContent;
        btn.disabled = true;
        btn.textContent = '导出中…';
        fetch(url).then(function (r) {
            if (!r.ok) {
                return r.json().catch(function () { return {}; }).then(function (body) {
                    throw new Error(body.message || body.error || ('导出失败（' + r.status + '）'));
                });
            }
            // 文件名取 RFC 5987 的 filename*（后端 ContentDisposition 以 UTF-8 输出，中文不乱码）
            const cd = r.headers.get('content-disposition') || '';
            const m = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(cd);
            const name = m ? decodeURIComponent(m[1]) : ('novel-export.' + format);
            return r.blob().then(function (blob) { return { blob: blob, name: name }; });
        }).then(function (pack) {
            const a = document.createElement('a');
            const objUrl = URL.createObjectURL(pack.blob);
            a.href = objUrl;
            a.download = pack.name;
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(function () { URL.revokeObjectURL(objUrl); }, 1000);
        }).catch(function (err) {
            alert('导出失败：' + err.message);
        }).finally(function () {
            btn.disabled = false;
            btn.textContent = original;
        });
    }

    function loadChapters(storyDir) {
        const token = ++storyViewToken;
        fetchJson('/api/stories/' + encodeURIComponent(storyDir) + '/chapters')
        .then(function (chapters) {
            if (token !== storyViewToken) return;   // 过期响应：用户已切到别的故事
            // 与“当前显示”绑定：保存/相邻章导航都读 currentStoryDir，不能只在 click 时赋值
            currentStoryDir = storyDir;
            chapterSequence = chapters.map(function (chapter) { return chapter.chapterNo; });
            libraryList.classList.add('hidden');
            chapterListDiv.classList.remove('hidden');
            workbenchOverview.classList.remove('hidden');
            // 故事概览与章节列表是同级工作区，概览始终排在章节列表之前。
            chapterListDiv.parentNode.insertBefore(workbenchOverview, chapterListDiv);
            chapterListTitle.textContent = '章节目录';
            if (chapters.length === 0) {
                chaptersDiv.innerHTML = '<p class="empty-hint">暂无章节</p>';
                // 不能直接 return：否则上一个故事的概览/账本/检查点留在页面上，
                // 点“回滚”会回滚到旧故事（按钮闭包仍指向旧 storyDir）。这里也要刷新一次
                loadWorkbench(storyDir);
                loadCheckpoints(storyDir);
                return;
            }
            chaptersDiv.innerHTML = chapters.map(function (c) {
                return '<div class="chapter-card" data-no="' + c.chapterNo + '">'
                    + '<span>第 ' + c.chapterNo + ' 章 ' + esc(c.title) + '</span>'
                    + '<span class="story-meta">' + c.contentLength + ' 字</span></div>';
            }).join('');
            chaptersDiv.querySelectorAll('.chapter-card').forEach(function (card) {
                card.addEventListener('click', function () {
                    loadChapter(storyDir, parseInt(card.dataset.no, 10));
                });
            });
            loadWorkbench(storyDir);
            loadCheckpoints(storyDir);
        })
        .catch(function (err) {
            showToast('加载章节失败：' + err.message);
        });
    }

    function loadWorkbench(storyDir) {
        workbenchMetrics.innerHTML = '<p class="empty-hint">概览加载中...</p>';
        fetchJson('/api/stories/' + encodeURIComponent(storyDir) + '/workbench')
        .then(function (data) {
            workbenchTitle.textContent = data.novelTitle || '故事概述';
            storyOverviewIntro.textContent = data.storyOverview || '暂无故事简介';
            renderStoryPlan(data);
            var m = data.metrics || {};
            workbenchMetrics.innerHTML = [
                metric('章节', data.chapterCount),
                metric('总字数', m.totalWords || 0),
                metric('平均章字数', m.averageChapterWords || 0),
                metric('待确认事实', m.pendingFactCount || 0),
                metric('未回收伏笔', m.unresolvedForeshadowCount || 0),
                metric('未解决质量债', m.unresolvedQualityDebtCount || 0),
                metric('一致性冲突', m.continuityConflictCount || 0)
            ].join('');
            renderLedger('workbench-characters', data.characters || []);
            renderLedger('workbench-items', (data.items || []).concat(data.factions || []));
            var risks = (data.foreshadowing || []).map(function (f) { return '第' + f.chapterNo + '章 · ' + f.content; })
                .concat((data.pendingFacts || []).map(function (f) { return '待确认 · ' + f.name + '：' + f.status; }))
                .concat((data.qualityDebts || []).filter(function (d) { return !d.resolved; }).map(function (d) { return '质量债 · 第' + d.chapterNo + '章'; }));
            document.getElementById('workbench-risks').innerHTML = risks.length ? risks.slice(0, 12).map(function (x) { return '<div class="compact-row">' + esc(x) + '</div>'; }).join('') : '<p class="empty-hint">暂无待处理项</p>';
        })
        .catch(function (err) { workbenchMetrics.innerHTML = '<p class="empty-hint">加载失败：' + esc(err.message) + '</p>'; });
    }

    // ===== 检查点与回滚 =====
    const checkpointListDiv = document.getElementById('checkpoint-list');
    const checkpointStatus = document.getElementById('checkpoint-status');
    const btnNewCheckpoint = document.getElementById('btn-new-checkpoint');
    const btnRefreshCheckpoints = document.getElementById('btn-refresh-checkpoints');

    function cpStatus(msg, type) {
        checkpointStatus.textContent = msg;
        checkpointStatus.classList.remove('hidden');
        checkpointStatus.classList.toggle('has-config', type === 'ok' || type === 'error');
        checkpointStatus.style.borderColor = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
        checkpointStatus.style.color = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
    }

    function formatCpTime(ms) {
        if (!ms) return '';
        var d = new Date(ms);
        function pad(n) { return n < 10 ? '0' + n : '' + n; }
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
            + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    function loadCheckpoints(storyDir) {
        checkpointListDiv.innerHTML = '<p class="empty-hint">加载中...</p>';
        fetchJson('/api/stories/' + encodeURIComponent(storyDir) + '/checkpoints')
        .then(function (list) {
            if (!list || list.length === 0) {
                checkpointListDiv.innerHTML = '<p class="empty-hint">暂无检查点，进程自动打点后或手动快照后出现</p>';
                return;
            }
            checkpointListDiv.innerHTML = list.map(function (cp) {
                var typeTag = cp.type === 'MANUAL' ? '<span class="cp-version" style="color:var(--gold);border-color:var(--gold)">MANUAL</span>'
                    : '<span class="cp-version">AUTO</span>';
                var currentTag = cp.current ? '<span class="cp-current">● 当前</span>' : '';
                var btn = cp.current ? '' : '<button class="btn-secondary" data-restore="' + esc(cp.checkpointId) + '">回 滚</button>';
                return '<div class="cp-item">'
                    + '<div class="cp-item-head">'
                    + '<span class="cp-version">v' + cp.versionNo + '</span>'
                    + typeTag + currentTag
                    + '<span class="cp-name">' + esc(cp.name || '（未命名）') + '</span>'
                    + '</div>'
                    + '<div class="cp-item-meta">' + cp.chapterCount + ' 章 · ' + cp.fileCount + ' 文件 · ' + formatCpTime(cp.createdAtMs) + '</div>'
                    + (btn ? '<div>' + btn + '</div>' : '')
                    + '</div>';
            }).join('');
            checkpointListDiv.querySelectorAll('[data-restore]').forEach(function (btn) {
                btn.addEventListener('click', function () { restoreCheckpoint(storyDir, btn.dataset.restore); });
            });
        })
        .catch(function (err) {
            checkpointListDiv.innerHTML = '<p class="empty-hint">加载失败：' + esc(err.message) + '</p>';
        });
    }

    function restoreCheckpoint(storyDir, checkpointId) {
        var item = document.querySelector('[data-restore="' + checkpointId + '"]');
        if (!confirm('确认回滚到该检查点？\n\n此操作将把章节、蓝图、账本恢复到该点，并裁剪之后的全部章节（破坏性操作，无法撤销）。建议先打一个当前快照。')) return;
        if (item) item.disabled = true;
        fetchJson('/api/stories/' + encodeURIComponent(storyDir) + '/checkpoints/' + encodeURIComponent(checkpointId) + '/restore', { method: 'POST' })
        .then(function () {
            cpStatus('回滚完成，已刷新概览', 'ok');
            loadCheckpoints(storyDir);
            loadWorkbench(storyDir);
            loadChapters(storyDir);
        })
        .catch(function (err) {
            cpStatus('回滚失败：' + err.message, 'error');
            if (item) item.disabled = false;
        });
    }

    btnRefreshCheckpoints.addEventListener('click', function () {
        if (currentStoryDir) {
            cpStatus('', undefined);
            checkpointStatus.classList.add('hidden');
            loadCheckpoints(currentStoryDir);
        }
    });

    btnNewCheckpoint.addEventListener('click', function () {
        if (!currentStoryDir) return;
        var name = prompt('给这个快照起个名字（必填）：', '');
        if (name === null) return;
        name = name.trim();
        if (!name) { cpStatus('快照名称不能为空', 'error'); return; }
        fetchJson('/api/stories/' + encodeURIComponent(currentStoryDir) + '/checkpoints', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: name })
        })
        .then(function () {
            cpStatus('快照已创建', 'ok');
            loadCheckpoints(currentStoryDir);
        })
        .catch(function (err) { cpStatus('创建快照失败：' + err.message, 'error'); });
    });

    function renderStoryPlan(data) {
        var el = document.getElementById('story-plan');
        if (!el) return;
        var current = data.latestChapterNo || data.chapterCount || 0;
        var total = data.estimatedTotalChapters;
        var min = data.estimatedRemainingChaptersMin;
        var max = data.estimatedRemainingChaptersMax;
        var phase = data.storyPhase || 'NORMAL';
        var phaseNames = { NORMAL: '常规推进', PREPARATION: '终局准备', ESCALATION: '冲突升级', WAR: '决战阶段', RESOLUTION: '收束阶段', EPILOGUE: '尾声' };
        var estimate = total ? ('预计总章数约 ' + total + ' 章') : '预计总章数尚未形成';
        // 以预计总章数减当前实际章节数为准，避免滚动蓝图中旧的剩余章数滞后。
        var remaining = '剩余章数待下一版蓝图校准';
        if (total != null && current != null) {
            remaining = '预计还剩 ' + Math.max(0, Number(total) - Number(current)) + ' 章';
        } else if (min != null && max != null) {
            remaining = min === max ? ('预计还剩 ' + min + ' 章') : ('预计还剩 ' + min + '～' + max + ' 章');
        }
        var html = '<div class="story-plan-head"><b>篇幅与完结进度</b><span class="story-phase">' + esc(phaseNames[phase] || phase) + '</span></div>'
            + '<div class="story-plan-line">' + esc(estimate) + ' · 当前第 ' + esc(String(current)) + ' 章 · ' + esc(remaining) + '</div>';
        var done = data.completedFinaleBeats || [];
        var todo = data.remainingFinaleBeats || [];
        if (data.finalVolumeDeclared || done.length || todo.length) {
            html += '<div class="finale-label">' + (data.finalVolumeDeclared ? '收官卷已声明' : '终局节点') + '</div>';
            html += '<div class="finale-beats">';
            done.forEach(function (beat) { html += '<span class="beat done">已完成 · ' + esc(beat) + '</span>'; });
            todo.forEach(function (beat) { html += '<span class="beat">待完成 · ' + esc(beat) + '</span>'; });
            html += '</div>';
        }
        el.innerHTML = html;
    }

    function metric(label, value, hint) {
        return '<div class="metric-card"><strong>' + esc(String(value)) + '</strong><span>' + esc(label)
            + (hint ? ' · ' + esc(String(hint)) : '') + '</span></div>';
    }
    function renderLedger(id, entries) {
        var el = document.getElementById(id);
        el.innerHTML = entries.length ? entries.slice(0, 16).map(function (x) {
            return '<div class="compact-row"><b>' + esc(x.name || '') + '</b><span>' + esc(x.status || '未记录') + '</span></div>';
        }).join('') : '<p class="empty-hint">暂无记录</p>';
    }

    function loadChapter(storyDir, chapterNo) {
        const token = ++storyViewToken;
        fetchJson('/api/stories/' + encodeURIComponent(storyDir) + '/chapters/' + chapterNo)
        .then(function (ch) {
            if (token !== storyViewToken) return;   // 过期响应：已切到别的故事/章节
            // 打开的章属于哪个故事，currentStoryDir 就必须是哪个——保存读写都以它为准
            currentStoryDir = storyDir;
            chapterViewDiv.classList.remove('editing');
            chapterListDiv.classList.add('hidden');
            workbenchOverview.classList.add('hidden');
            chapterViewDiv.classList.remove('hidden');
            chapterViewTitle.textContent = '第 ' + ch.chapterNo + ' 章 ' + ch.title;
            contentReadonly.textContent = ch.content;
            currentChapterNo = ch.chapterNo;
            btnEdit.classList.remove('hidden');
            btnSave.classList.add('hidden');
            btnCancelEdit.classList.add('hidden');
            contentEdit.classList.add('hidden');
            contentReadonly.classList.remove('hidden');
            saveResult.classList.add('hidden');
            updateChapterNavigation();
            // 章节切换后回到新章节开头，避免沿用上一章的滚动位置。
            chapterViewTitle.scrollIntoView({ behavior: 'auto', block: 'start' });
        })
        .catch(function (err) {
            showToast('加载章节失败：' + err.message);
        });
    }

    // ===== 模型接入设置 =====
    const llmConfigForm = document.getElementById('llm-config-form');
    const llmStatusDiv = document.getElementById('llm-config-status');
    const btnSaveLlmConfig = document.getElementById('btn-save-llm-config');
    const btnResetLlmConfig = document.getElementById('btn-reset-llm-config');
    const inputBaseUrl = document.getElementById('llm_baseUrl');
    const inputApiKey = document.getElementById('llm_apiKey');
    const inputModel = document.getElementById('llm_model');
    const inputMaxTokens = document.getElementById('llm_maxTokens');
    const inputAuthMode = document.getElementById('llm_authMode');
    const chatGptPanel = document.getElementById('chatgpt-auth-panel');
    const chatGptStatus = document.getElementById('chatgpt-auth-status');
    const btnChatGptLogin = document.getElementById('btn-chatgpt-login');
    const btnChatGptLogout = document.getElementById('btn-chatgpt-logout');
    const chatGptModelOptions = document.getElementById('chatgpt-model-options');
    const sceneMatrixDiv = document.getElementById('scene-matrix');
    const sceneUnifiedNote = document.getElementById('scene-unified-note');
    const sceneStatusDiv = document.getElementById('scene-config-status');
    const btnSaveSceneConfig = document.getElementById('btn-save-scene-config');
    const btnResetSceneConfig = document.getElementById('btn-reset-scene-config');
    const inputSceneApiEnabled = document.getElementById('scene_api_enabled');
    const sceneApiHint = document.getElementById('scene-api-hint');
    // 中转站（供应商注册表）：全局下拉选用 + 独立管理卡
    const inputProvider = document.getElementById('llm_provider');
    const providerListDiv = document.getElementById('provider-list');
    const providerStatusDiv = document.getElementById('provider-status');
    const inputProviderName = document.getElementById('provider_name');
    const inputProviderBaseUrl = document.getElementById('provider_baseUrl');
    const inputProviderApiKey = document.getElementById('provider_apiKey');
    const btnSaveProvider = document.getElementById('btn-save-provider');
    const btnProviderCancel = document.getElementById('btn-cancel-provider');
    const btnTestProvider = document.getElementById('btn-test-provider');
    /** 编辑态：正在改的中转站 id（null=新增） */
    let editingProviderId = null;
    /** 已保存的中转站（id → {name, baseUrl, hasKey, apiKeyMasked}），全表提交的底稿 */
    let savedProviders = [];

    function providerStatus(msg, type) {
        providerStatusDiv.textContent = msg;
        providerStatusDiv.classList.remove('hidden');
        providerStatusDiv.classList.toggle('has-config', type === 'ok' || type === 'error');
        providerStatusDiv.style.borderColor = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
        providerStatusDiv.style.color = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
    }

    function renderProviders(data) {
        savedProviders = data.providers || [];
        // 全局下拉：手动 + 各中转站（后端 providerId 为 null 时显示手动）
        inputProvider.innerHTML = '<option value="">手动（使用下方地址密钥）</option>'
            + savedProviders.map(function (p) {
                return '<option value="' + esc(p.id) + '">' + esc(p.name || p.id) + '（' + esc(p.baseUrl || '') + '）</option>';
            }).join('');
        inputProvider.value = data.providerId || '';
        renderProviderList();
    }

    function renderProviderList() {
        if (!providerListDiv) return;
        if (!savedProviders.length) {
            providerListDiv.innerHTML = '<p class="empty-hint">还没有登记中转站。填好名称与地址后点「添加 / 更新中转站」。</p>';
            return;
        }
        providerListDiv.innerHTML = savedProviders.map(function (p) {
            const keyText = p.hasKey ? 'Key：' + (p.apiKeyMasked || '已掩码') : '未配 Key';
            const selected = inputProvider.value === p.id ? ' <span class="scene-badge scene-badge-on">全局使用中</span>' : '';
            return '<div class="scene-row asset-row-provider" data-provider-id="' + esc(p.id) + '">'
                + '<div class="scene-name"><b>' + esc(p.name || p.id) + '</b>'
                + '<span class="scene-key">' + esc(p.baseUrl || '') + '</span>' + selected + '</div>'
                + '<div class="scene-static"><span class="mono">' + esc(keyText) + '</span></div>'
                + '<button type="button" class="btn-secondary scene-clear" data-act="fetch">获取模型</button>'
                + '<button type="button" class="btn-secondary scene-clear" data-act="edit">编辑</button>'
                + '<button type="button" class="btn-secondary scene-clear" data-act="delete">删除</button>'
                + '</div>';
        }).join('');
        providerListDiv.querySelectorAll('[data-act]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                const row = btn.closest('[data-provider-id]');
                const id = row.getAttribute('data-provider-id');
                const act = btn.getAttribute('data-act');
                if (act === 'fetch') fetchProviderModels(id);
                if (act === 'edit') startEditProvider(id);
                if (act === 'delete') deleteProvider(id);
            });
        });
    }

    /** 全表提交注册表：editingProviderId 的条目带表单值（apiKey 留空=保留现有），其余条目原样回传（不带 apiKey=保留） */
    function saveProvider() {
        const name = inputProviderName.value.trim();
        const baseUrl = inputProviderBaseUrl.value.trim();
        const apiKey = inputProviderApiKey.value.trim();
        if (!baseUrl) { providerStatus('Base URL 不能为空', 'error'); return; }
        const id = editingProviderId || ('p-' + Date.now().toString(36));
        const providers = {};
        savedProviders.forEach(function (p) {
            if (p.id === id) return;   // 编辑态：旧条目由下方新值整体顶替
            providers[p.id] = { name: p.name || p.id, baseUrl: p.baseUrl };
        });
        providers[id] = { name: name || id, baseUrl: baseUrl };
        if (apiKey) providers[id].apiKey = apiKey;
        btnSaveProvider.disabled = true;
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ providers: providers })
        })
        .then(function (data) {
            renderProviders(data);
            exitProviderEditing();
            providerStatus('中转站已保存：' + (name || id) + '。到上方「模型接入」的中转站下拉选用它。', 'ok');
            showToast('中转站已保存');
        })
        .catch(function (err) { providerStatus('保存失败：' + err.message, 'error'); })
        .finally(function () { btnSaveProvider.disabled = false; });
    }

    /** 退出编辑态回「新增」模式：清表单、复位按钮、隐藏取消。保存成功与取消编辑共用 */
    function exitProviderEditing() {
        editingProviderId = null;
        inputProviderName.value = '';
        inputProviderBaseUrl.value = '';
        inputProviderApiKey.value = '';
        inputProviderApiKey.placeholder = 'sk-...';
        btnSaveProvider.textContent = '添加中转站';
        btnProviderCancel.classList.add('hidden');
    }

    function startEditProvider(id) {
        const p = savedProviders.find(function (x) { return x.id === id; });
        if (!p) return;
        editingProviderId = id;
        inputProviderName.value = p.name || '';
        inputProviderBaseUrl.value = p.baseUrl || '';
        inputProviderApiKey.value = '';
        inputProviderApiKey.placeholder = p.hasKey ? '已配置（' + (p.apiKeyMasked || '已掩码') + '），留空保留' : 'sk-...';
        // 编辑态显式化：保存按钮只管改这一条，想加新站先「取消编辑」——
        // 否则"以为是添加、实际顶掉了被编辑条目"，列表看起来就像不能同时存多个中转站
        btnSaveProvider.textContent = '保存修改（' + (p.name || id) + '）';
        btnProviderCancel.classList.remove('hidden');
        providerStatus('正在编辑 ' + (p.name || id) + '：改完点「保存修改」。要添加新中转站请先点「取消编辑」。', 'ok');
    }

    function deleteProvider(id) {
        const p = savedProviders.find(function (x) { return x.id === id; });
        if (!p) return;
        if (!window.confirm('删除中转站「' + (p.name || id) + '」？它的地址密钥将从本机移除。')) return;
        const providers = {};
        savedProviders.forEach(function (x) {
            if (x.id !== id) providers[x.id] = { name: x.name || x.id, baseUrl: x.baseUrl };
        });
        // 被删的正是全局引用：连同引用一起清（后端对缺席条目按全量替换处理，悬空引用会收敛）
        const body = { providers: providers };
        if (inputProvider.value === id) body.providerId = '';
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        })
        .then(function (data) {
            renderProviders(data);
            providerStatus('中转站已删除：' + (p.name || id), 'ok');
            showToast('中转站已删除');
        })
        .catch(function (err) { providerStatus('删除失败：' + err.message, 'error'); });
    }

    /** 模型下拉选项：中转站获取到的模型名合并进共享 datalist（全局/场景/兜底输入框共用） */
    function setModelOptions(models) {
        const existing = {};
        Array.from(chatGptModelOptions.options).forEach(function (o) { existing[o.value] = true; });
        (models || []).forEach(function (m) {
            if (existing[m]) return;
            const opt = document.createElement('option');
            opt.value = m;
            chatGptModelOptions.appendChild(opt);
            existing[m] = true;
        });
    }

    /** 拉取模型列表：providerId 取已保存凭据；也可传显式 baseUrl/apiKey（保存前试连） */
    function fetchProviderModels(providerId, baseUrl, apiKey) {
        const body = {};
        if (providerId) body.providerId = providerId;
        if (baseUrl) body.baseUrl = baseUrl;
        if (apiKey) body.apiKey = apiKey;
        const who = providerId
            ? ((savedProviders.find(function (x) { return x.id === providerId; }) || {}).name || providerId)
            : (baseUrl || '当前地址');
        providerStatus('正在从 ' + who + ' 获取模型列表…');
        return fetchJson('/api/config/llm/models', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        })
        .then(function (models) {
            setModelOptions(models);
            providerStatus('已获取 ' + models.length + ' 个模型（' + who + '），已并入全局/场景/兜底的下拉选项', 'ok');
            showToast('获取到 ' + models.length + ' 个模型');
            return models;
        })
        .catch(function (err) {
            providerStatus('获取失败（' + who + '）：' + err.message, 'error');
            showToast('模型列表获取失败：' + err.message);
        });
    }

    if (btnSaveProvider) btnSaveProvider.addEventListener('click', saveProvider);
    if (btnProviderCancel) btnProviderCancel.addEventListener('click', function () {
        exitProviderEditing();
        providerStatus('已取消编辑，回到新增模式', 'ok');
    });
    if (btnTestProvider) btnTestProvider.addEventListener('click', function () {
        const baseUrl = inputProviderBaseUrl.value.trim();
        if (!baseUrl) { providerStatus('试连前先填 Base URL', 'error'); return; }
        fetchProviderModels(null, baseUrl, inputProviderApiKey.value.trim());
    });
    if (inputProvider) inputProvider.addEventListener('change', function () {
        if (inputProvider.value) {
            llmStatusDiv.className = 'config-status';
            llmStatusDiv.textContent = '已选择中转站，点「保 存 配 置」后全局地址密钥按该站生效';
        }
    });

    function renderLlmConfig(data) {
        inputAuthMode.value = data.authMode || 'api-key';
        inputBaseUrl.value = data.baseUrl || '';
        inputApiKey.value = '';
        inputModel.value = data.model || '';
        inputMaxTokens.value = data.maxTokens != null ? data.maxTokens : '';
        renderAuthMode();
        renderProviders(data);
        if (!data.configured) {
            llmStatusDiv.className = 'config-status';
            llmStatusDiv.textContent = '当前无运行时覆盖，调用全部走本地 yml 静态配置';
        } else {
            llmStatusDiv.className = 'config-status has-config';
            const keyText = data.hasKey ? 'Key 已配置（' + (data.apiKeyMasked || '已掩码') + '）' : 'Key 未单独覆盖（沿用静态配置）';
            llmStatusDiv.textContent = '运行时覆盖已生效：Base URL=' + (data.baseUrl || '沿用静态')
                + ' · 模型=' + (data.model || '沿用静态') + ' · ' + keyText;
        }
        renderSceneMatrix(data);
        renderFallback(data);
        renderFeatureToggles(data.toggles);
        renderEmbedding(data.embedding);
        renderReferenceMode(data);
    }

    // ===== 功能开关（质量/速度档：运行时覆盖 yml 的 enabled 开关）=====
    // 思考模型建议：这些场景每批/每章必调、对延迟敏感，强制思考模型（glm-5.3/kimi-k3 一类无法关思考）
    // 会因思考失控烧穿输出预算（实测 glm-5.3 在 chapter-plan 连烧 32k token 零产出）
    const THINKING_SENSITIVE_SCENES = {
        'chapter-plan': '⚑ 建议非思考模型（思考失控会烧穿 32k 预算）',
        'chapter-beats': '⚑ 高频小输出，建议非思考模型',
        'chapter-content': '⚑ 长篇输出，建议非思考模型',
        'chapter-rewrite': '⚑ 整章重写，思考模型单次可达 20 分钟',
        'summary': '⚑ 压缩任务，建议非思考模型',
        'ref-select': '⚑ 小输出选择任务，建议非思考模型'
    };

    const featureTogglesDiv = document.getElementById('feature-toggles');
    const toggleStatus = document.getElementById('toggle-status');
    const btnSaveToggles = document.getElementById('btn-save-toggles');
    const btnResetToggles = document.getElementById('btn-reset-toggles');

    function renderFeatureToggles(toggles) {
        if (!featureTogglesDiv) return;
        if (!toggles || toggles.length === 0) {
            featureTogglesDiv.innerHTML = '<p class="empty-hint">后端未返回开关定义</p>';
            return;
        }
        featureTogglesDiv.innerHTML = toggles.map(function (t) {
            const overridden = t.overrideValue !== null && t.overrideValue !== undefined;
            const badge = overridden
                ? '<span class="scene-badge scene-badge-on">已覆盖</span>' : '';
            return '<label class="toggle-row' + (t.effectiveValue ? '' : ' toggle-off') + '" data-toggle-key="' + esc(t.key) + '">'
                + '<input type="checkbox" class="toggle-input"' + (t.effectiveValue ? ' checked' : '') + '>'
                + '<span class="toggle-main">'
                + '<span class="toggle-title">' + esc(t.label) + badge + '</span>'
                + '<span class="toggle-desc">' + esc(t.description) + '</span>'
                + '</span>'
                + '<span class="toggle-cost">' + esc(t.costHint || '') + '</span>'
                + '</label>';
        }).join('');
    }

    function collectToggles() {
        const out = {};
        featureTogglesDiv.querySelectorAll('[data-toggle-key]').forEach(function (row) {
            out[row.getAttribute('data-toggle-key')] = row.querySelector('.toggle-input').checked;
        });
        return out;
    }

    function saveToggles(toggles) {
        btnSaveToggles.disabled = true;
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ toggles: toggles })
        })
        .then(function (data) {
            renderLlmConfig(data);
            toggleStatus.className = 'config-status has-config';
            toggleStatus.classList.remove('hidden');
            toggleStatus.textContent = '开关已保存并立即生效（下一章开始按新设置执行）';
        })
        .catch(function (err) {
            toggleStatus.className = 'config-status';
            toggleStatus.classList.remove('hidden');
            toggleStatus.textContent = '保存失败：' + err.message;
        })
        .finally(function () { btnSaveToggles.disabled = false; });
    }

    if (btnSaveToggles) {
        btnSaveToggles.addEventListener('click', function () { saveToggles(collectToggles()); });
    }
    if (btnResetToggles) {
        // 空表 = 清空全部开关覆盖 → 回退 yml（程序内置）静态值
        btnResetToggles.addEventListener('click', function () { saveToggles({}); });
    }

    // ===== 资料检索方式开关（运行时覆盖 llm|vector）=====
    const referenceModeForm = document.getElementById('reference-mode-form');
    const inputReferenceMode = document.getElementById('reference_mode');
    const referenceModeStatus = document.getElementById('reference-mode-status');
    const btnSaveReferenceMode = document.getElementById('btn-save-reference-mode');

    function renderReferenceMode(data) {
        if (!referenceModeForm) return;
        // 直接回显生效值（覆盖优先，未覆盖= yml 静态值）——用户只面对 vector/llm 二选一
        inputReferenceMode.value = data.effectiveReferenceMode || 'vector';
        referenceModeStatus.className = 'config-status';
        referenceModeStatus.classList.remove('hidden');
        referenceModeStatus.textContent = data.referenceMode
            ? '运行时覆盖生效中（保存前可改回另一方式）'
            : '当前按默认配置运行，改动后保存即生效';
    }

    if (referenceModeForm) {
        referenceModeForm.addEventListener('submit', function (e) {
            e.preventDefault();
            btnSaveReferenceMode.disabled = true;
            fetchJson('/api/config/llm', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ referenceMode: inputReferenceMode.value })  // 空串=清除覆盖回退 yml
            })
            .then(function (data) {
                renderLlmConfig(data);
                showToast('资料检索方式已保存并生效');
            })
            .catch(function (err) {
                referenceModeStatus.className = 'config-status';
                referenceModeStatus.textContent = '保存失败：' + err.message;
                showToast('保存失败：' + err.message);
            })
            .finally(function () { btnSaveReferenceMode.disabled = false; });
        });
    }

    function renderAuthMode() {
        const chatGpt = inputAuthMode.value === 'chatgpt';
        chatGptPanel.classList.toggle('hidden', !chatGpt);
        inputBaseUrl.disabled = chatGpt;
        inputApiKey.disabled = chatGpt;
        inputMaxTokens.disabled = chatGpt;
        inputProvider.disabled = chatGpt;
        if (chatGpt) loadChatGptStatus();
    }

    function loadChatGptStatus() {
        fetchJson('/api/auth/chatgpt/status').then(function (status) {
            btnChatGptLogin.classList.toggle('hidden', status.authorized);
            btnChatGptLogout.classList.toggle('hidden', !status.authorized);
            chatGptStatus.className = 'config-status' + (status.authorized ? ' has-config' : '');
            chatGptStatus.textContent = status.authorized
                ? '已登录 ChatGPT：' + (status.email || '账号已授权')
                : '尚未登录。点击 Continue with ChatGPT 在浏览器中授权。';
            if (status.authorized) loadChatGptModels();
        }).catch(function (err) {
            chatGptStatus.className = 'config-status';
            chatGptStatus.textContent = 'ChatGPT 登录状态读取失败：' + err.message;
        });
    }

    function loadChatGptModels() {
        fetchJson('/api/auth/chatgpt/models').then(function (models) {
            chatGptModelOptions.innerHTML = (models || []).map(function (model) {
                return '<option value="' + esc(model.slug) + '">' + esc(model.displayName || model.slug) + '</option>';
            }).join('');
            const slugs = (models || []).map(function (model) { return model.slug; });
            if (slugs.length && slugs.indexOf(inputModel.value.trim()) < 0) {
                inputModel.value = slugs[0];
                chatGptStatus.textContent += ' · 已选择可用模型 ' + slugs[0] + '，请保存配置。';
            }
        }).catch(function (err) {
            chatGptStatus.textContent += ' · 模型列表读取失败：' + err.message;
        });
    }

    // 场景模型矩阵：静态生效值 + 覆盖输入（留空=清除该字段覆盖，整表提交后端全量替换）
    // 显式清除集合：本行「恢复」点击后，把只有不可见密钥的场景记在这里，
    // 保存时发送 apiKey 空串命中后端“显式清除”语义；填入任何值即撤销清除意图
    let sceneKeyClears = {};
    function renderSceneMatrix(data) {
        if (!data.scenes || data.scenes.length === 0) {
            sceneMatrixDiv.innerHTML = '<p class="empty-hint">后端未返回场景配置（StoryProperties.module 未装配）</p>';
            sceneUnifiedNote.classList.add('hidden');
            return;
        }
        // 场景级 API 开关：null/true=打开（场景可自带地址密钥），false=强制全局
        const apiOn = data.sceneApiEnabled !== false;
        inputSceneApiEnabled.checked = apiOn;
        sceneApiHint.classList.toggle('scene-api-hint-off', !apiOn);
        if (data.unifiedModelEnabled) {
            sceneUnifiedNote.textContent = '分场景路由总开关当前为开启（yml: unified-model-enabled=true）：所有场景统一使用 chat-model，'
                + '此处场景覆盖仍会在统一模型之上生效。';
            sceneUnifiedNote.classList.remove('hidden');
        } else {
            sceneUnifiedNote.classList.add('hidden');
        }
        const header = '<div class="scene-row scene-head">'
            + '<span>场景</span><span>当前生效</span><span>覆盖 · 模型</span>'
            + '<span>Tokens</span><span>温度</span><span>Base URL</span>'
            + '<span>API Key</span><span></span>'
            + '</div>';
        const rows = data.scenes.map(function (s) {
            // 静态模型统一小写展示：yml 里混有 DeepSeek-V4-Pro / qwen3.8-flash 两种写法，
            // 同一张表里大小写不一致会被误读成"两个不同的模型"；供应商侧模型名不区分大小写，
            // 覆盖输入框同样不收窄写（用户填什么存什么）
            const staticText = s.staticModel ? String(s.staticModel).toLowerCase() : '未配置';
            const staticMeta = [];
            if (s.staticMaxTokens != null) staticMeta.push(String(s.staticMaxTokens) + 'tk');
            if (s.staticTemperature != null) staticMeta.push('t=' + s.staticTemperature);
            const badges = (s.independentApi ? '<span class="scene-badge scene-badge-api">独立API</span>' : '')
                + (s.sceneConfigured ? '' : '<span class="scene-badge">回落统一</span>')
                + (s.overridden ? '<span class="scene-badge scene-badge-on">已覆盖</span>' : '');
            const disabledAttr = apiOn ? '' : ' disabled';
            const thinkingHint = THINKING_SENSITIVE_SCENES[s.key] || '';
            return '<div class="scene-row" data-scene-key="' + esc(s.key) + '">'
                + '<div class="scene-name"><b>' + esc(s.label || s.key) + '</b>'
                + '<span class="scene-key">' + esc(s.key) + '</span>' + badges
                + (thinkingHint ? '<span class="scene-hint">' + esc(thinkingHint) + '</span>' : '')
                + '</div>'
                + '<div class="scene-static"><span class="mono">' + esc(staticText) + '</span>'
                + '<span class="scene-static-meta">' + esc(staticMeta.join(' · ')) + '</span></div>'
                + '<input type="text" class="scene-input" data-field="model" list="chatgpt-model-options" placeholder="留空 = 跟随全局" autocomplete="off"'
                + (thinkingHint ? ' title="' + esc(thinkingHint) + '"' : '') + '>'
                + '<input type="number" class="scene-input" data-field="maxTokens" min="1" placeholder="留空回退" autocomplete="off">'
                + '<input type="number" class="scene-input" data-field="temperature" min="0" max="1" step="0.1" placeholder="留空回退" autocomplete="off">'
                + '<input type="text" class="scene-input" data-field="baseUrl" placeholder="留空回退" autocomplete="off"' + disabledAttr + '>'
                + '<input type="password" class="scene-input" data-field="apiKey" placeholder="留空保留" autocomplete="new-password"' + disabledAttr + '>'
                + '<button type="button" class="btn-secondary scene-clear" title="清空本行覆盖输入">恢复</button>'
                + '</div>';
        }).join('');
        sceneMatrixDiv.innerHTML = header + rows;
        // 输入值用 property 赋值回填（innerHTML 属性转义不可靠），并绑定行内恢复按钮
        sceneKeyClears = {};
        data.scenes.forEach(function (s) {
            const rowEl = sceneMatrixDiv.querySelector('[data-scene-key="' + cssEscape(s.key) + '"]');
            if (!rowEl) return;
            rowEl.querySelector('[data-field="model"]').value = s.overrideModel || '';
            rowEl.querySelector('[data-field="maxTokens"]').value = s.overrideMaxTokens != null ? s.overrideMaxTokens : '';
            rowEl.querySelector('[data-field="temperature"]').value = s.overrideTemperature != null ? s.overrideTemperature : '';
            const baseInput = rowEl.querySelector('[data-field="baseUrl"]');
            const keyInput = rowEl.querySelector('[data-field="apiKey"]');
            baseInput.value = s.overrideBaseUrl || '';
            baseInput.placeholder = apiOn
                ? ('留空沿用静态：' + (s.independentApi ? '本场景 yml 独立地址' : '全局地址'))
                : '开关关闭：强制全局地址';
            keyInput.value = '';
            // 密钥不回显：已配置时以占位提示（再次提交留空=保留现有覆盖）
            keyInput.placeholder = s.overrideHasKey
                ? ('已覆盖（' + (s.overrideApiKeyMasked || '已掩码') + '），留空保留')
                : '留空沿用静态';
            rowEl.querySelector('.scene-clear').addEventListener('click', function () {
                rowEl.querySelectorAll('.scene-input').forEach(function (inp) { inp.value = ''; });
                // 该场景只剩一个看不见的密钥覆盖时，输入框本来就是空的——「恢复」此时是
                // 用户移除它的唯一途径：记入显式清除集合，保存时发 apiKey 空串让后端删掉
                if (s.overrideHasKey && s.key) {
                    sceneKeyClears[s.key] = true;
                    showToast('场景「' + (s.label || s.key) + '」的密钥覆盖将在保存时清除');
                }
            });
            // 用户又动了本行任何一个输入 = 撤销「恢复」的清除意图，按正常编辑提交
            rowEl.querySelectorAll('.scene-input').forEach(function (inp) {
                inp.addEventListener('input', function () { delete sceneKeyClears[s.key]; });
            });
        });
    }

    function cssEscape(s) {
        if (window.CSS && CSS.escape) return CSS.escape(s);
        return String(s).replace(/([^a-zA-Z0-9_\u00A0-\uFFFF-])/g, '\\$1');
    }

    function sceneStatus(msg, type) {
        sceneStatusDiv.textContent = msg;
        sceneStatusDiv.classList.remove('hidden');
        sceneStatusDiv.classList.toggle('has-config', type === 'ok' || type === 'error');
        sceneStatusDiv.style.borderColor = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
        sceneStatusDiv.style.color = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
    }

    function saveSceneOverrides() {
        const scenes = {};
        sceneMatrixDiv.querySelectorAll('.scene-row[data-scene-key]').forEach(function (rowEl) {
            const key = rowEl.getAttribute('data-scene-key');
            const model = rowEl.querySelector('[data-field="model"]').value.trim();
            const maxTokens = rowEl.querySelector('[data-field="maxTokens"]').value.trim();
            const temperature = rowEl.querySelector('[data-field="temperature"]').value.trim();
            const baseUrl = rowEl.querySelector('[data-field="baseUrl"]').value.trim();
            const apiKey = rowEl.querySelector('[data-field="apiKey"]').value.trim();
            if (!model && !maxTokens && !temperature && !baseUrl && !apiKey) {
                // 整行留空 = 该场景清除覆盖。但密钥不回显：本行若只剩一个看不见的密钥覆盖，
                // 必须发 apiKey 空串显式清除（后端对“不在表里”的场景另有不可见字段保护，删不掉它）
                if (sceneKeyClears[key]) scenes[key] = { apiKey: '' };
                return;
            }
            const entry = {};
            if (model) entry.model = model;
            if (maxTokens) entry.maxTokens = parseInt(maxTokens, 10);
            if (temperature !== '') entry.temperature = parseFloat(temperature);
            if (baseUrl) entry.baseUrl = baseUrl;
            // 密钥只在用户真的填了新值时提交（留空=后端保留该场景现有密钥覆盖，不回显也不清空）
            if (apiKey) entry.apiKey = apiKey;
            scenes[key] = entry;
        });
        btnSaveSceneConfig.disabled = true;
        // 开关状态一并提交（null/true=打开，false=强制全局）
        const body = { scenes: scenes, sceneApiEnabled: inputSceneApiEnabled.checked };
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        })
        .then(function (data) {
            renderLlmConfig(data);
            const count = Object.keys(scenes).length;
            sceneStatus('场景覆盖已保存：' + count + ' 个场景生效覆盖，其余回退下层配置；场景级 API='
                + (inputSceneApiEnabled.checked ? '开' : '关（全部走全局地址密钥）'), 'ok');
            showToast('场景模型覆盖已保存并生效');
        })
        .catch(function (err) {
            sceneStatus('场景覆盖保存失败：' + err.message, 'error');
            showToast('场景覆盖保存失败：' + err.message);
        })
        .finally(function () { btnSaveSceneConfig.disabled = false; });
    }

    function resetSceneOverrides() {
        btnResetSceneConfig.disabled = true;
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ resetScenes: true })
        })
        .then(function (data) {
            renderLlmConfig(data);
            sceneStatus('场景覆盖已全部清除，全部场景回退全局覆盖 / yml 静态配置', 'ok');
            showToast('场景覆盖已清除');
        })
        .catch(function (err) {
            sceneStatus('场景覆盖清除失败：' + err.message, 'error');
            showToast('清除失败：' + err.message);
        })
        .finally(function () { btnResetSceneConfig.disabled = false; });
    }

    // ===== 全局兜底模型（所有场景共用一份）=====
    const inputFallbackModel = document.getElementById('fallback_model');
    const inputFallbackMaxTokens = document.getElementById('fallback_maxTokens');
    const inputFallbackTemperature = document.getElementById('fallback_temperature');
    const inputFallbackBaseUrl = document.getElementById('fallback_baseUrl');
    const inputFallbackApiKey = document.getElementById('fallback_apiKey');
    const btnSaveFallback = document.getElementById('btn-save-fallback');
    const btnResetFallback = document.getElementById('btn-reset-fallback');
    const fallbackStatusDiv = document.getElementById('fallback-status');

    function fallbackStatus(msg, type) {
        if (!fallbackStatusDiv) return;
        fallbackStatusDiv.textContent = msg;
        fallbackStatusDiv.classList.remove('hidden');
        fallbackStatusDiv.classList.toggle('has-config', type === 'ok' || type === 'error');
        fallbackStatusDiv.style.borderColor = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
        fallbackStatusDiv.style.color = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
    }

    /**
     * 回填兜底模型：生效值优先展示覆盖值，未覆盖时显示 yml 静态值。
     * 密钥永不回显——只给"已配置"的占位提示，留空提交=保留现有。
     */
    function renderFallback(data) {
        if (!inputFallbackModel) return;
        const fb = data && data.fallback ? data.fallback : null;
        if (!fb) {
            inputFallbackModel.value = '';
            inputFallbackMaxTokens.value = '';
            inputFallbackTemperature.value = '';
            inputFallbackBaseUrl.value = '';
            inputFallbackApiKey.value = '';
            return;
        }
        inputFallbackModel.value = fb.overrideModel || fb.staticModel || '';
        // 上限/温度只回显覆盖值：回显静态值会让"留空=沿用"的承诺失真
        // （用户不动它保存，静态值就落成了覆盖；之后改 yml 静态值也不会生效）
        inputFallbackMaxTokens.value = fb.overrideMaxTokens != null ? fb.overrideMaxTokens : '';
        inputFallbackMaxTokens.placeholder = fb.staticMaxTokens != null
            ? ('静态默认 ' + fb.staticMaxTokens + '，留空沿用主模型') : '留空沿用主模型';
        inputFallbackTemperature.value = fb.overrideTemperature != null ? fb.overrideTemperature : '';
        inputFallbackBaseUrl.value = fb.overrideBaseUrl || fb.staticBaseUrl || '';
        inputFallbackApiKey.value = '';
        // 密钥不回显：已覆盖时给掩码提示，留空提交 = 保留现有密钥
        const hasKey = fb.overrideHasKey || fb.staticHasKey;
        const masked = fb.overrideApiKeyMasked || fb.staticApiKeyMasked || '已掩码';
        inputFallbackApiKey.placeholder = hasKey
            ? ('已配置（' + masked + '），留空保留')
            : '留空沿用主模型';
        inputFallbackModel.placeholder = fb.effectiveModel
            ? ('当前生效：' + fb.effectiveModel)
            : '留空 = 不启用兜底';
    }

    function saveFallback() {
        const model = inputFallbackModel.value.trim();
        const maxTokens = inputFallbackMaxTokens.value.trim();
        const temperature = inputFallbackTemperature.value.trim();
        const baseUrl = inputFallbackBaseUrl.value.trim();
        const apiKey = inputFallbackApiKey.value.trim();
        // 与场景矩阵同款：整表留空 = 清除兜底覆盖（回退 yml 静态值）
        if (!model && !maxTokens && !temperature && !baseUrl && !apiKey) {
            resetFallback();
            return;
        }
        const entry = {};
        if (model) entry.model = model;
        if (maxTokens) entry.maxTokens = parseInt(maxTokens, 10);
        if (temperature !== '') entry.temperature = parseFloat(temperature);
        if (baseUrl) entry.baseUrl = baseUrl;
        // 密钥只在真的填了新值时提交（留空=后端保留现有密钥）
        if (apiKey) entry.apiKey = apiKey;

        btnSaveFallback.disabled = true;
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fallback: entry })
        })
        .then(function (data) {
            renderLlmConfig(data);
            fallbackStatus('兜底模型已保存并生效：' + (model || '沿用静态配置'), 'ok');
            showToast('兜底模型已保存');
        })
        .catch(function (err) {
            fallbackStatus('兜底模型保存失败：' + err.message, 'error');
            showToast('保存失败：' + err.message);
        })
        .finally(function () { btnSaveFallback.disabled = false; });
    }

    function resetFallback() {
        btnResetFallback.disabled = true;
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ resetFallback: true })
        })
        .then(function (data) {
            renderLlmConfig(data);
            fallbackStatus('兜底模型覆盖已清除，回退到程序内置配置', 'ok');
            showToast('兜底覆盖已清除');
        })
        .catch(function (err) {
            fallbackStatus('兜底覆盖清除失败：' + err.message, 'error');
            showToast('清除失败：' + err.message);
        })
        .finally(function () { btnResetFallback.disabled = false; });
    }

    if (btnSaveFallback) btnSaveFallback.addEventListener('click', saveFallback);
    if (btnResetFallback) btnResetFallback.addEventListener('click', resetFallback);

    function loadLlmConfig() {
        fetchJson('/api/config/llm')
        .then(renderLlmConfig)
        .catch(function (err) {
            llmStatusDiv.className = 'config-status';
            llmStatusDiv.textContent = '配置读取失败：' + err.message;
            sceneMatrixDiv.innerHTML = '<p class="empty-hint">配置读取失败：' + esc(err.message) + '</p>';
        });
    }

    llmConfigForm.addEventListener('submit', function (e) {
        e.preventDefault();
        const body = { reset: false, authMode: inputAuthMode.value };
        if (inputBaseUrl.value.trim() !== '') body.baseUrl = inputBaseUrl.value.trim();
        if (inputApiKey.value.trim() !== '') body.apiKey = inputApiKey.value.trim();
        if (inputModel.value.trim() !== '') body.model = inputModel.value.trim();
        if (inputMaxTokens.value.trim() !== '') body.maxTokens = parseInt(inputMaxTokens.value, 10);
        // 中转站引用随全局表单提交：'' = 手动模式（清除引用），选中 id = 地址密钥走该站
        body.providerId = inputProvider.value;
        btnSaveLlmConfig.disabled = true;
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        })
        .then(function (data) {
            renderLlmConfig(data);
            showToast('模型接入配置已保存并生效');
        })
        .catch(function (err) {
            llmStatusDiv.className = 'config-status';
            llmStatusDiv.textContent = '配置保存失败：' + err.message;
            showToast('配置保存失败：' + err.message);
        })
        .finally(function () { btnSaveLlmConfig.disabled = false; });
    });

    inputAuthMode.addEventListener('change', renderAuthMode);

    btnChatGptLogin.addEventListener('click', function () {
        const popup = window.open('', 'novel-factory-chatgpt-login', 'width=720,height=760');
        btnChatGptLogin.disabled = true;
        fetchJson('/api/auth/chatgpt/start', { method: 'POST' })
        .then(function (result) {
            if (!popup) throw new Error('浏览器阻止了登录窗口，请允许本站弹窗');
            popup.location.href = result.url;
            popup.focus();
        })
        .catch(function (err) {
            if (popup) popup.close();
            showToast('ChatGPT 登录启动失败：' + err.message);
        })
        .finally(function () { btnChatGptLogin.disabled = false; });
    });

    btnChatGptLogout.addEventListener('click', function () {
        btnChatGptLogout.disabled = true;
        fetchJson('/api/auth/chatgpt', { method: 'DELETE' })
        .then(function () {
            loadChatGptStatus();
            showToast('已退出 ChatGPT 登录');
        })
        .catch(function (err) { showToast('退出失败：' + err.message); })
        .finally(function () { btnChatGptLogout.disabled = false; });
    });

    window.addEventListener('message', function (event) {
        if (!event.data || event.data.type !== 'chatgpt-auth-complete') return;
        if (event.data.success) {
            inputAuthMode.value = 'chatgpt';
            renderAuthMode();
            showToast('ChatGPT 登录成功，请确认模型并保存配置');
        } else {
            loadChatGptStatus();
        }
    });

    btnResetLlmConfig.addEventListener('click', function () {
        btnResetLlmConfig.disabled = true;
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ reset: true })
        })
        .then(function (data) {
            renderLlmConfig(data);
            showToast('运行时覆盖已清除（含场景覆盖与中转站引用），回退 yml 静态配置');
        })
        .catch(function (err) {
            llmStatusDiv.className = 'config-status';
            llmStatusDiv.textContent = '清除失败：' + err.message;
            showToast('清除失败：' + err.message);
        })
        .finally(function () { btnResetLlmConfig.disabled = false; });
    });

    btnSaveSceneConfig.addEventListener('click', saveSceneOverrides);
    btnResetSceneConfig.addEventListener('click', resetSceneOverrides);

    // ===== 我的参考资料（data/references：与内置 58 份同待遇，进索引被检索）=====
    const userReferenceListDiv = document.getElementById('user-reference-list');
    const referenceEditorDiv = document.getElementById('reference-editor');
    const inputReferenceName = document.getElementById('reference_file_name');
    const referenceContentEl = document.getElementById('reference_content');
    const btnNewReference = document.getElementById('btn-new-reference');
    const btnSaveReference = document.getElementById('btn-save-reference');
    const btnCancelReference = document.getElementById('btn-cancel-reference');
    const userReferenceStatusDiv = document.getElementById('user-reference-status');
    const inputImportPath = document.getElementById('reference_import_path');
    const inputImportName = document.getElementById('reference_import_name');
    const btnImportReference = document.getElementById('btn-import-reference');

    function referenceStatus(msg, type) {
        userReferenceStatusDiv.textContent = msg;
        userReferenceStatusDiv.classList.remove('hidden');
        userReferenceStatusDiv.classList.toggle('has-config', type === 'ok' || type === 'error');
        userReferenceStatusDiv.style.borderColor = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
        userReferenceStatusDiv.style.color = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
    }

    const REFERENCE_GROUP_LABELS = {
        technique: '写作技法（正文/审校检索）',
        structure: '结构模板（计划/蓝图检索）',
        genre: '题材设定（设定集/蓝图检索）'
    };

    function loadUserReferences() {
        fetchJson('/api/assets/references')
        .then(function (list) {
            if (!list || !list.length) {
                userReferenceListDiv.innerHTML = '<p class="empty-hint">还没有自备资料。点「新增资料」，把你的范例或题材知识喂给产线。</p>';
                return;
            }
            userReferenceListDiv.innerHTML = list.map(function (r) {
                const meta = [];
                if (r.group && REFERENCE_GROUP_LABELS[r.group]) meta.push(REFERENCE_GROUP_LABELS[r.group]);
                meta.push(Math.round(r.sizeBytes / 1024) + ' KB');
                return '<div class="scene-row asset-row-reference" data-reference="' + esc(r.fileName) + '">'
                    + '<div class="scene-name"><b>' + esc(r.title || r.fileName) + '</b>'
                    + '<span class="scene-key">' + esc(r.fileName) + '</span></div>'
                    + '<div class="scene-static"><span>' + esc(r.digest || '') + '</span>'
                    + '<span class="scene-static-meta">' + esc(meta.join(' · ')) + '</span></div>'
                    + '<button type="button" class="btn-secondary scene-clear" data-act="edit">编辑</button>'
                    + '<button type="button" class="btn-secondary scene-clear" data-act="delete">删除</button>'
                    + '</div>';
            }).join('');
            userReferenceListDiv.querySelectorAll('[data-act]').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    const name = btn.closest('[data-reference]').getAttribute('data-reference');
                    if (btn.getAttribute('data-act') === 'edit') openReferenceEditor(name);
                    else deleteReference(name);
                });
            });
        })
        .catch(function (err) {
            userReferenceListDiv.innerHTML = '<p class="empty-hint">资料列表读取失败：' + esc(err.message) + '</p>';
        });
    }

    function openReferenceEditor(fileName) {
        referenceEditorDiv.classList.remove('hidden');
        if (fileName) {
            inputReferenceName.value = fileName;
            inputReferenceName.disabled = true;   // 文件名是对外标识，保存后不改名
            fetchJson('/api/assets/references/' + encodeURIComponent(fileName))
            .then(function (body) { referenceContentEl.value = body.content || ''; })
            .catch(function (err) { referenceStatus('读取失败：' + err.message, 'error'); });
        } else {
            inputReferenceName.value = '';
            inputReferenceName.disabled = false;
            referenceContentEl.value = '';
        }
        referenceEditorDiv.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    function saveReference() {
        const name = inputReferenceName.value.trim();
        if (!name) { referenceStatus('资料名不能为空（小写字母/数字/连字符）', 'error'); return; }
        const content = referenceContentEl.value;
        if (!content.trim()) { referenceStatus('内容不能为空', 'error'); return; }
        btnSaveReference.disabled = true;
        fetchJson('/api/assets/references/' + encodeURIComponent(name), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content: content })
        })
        .then(function () {
            referenceStatus('资料「' + name + '」已保存，下一批作业生效（检索/向量索引自动纳入）', 'ok');
            showToast('参考资料已保存');
            referenceEditorDiv.classList.add('hidden');
            loadUserReferences();
        })
        .catch(function (err) { referenceStatus('保存失败：' + err.message, 'error'); })
        .finally(function () { btnSaveReference.disabled = false; });
    }

    function deleteReference(name) {
        if (!window.confirm('删除资料「' + name + '」？它将不再被检索注入。')) return;
        fetchJson('/api/assets/references/' + encodeURIComponent(name), { method: 'DELETE' })
        .then(function () {
            referenceStatus('资料「' + name + '」已删除', 'ok');
            showToast('参考资料已删除');
            loadUserReferences();
        })
        .catch(function (err) { referenceStatus('删除失败：' + err.message, 'error'); });
    }

    if (btnNewReference) btnNewReference.addEventListener('click', function () { openReferenceEditor(null); });
    if (btnSaveReference) btnSaveReference.addEventListener('click', saveReference);
    if (btnCancelReference) btnCancelReference.addEventListener('click', function () {
        referenceEditorDiv.classList.add('hidden');
    });

    /** 从文件路径推资料名：去扩展名 → 小写 → 非法字符压成连字符 → 去首尾连字符 */
    function referenceNameFromPath(path) {
        const base = String(path).split(/[\\/]/).pop() || '';
        const stem = base.replace(/\.[^.]*$/, '');
        const slug = stem.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '');
        return slug;
    }

    /** 从本地文件导入资料：后端读全文复制进 data/references，源文件之后移动/删除不受影响 */
    function importReference() {
        const path = inputImportPath.value.trim();
        if (!path) { referenceStatus('先填本地文件路径（md / txt）', 'error'); return; }
        let name = inputImportName.value.trim() || referenceNameFromPath(path);
        if (!name) {
            referenceStatus('文件名无法转成合法资料名（需含字母或数字），请在「资料名」里手动填写', 'error');
            return;
        }
        btnImportReference.disabled = true;
        fetchJson('/api/assets/references/' + encodeURIComponent(name) + '/import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sourcePath: path })
        })
        .then(function (list) {
            const title = (list || []).filter(function (r) { return r.fileName === name; })[0];
            referenceStatus('已导入「' + (title ? title.title : name) + '」（' + name
                + '），下一批作业生效（检索/向量索引自动纳入）', 'ok');
            showToast('资料已导入');
            inputImportPath.value = '';
            inputImportName.value = '';
            loadUserReferences();
        })
        .catch(function (err) { referenceStatus('导入失败：' + err.message, 'error'); })
        .finally(function () { btnImportReference.disabled = false; });
    }

    if (btnImportReference) btnImportReference.addEventListener('click', importReference);
    if (inputImportPath) inputImportPath.addEventListener('change', function () {
        // 填了路径且没手动指定资料名时，实时预览自动生成的名字
        if (!inputImportName.value.trim()) {
            const slug = referenceNameFromPath(inputImportPath.value);
            inputImportName.placeholder = slug || '文件名无法自动生成，请手动填写';
        }
    });

    // ===== 文风（data/styles 覆盖内置 styles/{genre}.md）=====
    const styleListDiv = document.getElementById('style-list');
    const styleEditorDiv = document.getElementById('style-editor');
    const styleEditorTitle = document.getElementById('style_editor_title');
    const styleContentEl = document.getElementById('style_content');
    const btnSaveStyle = document.getElementById('btn-save-style');
    const btnResetStyle = document.getElementById('btn-reset-style');
    const btnCancelStyle = document.getElementById('btn-cancel-style');
    const styleStatusDiv = document.getElementById('style-status');
    /** 编辑态覆盖标记：恢复内置按钮只在已有覆盖时可用 */
    let editingStyleHasOverride = false;

    const GENRE_LABELS = {
        fantasy: '玄幻 / 奇幻 / 修仙', ability: '都市异能', suspense: '悬疑 / 推理 / 刑侦',
        romance: '言情 / 情感', scifi: '科幻 / 星际', apocalypse: '末世 / 废土',
        'infinite-flow': '无限流 / 副本', urban: '都市现实 / 重生 / 年代', default: '通用（未命中题材时）'
    };

    function styleStatus(msg, type) {
        styleStatusDiv.textContent = msg;
        styleStatusDiv.classList.remove('hidden');
        styleStatusDiv.classList.toggle('has-config', type === 'ok' || type === 'error');
        styleStatusDiv.style.borderColor = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
        styleStatusDiv.style.color = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
    }

    function loadStyles() {
        fetchJson('/api/assets/styles')
        .then(function (list) {
            styleListDiv.innerHTML = list.map(function (s) {
                const label = GENRE_LABELS[s.code] || s.code;
                let badge;
                if (s.hasOverride) badge = '<span class="scene-badge scene-badge-on">自定义生效中</span>';
                else if (s.hasBuiltin) badge = '<span class="scene-badge">内置</span>';
                else badge = '<span class="scene-badge">无专属（回落通用）</span>';
                return '<div class="scene-row asset-row-style" data-style="' + esc(s.code) + '">'
                    + '<div class="scene-name"><b>' + esc(label) + '</b>'
                    + '<span class="scene-key">' + esc(s.code) + '</span>' + badge + '</div>'
                    + '<button type="button" class="btn-secondary scene-clear" data-act="edit">编辑</button>'
                    + '</div>';
            }).join('');
            styleListDiv.querySelectorAll('[data-act]').forEach(function (btn) {
                btn.addEventListener('click', function () {
                    openStyleEditor(btn.closest('[data-style]').getAttribute('data-style'));
                });
            });
        })
        .catch(function (err) {
            styleListDiv.innerHTML = '<p class="empty-hint">文风列表读取失败：' + esc(err.message) + '</p>';
        });
    }

    function openStyleEditor(code) {
        styleEditorDiv.classList.remove('hidden');
        styleEditorDiv.setAttribute('data-editing-code', code);   // 保存/恢复内置按它寻址
        styleEditorTitle.value = (GENRE_LABELS[code] || code) + '（' + code + '）';
        fetchJson('/api/assets/styles/' + encodeURIComponent(code))
        .then(function (body) {
            styleContentEl.value = body.content || '';
            editingStyleHasOverride = !!body.overridden;
            btnResetStyle.classList.toggle('hidden', !editingStyleHasOverride && !body.builtin);
            btnResetStyle.disabled = !editingStyleHasOverride;
            if (!body.builtin && !body.overridden) {
                styleContentEl.placeholder = '该题材还没有专属文风。写一篇，保存后即拥有专属文风（不再回落通用版）。';
            }
        })
        .catch(function (err) { styleStatus('读取失败：' + err.message, 'error'); });
        styleEditorDiv.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    if (btnSaveStyle) btnSaveStyle.addEventListener('click', function () {
        // 题材码从编辑器容器取：标题输入框里是「中文名（code）」混排不可靠
        const target = styleEditorDiv.getAttribute('data-editing-code');
        if (!target) { styleStatus('请先从列表选择要编辑的题材', 'error'); return; }
        const content = styleContentEl.value;
        if (!content.trim()) { styleStatus('文风内容不能为空', 'error'); return; }
        btnSaveStyle.disabled = true;
        fetchJson('/api/assets/styles/' + encodeURIComponent(target), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content: content })
        })
        .then(function () {
            styleStatus('「' + target + '」文风已保存，下次生成即用新版', 'ok');
            showToast('文风已保存');
            styleEditorDiv.classList.add('hidden');
            loadStyles();
        })
        .catch(function (err) { styleStatus('保存失败：' + err.message, 'error'); })
        .finally(function () { btnSaveStyle.disabled = false; });
    });

    if (btnResetStyle) btnResetStyle.addEventListener('click', function () {
        const target = styleEditorDiv.getAttribute('data-editing-code');
        if (!target) return;
        if (!window.confirm('恢复「' + target + '」的内置文风？你的自定义版本将被删除。')) return;
        fetchJson('/api/assets/styles/' + encodeURIComponent(target), { method: 'DELETE' })
        .then(function () {
            styleStatus('已恢复内置文风（' + target + '）', 'ok');
            showToast('已恢复内置文风');
            styleEditorDiv.classList.add('hidden');
            loadStyles();
        })
        .catch(function (err) { styleStatus('恢复失败：' + err.message, 'error'); });
    });

    if (btnCancelStyle) btnCancelStyle.addEventListener('click', function () {
        styleEditorDiv.classList.add('hidden');
    });

    function loadUserAssets() {
        loadUserReferences();
        loadStyles();
    }

    // 场景级 API 开关：勾选/取消即保存（独立于"保存场景覆盖"，开关本身就是一条运行时覆盖）
    inputSceneApiEnabled.addEventListener('change', function () {
        const wanted = inputSceneApiEnabled.checked;
        inputSceneApiEnabled.disabled = true;
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sceneApiEnabled: wanted })
        })
        .then(function (data) {
            renderLlmConfig(data);
            sceneStatus(wanted
                ? '场景级 API 已开启：各场景可自带 Base URL / API Key（在下方各行编辑）'
                : '场景级 API 已关闭：所有场景强制走全局地址与密钥，场景自带配置不生效', 'ok');
            showToast(wanted ? '场景级 API 已开启' : '场景级 API 已关闭（统一走全局）');
        })
        .catch(function (err) {
            inputSceneApiEnabled.checked = !wanted; // 回滚勾选态，避免 UI 与后端不一致
            sceneStatus('场景级 API 开关保存失败：' + err.message, 'error');
            showToast('开关保存失败：' + err.message);
        })
        .finally(function () { inputSceneApiEnabled.disabled = false; });
    });

    // ===== 向量检索分区（可运行时覆盖：地址/密钥/模型/维度；客户端按调用重建，保存即生效） =====
    const embedForm = document.getElementById('embed-form');
    const embedStatusDiv = document.getElementById('embed-status');
    const inputEmbedBaseUrl = document.getElementById('embed_baseUrl');
    const inputEmbedApiKey = document.getElementById('embed_apiKey');
    const inputEmbedModel = document.getElementById('embed_model');
    const inputEmbedDimensions = document.getElementById('embed_dimensions');
    const btnSaveEmbed = document.getElementById('btn-save-embed');
    const btnResetEmbed = document.getElementById('btn-reset-embed');

    function renderEmbedding(emb) {
        if (!embedForm) return;
        const fields = [inputEmbedBaseUrl, inputEmbedApiKey, inputEmbedModel, inputEmbedDimensions, btnSaveEmbed, btnResetEmbed];
        if (!emb || !emb.configured) {
            fields.forEach(function (el) { if (el) el.disabled = true; });
            embedStatusDiv.className = 'config-status';
            embedStatusDiv.classList.remove('hidden');
            embedStatusDiv.textContent = 'yml 未配置 module.embedding-api 静态段：先在 yml 里配好，再谈运行时覆盖';
            return;
        }
        fields.forEach(function (el) { if (el) el.disabled = false; });
        inputEmbedBaseUrl.value = emb.overrideBaseUrl || '';
        inputEmbedApiKey.value = '';
        inputEmbedModel.value = emb.overrideModel || '';
        inputEmbedDimensions.value = emb.overrideDimensions != null ? emb.overrideDimensions : '';
        inputEmbedBaseUrl.placeholder = '留空沿用静态：' + (emb.baseUrl || '未配置');
        inputEmbedModel.placeholder = '留空沿用静态：' + (emb.model || '未配置');
        inputEmbedDimensions.placeholder = emb.dimensions != null ? '留空沿用静态：' + emb.dimensions : '留空走供应商默认';
        embedStatusDiv.textContent = '静态生效：模型=' + (emb.model || '未配置')
            + ' · 维度=' + (emb.dimensions != null ? emb.dimensions : '供应商默认')
            + ' · Key ' + (emb.hasKey ? '已配置（' + (emb.apiKeyMasked || '已掩码') + '）' : '未配置')
            + (emb.overridden ? '；嵌入覆盖生效中（保存后下次向量调用即用新值）' : '；当前无嵌入覆盖');
        embedStatusDiv.classList.remove('hidden');
        embedStatusDiv.classList.toggle('has-config', emb.overridden === true);
    }

    function embedStatus(msg, type) {
        embedStatusDiv.textContent = msg;
        embedStatusDiv.classList.remove('hidden');
        embedStatusDiv.classList.toggle('has-config', type === 'ok' || type === 'error');
        embedStatusDiv.style.borderColor = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
        embedStatusDiv.style.color = type === 'error' ? 'var(--error)' : (type === 'ok' ? 'var(--ok)' : '');
    }

    embedForm.addEventListener('submit', function (e) {
        e.preventDefault();
        const embedding = {};
        if (inputEmbedBaseUrl.value.trim() !== '') embedding.baseUrl = inputEmbedBaseUrl.value.trim();
        if (inputEmbedApiKey.value.trim() !== '') embedding.apiKey = inputEmbedApiKey.value.trim();
        if (inputEmbedModel.value.trim() !== '') embedding.model = inputEmbedModel.value.trim();
        if (inputEmbedDimensions.value.trim() !== '') embedding.dimensions = parseInt(inputEmbedDimensions.value, 10);
        btnSaveEmbed.disabled = true;
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ embedding: embedding })
        })
        .then(function (data) {
            renderLlmConfig(data);
            showToast('嵌入覆盖已保存，下次向量调用即生效');
        })
        .catch(function (err) {
            embedStatus('嵌入覆盖保存失败：' + err.message, 'error');
            showToast('嵌入覆盖保存失败：' + err.message);
        })
        .finally(function () { btnSaveEmbed.disabled = false; });
    });

    btnResetEmbed.addEventListener('click', function () {
        btnResetEmbed.disabled = true;
        fetchJson('/api/config/llm', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ resetEmbedding: true })
        })
        .then(function (data) {
            renderLlmConfig(data);
            showToast('嵌入覆盖已清除，回退 yml 静态配置');
        })
        .catch(function (err) {
            embedStatus('嵌入覆盖清除失败：' + err.message, 'error');
            showToast('清除失败：' + err.message);
        })
        .finally(function () { btnResetEmbed.disabled = false; });
    });

    // ===== 运行观测（量化日志）：llm-usage.jsonl 的调用/失败/token/耗时汇总 =====
    const btnRefreshMetrics = document.getElementById('btn-refresh-metrics');

    function loadMetrics() {
        fetchJson('/api/metrics/llm')
        .then(renderMetrics)
        .catch(function (err) {
            const sum = document.getElementById('metrics-summary');
            if (sum) sum.innerHTML = '<p class="empty-hint">读取失败：' + esc(err.message) + '</p>';
        });
    }

    function renderMetrics(m) {
        const sum = document.getElementById('metrics-summary');
        const byModel = document.getElementById('metrics-by-model');
        if (!sum || !byModel) return;
        sum.innerHTML = [
            metric('调用次数', m.calls || 0),
            metric('失败率', m.failureRate != null ? (m.failureRate * 100).toFixed(2) + '%' : '-'),
            metric('总 Tokens', formatNum(m.totalTokens)),
            metric('输入 / 输出', formatNum(m.promptTokens) + ' / ' + formatNum(m.completionTokens)),
            // 思考占比：仅当有供应商上报思考量时才显示（混用非思考模型时该列无意义）
            (m.reasoningCalls > 0
                ? metric('思考占比', (m.reasoningTokenRatio * 100).toFixed(1) + '%',
                    formatNum(m.reasoningTokens) + ' / ' + m.reasoningCalls + ' 次上报')
                : ''),
            metric('累计耗时', formatDuration(m.durationMs)),
            metric('平均耗时', formatDuration(m.averageDurationMs))
        ].join('');
        const entries = Object.entries(m.byModel || {});
        if (entries.length === 0) {
            byModel.innerHTML = '<p class="empty-hint">尚无调用记录（llm-usage.jsonl 为空或不存在）</p>';
            return;
        }
        const total = entries.reduce(function (acc, e) { return acc + e[1]; }, 0) || 1;
        byModel.innerHTML = entries.map(function (e) {
            return '<div class="metrics-model-row"><b class="mono">' + esc(e[0]) + '</b>'
                + '<span class="mono">' + e[1] + ' 次 · ' + (e[1] * 100 / total).toFixed(1) + '%</span></div>';
        }).join('');
    }

    function formatNum(n) {
        if (n == null) return '-';
        if (n >= 1e8) return (n / 1e8).toFixed(2) + ' 亿';
        if (n >= 1e4) return (n / 1e4).toFixed(1) + ' 万';
        return String(n);
    }

    function formatDuration(ms) {
        if (ms == null) return '-';
        if (ms >= 3600000) return (ms / 3600000).toFixed(1) + ' 小时';
        if (ms >= 60000) return (ms / 60000).toFixed(1) + ' 分钟';
        return (ms / 1000).toFixed(1) + ' 秒';
    }

    // ===== 批末体检呈现：overall 分级徽标 + 逐指标行（级别着色）+ 建议 =====
    function renderHealthReport(report) {
        const box = document.getElementById('job-health');
        if (!box) return;
        if (!report || !report.overall) {
            box.classList.add('hidden');
            box.innerHTML = '';
            return;
        }
        const lvColor = { OK: '#34d399', WATCH: '#fbbf24', DEGRADED: '#fb923c', CRITICAL: '#f87171' };
        const lvName = { OK: '健康', WATCH: '观察', DEGRADED: '劣化', CRITICAL: '危急' };
        const color = lvColor[report.overall] || '#8b93a7';
        let html = '<div class="list-header"><h3 class="status-title">批末体检'
            + '<span class="health-badge" style="color:' + color + '">' + esc(lvName[report.overall] || report.overall)
            + ' · ' + esc(report.overall) + '</span>'
            + '<span class="scene-key">样本 ' + (report.chapterCount != null ? report.chapterCount : '?') + ' 章</span></h3></div>';
        html += '<div class="health-metrics">';
        (report.metrics || []).forEach(function (mt) {
            const c = lvColor[mt.level] || '#8b93a7';
            html += '<div class="health-metric">'
                + '<span class="health-level" style="color:' + c + '">' + esc(mt.level || '') + '</span>'
                + '<span class="health-label">' + esc(mt.label || '') + '</span>'
                + '<span class="health-value mono">' + esc(formatNum(mt.value)) + esc(mt.unit || '') + '</span>'
                + '<span class="health-target">' + esc(mt.healthyLine || '') + '</span>'
                + (mt.detail ? '<span class="health-detail">' + esc(mt.detail) + '</span>' : '')
                + '</div>';
        });
        html += '</div>';
        if (report.recommendations && report.recommendations.length > 0) {
            html += '<div class="health-recs"><b>建议</b><ul>'
                + report.recommendations.map(function (r) { return '<li>' + esc(r) + '</li>'; }).join('')
                + '</ul></div>';
        }
        box.innerHTML = html;
        box.classList.remove('hidden');
    }

    if (btnRefreshMetrics) btnRefreshMetrics.addEventListener('click', loadMetrics);

    // ===== Toast 通知 =====
    const toastEl = document.getElementById('toast');
    let toastTimer = null;

    function showToast(msg) {
        toastEl.textContent = msg;
        toastEl.classList.remove('hidden');
        if (toastTimer) clearTimeout(toastTimer);
        toastTimer = setTimeout(function () {
            toastEl.classList.add('hidden');
        }, 3000);
    }

    // ===== 工具函数 =====
    /**
     * HTML 转义。注意 textContent→innerHTML 只转义 & < >，**不转义引号**——
     * 直接拼进属性值时，书名/模型名里一个 " 就能闭合属性并注入事件处理器。
     * 正文与属性两个上下文共用本函数，故这里把引号一并转义。
     */
    function esc(s) {
        const d = document.createElement('div');
        d.textContent = s == null ? '' : String(s);
        return d.innerHTML.replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    // 统一 fetch 封装：响应非 JSON（服务重启中的 HTML 错误页等）给出友好提示
    function fetchJson(url, options) {
        return fetch(API_BASE + url, options).then(function (r) {
            var ct = (r.headers.get('content-type') || '').toLowerCase();
            // 状态码挂到 Error 上：调用方（轮询/更新检查）按 status 分支，
            // 不再依赖 "404 " 这样的消息前缀（HTML 错误页会被包装成别的文案，前缀永远匹配不上）
            if (ct.indexOf('application/json') === -1) {
                var notJson = new Error('服务暂不可用（' + r.status + '），请稍后重试');
                notJson.status = r.status;
                throw notJson;
            }
            return r.json().then(function (body) {
                if (!r.ok) {
                    var msg = body && body.message ? body.message : JSON.stringify(body);
                    var httpError = new Error(r.status + ' ' + msg);
                    httpError.status = r.status;
                    throw httpError;
                }
                return body;
            });
        });
    }

    // ===== 版本通知（左下角）=====
    // 逻辑：当前版本由本地后端 /api/app/version 提供；最新版本直接查 GitHub Releases API
    // （公开仓库 + 支持 CORS，无需后端中转）。检查失败不打扰用户，只在面板里如实显示原因。
    const btnUpdate = document.getElementById('btn-update');
    const updatePanel = document.getElementById('update-panel');
    const btnUpdateClose = document.getElementById('btn-update-close');
    const btnCheckUpdate = document.getElementById('btn-check-update');
    const updateCurrentVersion = document.getElementById('update-current-version');
    const updateLatestBadge = document.getElementById('update-latest-badge');
    const updateStatus = document.getElementById('update-status');
    const updateBody = document.getElementById('update-body');
    const updateDownloadLink = document.getElementById('update-download-link');
    const updateFabDot = document.getElementById('update-fab-dot');

    var appMeta = null;
    var updateChecked = false;
    /** 元信息读取结果：ok=正常 / legacy=后端没有该接口（装的是旧版） / unreachable=后端不可达 */
    var appMetaState = 'unreachable';

    /** 后端不可用时的兜底（旧版没有 /api/app/version，拿不到这些字段） */
    var FALLBACK_REPO = 'YoRengzh/Novel-factory';
    var FALLBACK_RELEASES_URL = 'https://github.com/YoRengzh/Novel-factory/releases/latest';

    function showUpdatePanel(show) {
        updatePanel.classList.toggle('hidden', !show);
    }

    /**
     * 读取本机后端版本。三种结果必须区分开，否则用户只会看到"请稍后重试"这种无意义提示：
     * 404 = 装的是旧版程序（没有这个接口）→ 应引导去更新，而不是重试；
     * 网络/解析失败 = 后端没起来或正在重启 → 重试有意义；
     * 其他 4xx/5xx = 后端内部错误 → 如实报状态码。
     */
    function loadAppMeta() {
        return fetch(API_BASE + '/api/app/version', { headers: { 'Accept': 'application/json' } })
            .then(function (r) {
                if (r.status === 404) {
                    appMetaState = 'legacy';
                    return null;
                }
                if (!r.ok) {
                    throw new Error('后端返回 HTTP ' + r.status);
                }
                return r.json();
            })
            .then(function (meta) {
                if (!meta) {
                    // 旧版后端：接口不存在。下载页兜底指向公开仓库
                    updateCurrentVersion.textContent = '旧版本';
                    updateDownloadLink.href = FALLBACK_RELEASES_URL;
                    return null;
                }
                appMeta = meta;
                appMetaState = 'ok';
                updateCurrentVersion.textContent = 'v' + meta.version;
                updateDownloadLink.href = meta.releasesUrl || FALLBACK_RELEASES_URL;
                return meta;
            })
            .catch(function (err) {
                appMetaState = 'unreachable';
                // 后端读不到版本时，壳（Electron）仍知道自己的版本——不能因此让整个
                // 更新入口退化成"读取失败"，那会把应用内更新的能力一起藏掉
                updateCurrentVersion.textContent = window.nfUpdater ? '由安装包提供' : '读取失败';
                updateDownloadLink.href = FALLBACK_RELEASES_URL;
                if (updateStatus && !updateChecked && !window.nfUpdater) {
                    updateStatus.textContent = '无法连接本机后端：' + (err && err.message ? err.message : '连接失败')
                        + '。请确认应用仍在运行，或稍后重试。';
                }
                return null;
            });
    }

    /** 版本比较：1.10.0 > 1.9.0（按数字段比较，不能直接字符串比） */
    function isNewer(latest, current) {
        var a = String(latest || '').replace(/^v/i, '').split('.').map(Number);
        var b = String(current || '').replace(/^v/i, '').split('.').map(Number);
        for (var i = 0; i < Math.max(a.length, b.length); i++) {
            var x = a[i] || 0, y = b[i] || 0;
            if (x > y) return true;
            if (x < y) return false;
        }
        return false;
    }

    /**
     * Release 正文渲染：只做最小安全渲染（标题/列表），其余按纯文本。
     *
     * <p>两个来源的正文形态不同，必须先归一：
     * <ul>
     *   <li>GitHub API 的 {@code body}：Markdown 原文（浏览器直连路径）</li>
     *   <li>electron-updater 的 {@code releaseNotes}：读的是 releases.atom 的 {@code <content>}，
     *       GitHub 已把正文转成 HTML 并转义（{@code &lt;p&gt;...&lt;/p&gt;}）——XML 解析后就是
     *       字面量 {@code <p>} 标签。不剥掉的话，页面上会把标签当正文原样显示出来</li>
     * </ul>
     * 剥标签只做文本层面处理（不注入 HTML），最后仍然走 esc()，避免 Release 正文成为 XSS 入口。
     */
    function renderReleaseNotes(md) {
        if (!md) return '<p class="empty-hint">该版本未填写更新说明。</p>';
        var text = normalizeReleaseHtml(String(md));
        return text.split('\n').map(function (line) {
            var t = line.trim();
            if (!t) return '';
            if (/^#{1,6}\s/.test(t)) {
                return '<div class="release-h">' + esc(t.replace(/^#{1,6}\s+/, '')) + '</div>';
            }
            if (/^[-*]\s/.test(t)) {
                return '<div class="release-li">· ' + esc(t.replace(/^[-*]\s+/, '')) + '</div>';
            }
            return '<div class="release-p">' + esc(t) + '</div>';
        }).join('');
    }

    /**
     * 把 Atom feed 风格的 HTML 片段归一成纯文本行：
     * 块级标签（p/div/br/li/h1-h6）转成换行，列表项加 "- " 前缀，其余标签剥掉，
     * 再把 HTML 实体（&lt; &amp; &quot; 等）解码回字符。
     */
    function normalizeReleaseHtml(raw) {
        if (raw.indexOf('<') === -1 && raw.indexOf('&') === -1) {
            return raw;
        }
        var s = raw
            // 列表项 → Markdown 列表，交给上面的 "- " 分支渲染成圆点
            .replace(/<li[^>]*>/gi, '\n- ')
            .replace(/<\/li>/gi, '')
            // 标题 → Markdown 标题，保留层级信息供 release-h 分支使用
            .replace(/<h([1-6])[^>]*>/gi, function (_m, n) { return '\n' + '#'.repeat(Number(n)) + ' '; })
            .replace(/<\/h[1-6]>/gi, '\n\n')
            // 段落/块级边界给空行：相邻 <p> 之间必须留出分隔，否则多段说明会挤成一片
            .replace(/<br\s*\/?>/gi, '\n')
            .replace(/<\/(p|div|ul|ol|blockquote)>/gi, '\n\n')
            .replace(/<(p|div|ul|ol|blockquote)[^>]*>/gi, '')
            // 剩余标签一律剥掉（含 code/strong/em 等行内标签）
            .replace(/<[^>]+>/g, '');
        // 实体解码：&amp; 必须最后解，否则 &amp;lt; 会被二次解码成 <
        s = s.replace(/&lt;/g, '<').replace(/&gt;/g, '>')
            .replace(/&quot;/g, '"').replace(/&#39;/g, "'")
            .replace(/&nbsp;/g, ' ')
            .replace(/&amp;/g, '&');
        // 收敛多余空行：连续空白行压成一行，避免正文之间出现大段空白
        return s.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
    }

    function checkUpdate() {
        btnCheckUpdate.disabled = true;
        updateStatus.textContent = '正在检查…';
        updateBody.innerHTML = '';
        updateLatestBadge.classList.add('hidden');
        updateDownloadLink.classList.add('hidden');
        hideInstallButton();

        var metaPromise = appMeta ? Promise.resolve(appMeta) : loadAppMeta();
        metaPromise.then(function (meta) {
            if (!meta) {
                // 后端不可用/旧版：版本号仍可从壳（Electron）拿到——优先走应用内更新，
                // 它不依赖 /api/app/version，只依赖安装包自带的 latest.yml；
                // 壳不支持（浏览器/开发模式）时仍要给出明确结论，不能静默什么都不做
                return checkViaShell(null).then(function (handled) {
                    if (handled) return;
                    updateStatus.textContent = '无法连接本机后端，请确认应用仍在运行后重试；'
                            + '也可点击「打开下载页」手动检查新版本。';
                    updateDownloadLink.classList.remove('hidden');
                    updateDownloadLink.href = FALLBACK_RELEASES_URL;
                });
            }
            return checkViaShell(meta).then(function (handled) {
                if (handled) return;
                // 壳不提供更新能力（浏览器访问 / 开发模式）：回退到 GitHub Releases 查询 + 下载页
                return checkViaGitHub(meta);
            });
        }).catch(function (err) {
            updateStatus.textContent = '检查失败：' + (err && err.message ? err.message : '网络不可达')
                + '。可点击「打开下载页」手动查看。';
            updateDownloadLink.classList.remove('hidden');
        }).finally(function () {
            btnCheckUpdate.disabled = false;
        });
    }

    /**
     * 应用内更新：经 Electron preload 暴露的 nfUpdater 检查/下载/安装。
     * 返回 true 表示"这条路径已接管"（无论有无新版本），调用方不必再查 GitHub；
     * 返回 false 表示壳不支持（浏览器直接打开页面、开发模式），调用方回退下载页。
     */
    function checkViaShell(meta) {
        var api = window.nfUpdater;
        if (!api) return Promise.resolve(false);
        return api.check().then(function (res) {
            if (!res || !res.supported) return false;
            updateChecked = true;
            // 当前版本以**安装包**为准（后端 app.version 与 electron version 是两处独立维护的，
            // 只改一处就会让比较基准漂移）；后端版本仅在壳拿不到时兜底
            var current = res.currentVersion || (meta && meta.version) || '';
            if (res.error) {
                // 壳能更新但检查失败（网络/缺少 latest.yml）：如实告知，并保留下载页兜底
                updateStatus.textContent = '应用内检查失败：' + res.error
                    + '。可点击「打开下载页」手动下载。';
                updateDownloadLink.classList.remove('hidden');
                return true;
            }
            if (res.version && isNewer(res.version, current)) {
                updateStatus.textContent = '发现新版本：v' + res.version + '（当前 v' + current + '）';
                updateLatestBadge.classList.remove('hidden');
                showInstallButton('下 载 更 新');
                updateBody.innerHTML = renderReleaseNotes(
                    typeof res.releaseNotes === 'string' ? res.releaseNotes : '');
                updateFabDot.classList.add('has-update');
            } else {
                updateStatus.textContent = '已是最新版本（v' + (current || res.currentVersion) + '）。';
                updateBody.innerHTML = '<p class="empty-hint">当前无需更新。</p>';
            }
            return true;
        }).catch(function () {
            return false;
        });
    }

    /** 浏览器/开发模式路径：查 GitHub Releases 判断版本，提供下载页（无法直接安装） */
    function checkViaGitHub(meta) {
        // 后端起不来（meta=null）+ 壳不支持应用内更新时也会走到这里，不能直接解引用
        meta = meta || { version: '', repo: FALLBACK_REPO, releasesUrl: FALLBACK_RELEASES_URL };
        var currentVersion = String(meta.version || '').trim();
        if (!currentVersion) {
            updateStatus.textContent = '无法确定当前版本（本机后端不可用且安装包未提供版本）。'
                    + '可点击「打开下载页」手动核对。';
            updateDownloadLink.classList.remove('hidden');
            updateDownloadLink.href = meta.releasesUrl || FALLBACK_RELEASES_URL;
            return Promise.resolve();
        }
        return fetch('https://api.github.com/repos/' + (meta.repo || FALLBACK_REPO) + '/releases/latest', {
            headers: { 'Accept': 'application/vnd.github+json' }
        }).then(function (r) {
            if (!r.ok) {
                throw new Error(r.status === 404
                    ? '仓库尚未发布任何 Release'
                    : 'GitHub 返回 ' + r.status);
            }
            return r.json();
        }).then(function (release) {
            var latest = String(release.tag_name || '').replace(/^v/i, '');
            updateChecked = true;
            if (isNewer(latest, meta.version)) {
                updateStatus.textContent = '发现新版本：v' + latest + '（当前 v' + meta.version + '）'
                    + '。浏览器打开时无法自动安装，请下载安装包后覆盖安装。';
                updateLatestBadge.classList.remove('hidden');
                updateDownloadLink.classList.remove('hidden');
                updateDownloadLink.href = release.html_url || meta.releasesUrl;
                updateBody.innerHTML = renderReleaseNotes(release.body);
                updateFabDot.classList.add('has-update');
            } else {
                updateStatus.textContent = '已是最新版本（v' + meta.version + '）。';
                updateBody.innerHTML = '<p class="empty-hint">当前无需更新。</p>';
            }
        });
    }

    // ===== 应用内更新的下载/安装按钮 =====
    // 与"打开下载页"并列：前者能一步装完，后者是壳不支持时的兜底。
    var btnInstall = document.getElementById('btn-install-update');
    var shellDownloaded = false;

    function showInstallButton(label) {
        if (!btnInstall) return;
        btnInstall.textContent = label || '下 载 更 新';
        btnInstall.classList.remove('hidden');
        btnInstall.disabled = false;
    }

    function hideInstallButton() {
        shellDownloaded = false;
        if (!btnInstall) return;
        btnInstall.classList.add('hidden');
        btnInstall.disabled = false;
    }

    if (btnInstall && window.nfUpdater) {
        btnInstall.addEventListener('click', function () {
            if (shellDownloaded) {
                btnInstall.disabled = true;
                updateStatus.textContent = '正在退出并安装，应用会自动重启…';
                Promise.resolve(window.nfUpdater.install()).then(function (res) {
                    if (res && res.ok === false) {
                        btnInstall.disabled = false;
                        updateStatus.textContent = '安装失败：' + (res.error || '未知错误');
                    }
                });
                return;
            }
            btnInstall.disabled = true;
            updateStatus.textContent = '正在下载更新包…';
            window.nfUpdater.download().then(function (res) {
                if (res && res.ok) {
                    // 进度由 updater:event 的 progress/downloaded 驱动，这里不重复设置
                    return;
                }
                btnInstall.disabled = false;
                btnInstall.textContent = '下 载 更 新';   // 失败后别把按钮永久留在“下载中 x%”
                updateStatus.textContent = '下载失败：' + (res && res.error ? res.error : '未知错误')
                    + '。可点击「打开下载页」手动下载。';
                updateDownloadLink.classList.remove('hidden');
            }).catch(function (err) {
                btnInstall.disabled = false;
                btnInstall.textContent = '下 载 更 新';
                updateStatus.textContent = '下载失败：' + (err && err.message ? err.message : '未知错误')
                    + '。可点击「打开下载页」手动下载。';
                updateDownloadLink.classList.remove('hidden');
            });
        });

        window.nfUpdater.onEvent(function (evt) {
            if (!evt) return;
            if (evt.type === 'progress') {
                updateStatus.textContent = '正在下载更新包… ' + evt.percent + '%';
                if (btnInstall) btnInstall.textContent = '下 载 中 ' + evt.percent + '%';
            } else if (evt.type === 'downloaded') {
                shellDownloaded = true;
                updateStatus.textContent = '更新包已下载完成（v' + (evt.version || '') + '），点击按钮安装并重启。';
                showInstallButton('安 装 并 重 启');
            } else if (evt.type === 'error') {
                updateStatus.textContent = '更新出错：' + (evt.message || '未知错误')
                    + '。可点击「打开下载页」手动下载。';
                updateDownloadLink.classList.remove('hidden');
                if (btnInstall) btnInstall.disabled = false;
            } else if (evt.type === 'not-available') {
                updateStatus.textContent = '已是最新版本。';
            }
        });
    }

    if (btnUpdate) {
        btnUpdate.addEventListener('click', function () {
            var willShow = updatePanel.classList.contains('hidden');
            showUpdatePanel(willShow);
            if (willShow && !updateChecked) { checkUpdate(); }
        });
    }
    if (btnUpdateClose) {
        btnUpdateClose.addEventListener('click', function () { showUpdatePanel(false); });
    }
    if (btnCheckUpdate) {
        btnCheckUpdate.addEventListener('click', checkUpdate);
    }

    // ===== 初始化 =====
    var savedTheme = 'dark';
    try { savedTheme = localStorage.getItem(THEME_KEY) || 'dark'; } catch (e) {}
    applyTheme(savedTheme, false);
    restoreJob();
    autoGrowAll();
    // 启动即探测后端版本：旧版后端（无 /api/app/version）会在这里被识别出来，
    // 用户点开通知面板就能直接看到"当前安装的版本较旧"，而不是等到点检查才报错
    loadAppMeta().then(function (meta) {
        if (!meta && appMetaState === 'legacy' && updateStatus) {
            if (window.nfUpdater) {
                // 装了壳：后端旧版不影响应用内更新——版本号由安装包自带，直接查
                updateStatus.textContent = '本机后端版本较旧，正在检查安装包更新…';
                checkUpdate();
            } else {
                updateStatus.textContent = '当前安装的版本较旧（后端没有版本接口），无法自动比对。'
                    + '请点击「打开下载页」获取最新版本。';
                updateDownloadLink.classList.remove('hidden');
                updateDownloadLink.href = FALLBACK_RELEASES_URL;
            }
        }
    });
})();
