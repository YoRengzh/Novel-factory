# 发布验收清单

> **用途**：三合一文档——① v0.1 的"完成"定义（发布前照着验收）；② 随 zip 发给用户的验证指引（下载后照着做，就知道装对了没有）；③ 历史记录（每版一小节，累积保留）。
>
> **当前状态**（2026-10-16）：步骤 4–6 已用两批真实运行验证（实测数据见文末）；步骤 1–3、7 依赖阶段 3–4 的 zip / start.bat / stop.bat 产物，落地后在本文档上补测。
> **写清单的规矩**：每一条都必须是"操作 → 外行能亲眼观察到的现象"，不写"接口返回 200"这类内部实现。

---

## v0.2.0（待发布）

### 相比 v0.1.0 的变化（发布时写进 Release Notes）

- **零外部向量库进程**：内置向量库成为默认，不再需要安装/启动 Qdrant；语义唤醒、跨章记忆召回完整保留
- **删除小说 = 向量一并清理**：故事数据（正文、账本、向量索引）全部随故事目录存在，删目录即全清
- 进阶用户仍可切回 Qdrant：`story.vector-store.mode: qdrant`（`reference-selector.mode: vector` 时参考资料也走向量检索）
- 新增已知限制：**嵌入 Key 仍必需**（内置的是"存向量"，"变向量"仍需 embedding API；未配置时以无增强模式降级，日志单条提示）

### v0.2 验收步骤（在 v0.1 步骤之外，额外验证）

- [ ] 启动日志出现「内置向量库初始化」（且**无** Qdrant 连接尝试）
- [ ] 跑批后故事目录 `memory/vectors/` 出现**非空** .nvbin（向量真写入，不只是建文件）
- [ ] 批末体检无降级告警（向量链路全通）
- [ ] 删除故事目录 → 向量文件随之消失，且应用不重启也能感知（检索/计数归零）
- [ ] 切回 `mode: qdrant` 仍可运行（进阶档保留，旧 Qdrant 数据不丢）

### 已知限制（v0.2）

1. 嵌入 Key 仍必需——内置的是存储引擎，不是嵌入模型
2. v0.1 的已知限制 1（JDK）、4（成本）、5（日志噪音，Qdrant 部分已随内置库消失）按 v0.2 实际复验更新

---

## v0.1.0（已发布 2026-10-18）

### 前置条件

- [ ] Windows 10/11，已安装 JDK 17+（命令行 `java -version` 显示 17）
- [ ] 一个 OpenAI 兼容端点的 API Key（DashScope / one-api / new-api 均可）
- [ ] **不需要**：Docker / MySQL / Qdrant / 单独的 embedding Key——"不需要这些也能跑"本身就是验收内容

### 1. 解压

- [ ] 在空目录解压 zip
- [ ] 目录内应包含：应用 jar、`config/`（含 application-local.example.yml）、`start.bat`、`stop.bat`、本清单、README 与 LICENSE

### 2. 配置（唯一必改的文件）

- [ ] 复制 `config/application-local.example.yml` 为 `config/application-local.yml`
- [ ] 只填一项：你的 API Key
- [ ] 确认不改其他任何文件

### 3. 启动

- [ ] 双击 `start.bat`（全程不经过命令行）
- [ ] 浏览器自动打开 `http://localhost:8080`，出现工作台页面
- [ ] 页面顶部可见「01 生成 / 02 小说库 / 03 设置」三个标签

### 4. 创建故事

- [ ] 手填创建表单（必填项：小说名称），或点「一键生成设定集」后按需微调
- [ ] 提交后「小说库」出现新故事条目

### 5. 提交生成

- [ ] 本次生成章数填 **5**（完整验收；3 章只能冒烟，原因见已知限制 3）
- [ ] 提交后作业状态卡从"运行中"推进，章节逐个出现

### 6. 验收产出

- [ ] 批末体检板块出现，显示总体级别（OK / WATCH / DEGRADED / CRITICAL）
- [ ] 体检指标区显示逐项指标（当前值｜目标｜级别）——样本 ≥5 章才会出现
- [ ] 章节正文可阅读、可导出
- [ ] 记录本次实际耗时与 token 消耗（成本参考）

### 7. 关闭

- [ ] 关闭窗口 / 运行 `stop.bat`
- [ ] 任务管理器确认 java 进程已退出
- [ ] 再次双击 `start.bat` 不报端口占用

### 可选：进阶档自验（有 Qdrant 的用户）

- [ ] 启动 Qdrant（`docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant`）并配置 embedding Key
- [ ] 重启应用跑批；日志出现"向量集合已创建 / 向量批量写入完成 / 故事记忆检索命中"
- [ ] 体检「无检索唤醒章数占比」= 0

---

## 已知限制（v0.1，随包发布给用户看）

1. **需要 JDK 17**（v1.0 安装包将免除该依赖）
2. **章节计划审批窗口为 120 秒**：提交后不在此窗口内人工审批，将按 AI 原版计划自动继续（等效黑盒模式）；想全程人工把关请调大 `novel-generation.yml` 的 `timeout-seconds`
3. **无 Qdrant / 无 embedding Key 时无向量增强**：三账本、章节摘要、伏笔排期等文件侧记忆完整工作；批末体检的「无检索唤醒章数占比」会标出裸跑章
4. **3 章批的体检报告不给逐项指标**（样本 <5 章的既定行为，只给总体级别与"样本不足"提示）；要看完整指标需 ≥5 章
5. **成本参考**：3 章约 29–36 万 token（含规划/审校/评审/摘要全链路），5 章相应更多
6. ~~**日志噪音（已知问题，发布前建议修）**~~ 已修复（2026-10-17）：Qdrant 连接失败改为单条 WARN + 熔断冷静期，实测整批仅 1 条 WARN、1 堆 ERROR（首次尝试时客户端自带），作业正常 COMPLETED
7. ~~**前端静态资源无缓存控制**~~ 已修复（2026-10-17）：静态资源响应头已带 `Cache-Control: no-cache`

## 文档矛盾裁决（2026-10-16 实测）

- `tools/run-app.sh` 称："Qdrant 是硬依赖，缺了跑批第一步就 FAILED"
- `docs/quickstart.md` 称："Qdrant 未启动时系统以无向量增强模式运行"

**实测**（无 Qdrant、端口指向 6335、`reference-selector.mode=llm`）：作业 COMPLETED，3 章全部产出，降级链（RECALL_DEGRADED）按设计工作。**裁决：quickstart 正确**；run-app.sh 的描述只对应 `mode=vector` 的场景（该模式下向量链路快速失败是刻意设计），对 v0.1 默认档不成立 → 阶段 1 更新 run-app.sh 注释。

## 实测记录（2026-10-16，新仓库，IDEA 启动而非 zip）

| 批次 | 配置 | 结果 | 每章耗时 | token |
|---|---|---|---|---|
| A | Qdrant:6334 + embedding 覆盖生效 | COMPLETED，3 章 | 108 / 256 / 193 秒 | 291,410 |
| B | 无 Qdrant（6335）+ selector=llm + 无 embedding Key | COMPLETED，3 章 | 130 / 140 / 149 秒 | 359,498 |

**批 A 向量证据**：集合 `novel-memory-20261016-story-0001` 创建（1024 维 Cosine），8+11+12 点写入；每章检索命中 7–8 点（分数 0.476~0.631）；唤醒节注入 379–396 字；`novel-references` 索引 391 点并检索命中。

**批 B 降级证据**：2× RECALL_DEGRADED（检索失败 → 无唤醒继续）；2× Qdrant 连接拒绝 ERROR；"整集合重建失败"WARN ×2；参考资料选择 llm 模式成功 16 次；401 ×3 均来自 StoryMemoryService（embedding 无 Key）。

**401 归因更正**：不是"llm 模式不支持向量"——日志显示批量 B 开跑前「清除嵌入覆盖」被触发（19:32:55 / 19:33:00 记录 `嵌入覆盖 无`），且静态 `embedding-api.api-key: ${LLM_API_KEY:}` 为空。准确表述：**没配 embedding Key**。故事记忆的索引/检索独立于参考资料选择器模式，两者不要混为一谈。

---

## 待办（进阶段 1 前清理）

- [x] 新仓库 `novel-generation.yml` 的测试端口改动（6334→6335）已移入 `application-local.yml`（2026-10-16；注意当时主配置的 vector-store 段被误删，已要求恢复——覆盖机制的正确形态是"默认值留主配置 + 差异放 local 文件"）
- [x] 决定不引入 sample：验收用"手填表单/一键生成设定集"即可（2026-10-16 用户裁决）。连带待办：新仓库 README 快速开始中引用 sample 的句子需在阶段 4 同步时一并删除

---

## v0.2.0（规划中，届时补小节）

预期增量：内置向量库成为默认（免 Qdrant 进程）；本清单步骤 3 的启动检查项去掉 Qdrant 前提；进阶档自验改为"无需任何额外进程"。注意：embedding 端点依赖不消失（同一把 Key 即可，详见 README）。

---

## 应用内更新（桌面版，v1.2.0+）

每次发版必须按顺序做完三件事，缺任何一环「检查更新」都会退回「打开下载页」：

1. **先重建后端**：`bash tools/build-release.sh` → `bash tools/package-native.sh`
   （页面由 sidecar 的 jar 提供；只改前端不重打 jar，装出来的还是旧 UI）
2. **再打安装包**：`cd electron && npm run dist`
   产出 `StartDrafts-Setup-<版本>.exe` **以及 `latest.yml`**（sha512/size/文件名都在里面）
3. **上传 Release 时 exe 与 latest.yml 一起传**，不要改名。electron-updater 按
   `releases/download/<tag>/latest.yml` 读取版本与校验和；缺它必然 404

验收：

- [ ] `dist/electron/latest.yml` 存在，`path:` 与安装包文件名逐字一致，`sha512`/`size` 有值
- [ ] Release 资产同时包含 exe 与 latest.yml
- [ ] 装低版本 → 通知面板出现「下载更新」→ 有进度 → 变「安装并重启」→ 重启后版本已更新
- [ ] 升级后无残留 `java.exe`，也不报"端口 8080 被占用"

两条硬限制：

- **基线**：第一个带 updater 的版本只能手动安装一次；应用内更新从它**之后**的版本开始生效
  （更早的安装包没有 preload/updater 代码，只能走「打开下载页」）。
- **版本号**：`electron/package.json` 与 `application.yml` 的 `app.version` 必须一起递增
  （`build-release.sh` 会校验），且必须大于用户当前安装的版本，否则 `isUpdateAvailable=false`。
