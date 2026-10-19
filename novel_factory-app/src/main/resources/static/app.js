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
    let currentStoryDir = null;
    // 章节计划裁决：待裁决计划的本地快照（用于比对"用户是否改过"）+ 倒计时定时器
    let pendingPlanSnapshot = null;
    let pendingPlanSignature = null;
    let approvalTimer = null;

    // ===== DOM 元素 =====
    const loginPage = document.getElementById('login-page');
    const appPage = document.getElementById('app-page');
    const loginForm = document.getElementById('login-form');
    const loginError = document.getElementById('login-error');
    const btnLogout = document.getElementById('btn-logout');

    function checkAuth() {
        // 当前部署范围是 localhost 单体，服务端也未启用认证；不使用伪造的前端密码门槛。
        showApp();
    }

    function showLogin() {
        loginPage.classList.add('active');
        appPage.classList.remove('active');
    }

    function showApp() {
        loginPage.classList.remove('active');
        appPage.classList.add('active');
    }

    function logout() {
        currentJobId = null;
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
        window.location.reload();
    }

    // 旧登录页仅保留为静态原型，不作为本地工作台入口。
    loginForm.addEventListener('submit', function (e) {
        e.preventDefault();
        showApp();
    });

    btnLogout.addEventListener('click', logout);

    function showLoginError(msg) {
        loginError.textContent = msg;
        loginError.classList.remove('hidden');
    }

    // ===== 登录页宇宙星野（Canvas） =====
    const cosmosCanvas = document.getElementById('cosmos-canvas');

    function initCosmos() {
        if (!cosmosCanvas || !cosmosCanvas.getContext) return;
        const ctx = cosmosCanvas.getContext('2d');
        let stars = [];
        let meteor = null;
        let nextMeteorAt = 0;

        function resize() {
            const parent = cosmosCanvas.parentElement;
            const dpr = window.devicePixelRatio || 1;
            cosmosCanvas.width = parent.clientWidth * dpr;
            cosmosCanvas.height = parent.clientHeight * dpr;
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            seed();
        }

        function seed() {
            stars = [];
            const w = cosmosCanvas.width / (window.devicePixelRatio || 1);
            const h = cosmosCanvas.height / (window.devicePixelRatio || 1);
            const count = Math.floor(w * h / 5200);
            const palette = ['255,255,255', '125,211,252', '232,213,163'];
            for (let i = 0; i < count; i++) {
                stars.push({
                    x: Math.random() * w,
                    y: Math.random() * h,
                    r: Math.random() < 0.85 ? Math.random() * 0.9 + 0.3 : Math.random() * 1.5 + 0.8,
                    base: Math.random() * 0.45 + 0.25,
                    amp: Math.random() * 0.3,
                    speed: Math.random() * 0.0012 + 0.0004,
                    phase: Math.random() * Math.PI * 2,
                    color: palette[Math.floor(Math.random() * (Math.random() < 0.72 ? 1 : palette.length))]
                });
            }
        }

        function frame(now) {
            const w = cosmosCanvas.width / (window.devicePixelRatio || 1);
            const h = cosmosCanvas.height / (window.devicePixelRatio || 1);
            ctx.clearRect(0, 0, w, h);

            for (const s of stars) {
                const alpha = Math.max(0.05, s.base + Math.sin(now * s.speed + s.phase) * s.amp);
                ctx.beginPath();
                ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2);
                ctx.fillStyle = 'rgba(' + s.color + ',' + alpha.toFixed(3) + ')';
                ctx.fill();
            }

            // 流星：低频出现，短促划过
            if (!meteor && now > nextMeteorAt) {
                meteor = {
                    x: w * (0.25 + Math.random() * 0.6),
                    y: h * Math.random() * 0.3,
                    vx: -(3.2 + Math.random() * 2),
                    vy: 1.8 + Math.random() * 1.2,
                    life: 1
                };
                nextMeteorAt = now + 8000 + Math.random() * 9000;
            }
            if (meteor) {
                meteor.x += meteor.vx;
                meteor.y += meteor.vy;
                meteor.life -= 0.016;
                if (meteor.life <= 0 || meteor.x < -60 || meteor.y > h + 40) {
                    meteor = null;
                } else {
                    const grad = ctx.createLinearGradient(
                        meteor.x, meteor.y,
                        meteor.x - meteor.vx * 12, meteor.y - meteor.vy * 12);
                    grad.addColorStop(0, 'rgba(255,255,255,' + (0.8 * meteor.life).toFixed(3) + ')');
                    grad.addColorStop(1, 'rgba(255,255,255,0)');
                    ctx.strokeStyle = grad;
                    ctx.lineWidth = 1.2;
                    ctx.beginPath();
                    ctx.moveTo(meteor.x, meteor.y);
                    ctx.lineTo(meteor.x - meteor.vx * 12, meteor.y - meteor.vy * 12);
                    ctx.stroke();
                }
            }

            requestAnimationFrame(frame);
        }

        window.addEventListener('resize', resize);
        resize();
        nextMeteorAt = performance.now() + 5000;
        requestAnimationFrame(frame);
    }

    // ===== Tab 切换 =====
    document.querySelectorAll('.nav-tab').forEach(function (btn) {
        btn.addEventListener('click', function () {
            document.querySelectorAll('.nav-tab').forEach(function (b) { b.classList.remove('active'); });
            document.querySelectorAll('.tab-content').forEach(function (c) { c.classList.remove('active'); });
            btn.classList.add('active');
            document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
            if (btn.dataset.tab === 'library') { loadStories(); }
            if (btn.dataset.tab === 'settings') { loadLlmConfig(); }
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
        } else if (job.currentChapter != null && job.totalChapters) {
            filled = Math.min(PROGRESS_WIDTH, Math.max(0,
                Math.round(PROGRESS_WIDTH * job.currentChapter / job.totalChapters)));
            var pct = Math.min(100, Math.round(job.currentChapter / job.totalChapters * 100));
            meta = '第 ' + job.currentChapter + ' / ' + job.totalChapters + ' 章 · ' + pct + '%';
        } else if (job.status === 'RUNNING' || job.status === 'CANCELLING') {
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
    function applyFormValues(values) {
        let applied = 0;
        if (!values) return applied;
        Object.keys(values).forEach(function (key) {
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
                // 后端这两个字段是 Integer，别当字符串发过去（虽然 Jackson 能强转，但别依赖它）
                data[key] = (key === 'chapterCount' || key === 'maxChapterCount')
                        ? parseInt(val, 10) : val;
            }
        });

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
        fetch(API_BASE + '/api/jobs/' + currentJobId + '/cancel', { method: 'POST' })
        .then(function () {
            btnCancel.disabled = true;
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
            if (String(err.message).indexOf('404') === 0) {
                stopPolling();
                clearSavedJobId();
                currentJobId = null;
                hideApprovalCard();
                jobStatusDiv.classList.add('hidden');
                progressCard.classList.add('hidden');
                btnSubmit.disabled = false;
                btnCancel.disabled = true;
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
        if (job.currentStage) lines.push(row('阶段', job.currentStage));
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
            'AWAITING_APPROVAL': '#fbbf24',
            'COMPLETED': '#34d399',
            'FAILED': '#f87171',
            'CANCELLED': '#fbbf24'
        };
        const names = { 'AWAITING_APPROVAL': '待裁决' };
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
        const at = job.currentChapter != null
                ? '已推进到第 ' + job.currentChapter + ' 章附近' : '已有部分章节落盘';
        resumeActionHint.textContent = '「' + job.storyDirName + '」这次没写完（' + at + '）。'
                + '已落盘的章节不会被重写，点下面会接着最后一章往后续写；'
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
            const applied = applyFormValues(info.setting);
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
                chapterType: row.querySelector('.ap-type').value
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
            var savedMsg = resp.summaryUpdated ? '，摘要已更新' : '';
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
        fetchJson('/api/stories/' + encodeURIComponent(storyDir) + '/chapters')
        .then(function (chapters) {
            chapterSequence = chapters.map(function (chapter) { return chapter.chapterNo; });
            libraryList.classList.add('hidden');
            chapterListDiv.classList.remove('hidden');
            workbenchOverview.classList.remove('hidden');
            // 故事概览与章节列表是同级工作区，概览始终排在章节列表之前。
            chapterListDiv.parentNode.insertBefore(workbenchOverview, chapterListDiv);
            chapterListTitle.textContent = '章节目录';
            if (chapters.length === 0) {
                chaptersDiv.innerHTML = '<p class="empty-hint">暂无章节</p>';
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

    function metric(label, value) { return '<div class="metric-card"><strong>' + esc(String(value)) + '</strong><span>' + esc(label) + '</span></div>'; }
    function renderLedger(id, entries) {
        var el = document.getElementById(id);
        el.innerHTML = entries.length ? entries.slice(0, 16).map(function (x) {
            return '<div class="compact-row"><b>' + esc(x.name || '') + '</b><span>' + esc(x.status || '未记录') + '</span></div>';
        }).join('') : '<p class="empty-hint">暂无记录</p>';
    }

    function loadChapter(storyDir, chapterNo) {
        fetchJson('/api/stories/' + encodeURIComponent(storyDir) + '/chapters/' + chapterNo)
        .then(function (ch) {
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

    function renderLlmConfig(data) {
        inputAuthMode.value = data.authMode || 'api-key';
        inputBaseUrl.value = data.baseUrl || '';
        inputApiKey.value = '';
        inputModel.value = data.model || '';
        inputMaxTokens.value = data.maxTokens != null ? data.maxTokens : '';
        renderAuthMode();
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
        renderEmbedding(data.embedding);
        renderReferenceMode(data);
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
            + '<span>场景</span><span>静态生效（yml）</span><span>覆盖 · 模型</span>'
            + '<span>覆盖 · Tokens</span><span>覆盖 · 温度</span><span>覆盖 · Base URL</span>'
            + '<span>覆盖 · API Key</span><span></span>'
            + '</div>';
        const rows = data.scenes.map(function (s) {
            const staticText = s.staticModel || '未配置';
            const staticMeta = [];
            if (s.staticMaxTokens != null) staticMeta.push(String(s.staticMaxTokens) + 'tk');
            if (s.staticTemperature != null) staticMeta.push('t=' + s.staticTemperature);
            const badges = (s.independentApi ? '<span class="scene-badge scene-badge-api">独立API</span>' : '')
                + (s.sceneConfigured ? '' : '<span class="scene-badge">回落统一</span>')
                + (s.overridden ? '<span class="scene-badge scene-badge-on">已覆盖</span>' : '');
            const disabledAttr = apiOn ? '' : ' disabled';
            return '<div class="scene-row" data-scene-key="' + esc(s.key) + '">'
                + '<div class="scene-name"><b>' + esc(s.label || s.key) + '</b>'
                + '<span class="scene-key">' + esc(s.key) + '</span>' + badges + '</div>'
                + '<div class="scene-static"><span class="mono">' + esc(staticText) + '</span>'
                + '<span class="scene-static-meta">' + esc(staticMeta.join(' · ')) + '</span></div>'
                + '<input type="text" class="scene-input" data-field="model" placeholder="留空回退" autocomplete="off">'
                + '<input type="number" class="scene-input" data-field="maxTokens" min="1" placeholder="留空回退" autocomplete="off">'
                + '<input type="number" class="scene-input" data-field="temperature" min="0" max="2" step="0.1" placeholder="留空回退" autocomplete="off">'
                + '<input type="text" class="scene-input" data-field="baseUrl" placeholder="留空回退" autocomplete="off"' + disabledAttr + '>'
                + '<input type="password" class="scene-input" data-field="apiKey" placeholder="留空保留" autocomplete="new-password"' + disabledAttr + '>'
                + '<button type="button" class="btn-secondary scene-clear" title="清空本行覆盖输入">恢复</button>'
                + '</div>';
        }).join('');
        sceneMatrixDiv.innerHTML = header + rows;
        // 输入值用 property 赋值回填（innerHTML 属性转义不可靠），并绑定行内恢复按钮
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
            if (!model && !maxTokens && !temperature && !baseUrl && !apiKey) return; // 整行留空 = 该场景清除覆盖
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
            showToast('运行时覆盖已清除（含场景覆盖），回退 yml 静态配置');
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
    function esc(s) {
        const d = document.createElement('div');
        d.textContent = s;
        return d.innerHTML;
    }

    // 统一 fetch 封装：响应非 JSON（服务重启中的 HTML 错误页等）给出友好提示
    function fetchJson(url, options) {
        return fetch(API_BASE + url, options).then(function (r) {
            var ct = (r.headers.get('content-type') || '').toLowerCase();
            if (ct.indexOf('application/json') === -1) {
                throw new Error('服务暂不可用（' + r.status + '），请稍后重试');
            }
            return r.json().then(function (body) {
                if (!r.ok) {
                    var msg = body && body.message ? body.message : JSON.stringify(body);
                    throw new Error(r.status + ' ' + msg);
                }
                return body;
            });
        });
    }

    // ===== 初始化 =====
    var savedTheme = 'dark';
    try { savedTheme = localStorage.getItem(THEME_KEY) || 'dark'; } catch (e) {}
    applyTheme(savedTheme, false);
    initCosmos();
    checkAuth();
    restoreJob();
    autoGrowAll();
})();
