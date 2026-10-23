# 架构与机制

本文档说明 novel-factory 的模块划分、生成管线、存储契约，以及每一项机制的**出处与实现要点**。
所有条目都对应真实代码，标注了文件与类名，便于按图索骥。

- [1. 模块划分](#1-模块划分)
- [2. 生成管线](#2-生成管线)
- [3. 存储契约](#3-存储契约)
- [4. 机制清单](#4-机制清单)
- [5. 跨切面设计原则](#5-跨切面设计原则)

---

## 1. 模块划分

六个 Maven 模块，依赖单向，`domain` 是唯一承载业务机制的地方：

| 模块 | 职责 | 内部依赖 |
|---|---|---|
| `novel_factory-types` | 枚举、值对象 | 无 |
| `novel_factory-api` | 请求/响应 DTO | 无 |
| `novel_factory-domain` | **全部机制**：管线节点、质量策略、记忆层、提示词装配、作业调度 | types, api |
| `novel_factory-infrastructure` | 文件仓库、Qdrant 向量库、LLM / Embedding 网关 | domain |
| `novel_factory-trigger` | HTTP 端点 + 请求装配 + Web 守卫 + 异常出口 | domain, api, types |
| `novel_factory-app` | 装配、配置、启动 | trigger, infrastructure |

domain 通过端口接口对外，infrastructure 提供实现：

| 端口（domain） | 实现（infrastructure） |
|---|---|
| `adapter/llm/LlmGateway` | `SpringAiLlmGateway` |
| `adapter/llm/EmbeddingGateway` | `SpringAiEmbeddingGateway` |
| `adapter/repository/IStoryRepository` | `StoryRepository`（文件仓库） |
| `adapter/repository/VectorStore` | `QdrantVectorStore` |

技术栈：Java 17、Spring Boot 3.4.3、Spring AI 1.0（OpenAI 兼容协议）、Qdrant java client 1.14.1（gRPC）。

trigger 内部按角色分包，控制器只做「解析 → 调用 → 返回」：

| 包 | 职责 |
|---|---|
| `trigger.http` | `@RestController`：解析请求、调用领域服务、返回 DTO，不含业务规则 |
| `trigger.assembler` | 请求 DTO ⇄ 领域对象（命令 / 聚合）；纯字段搬运 + 入口格式校验 |
| `trigger.web` | 与具体端点无关的 Servlet 层组件（跨站写请求守卫） |
| `trigger.advice` | 统一异常出口：入参类错误码（0002/0003/0004）→ 400，其余 → 500；409/502/503 等端点特有意状态码仍由端点自己抛 |

domain 的每个子包都带 `package-info.java`，写明「这个包放什么、入口类是谁、失败语义如何」。
读陌生代码时先看包注释，通常能省掉一次从 controller 往下追的搜索。

## 2. 生成管线

### 2.1 批级节点链

`StoryGenerateService` 驱动一条显式的节点链（`domain/.../service/armory/node/`，共 12 个节点）：

```
RootNode
 └─ ValidateUserInputNode
     └─ BuildStoryContextNode          # 故事设定 + 章节带目标窗口化
         └─ BuildVolumeBlueprintNode    # 卷蓝图
             └─ BuildStageBlueprintNode # 阶段（弧）蓝图，含时间跳接判定
                 └─ BuildChapterPlanPromptNode
                     └─ CallChapterPlanLlmNode
                         └─ ParseChapterPlanNode
                             └─ ValidateChapterPlanNode
                                 └─ PlanApprovalGateNode    # 可选：人工裁决门
                                     └─ GenerateChapterContentNode
                                         └─ PersistChapterPlanNode
```

两个非显然点：

- **审批门不阻塞线程。** `PlanApprovalGateNode` 在挂起时抛 `PlanApprovalSuspendedException` 作为控制信号，作业转为 `AWAITING_APPROVAL` 并**归还工作线程**——`jobExecutor` 是单线程池，在工作线程里等待会冻住所有排队作业。裁决或超时后复用同一 jobId 与 run 目录续跑。关闭该门时节点链与历史行为逐字节一致。
- **跨阶段批次的计划是惰性分段生成的**，所以审批门只拦得到首段计划（配置项 `plan-approval.scope` 可限定为首批）。

### 2.2 逐章循环（ChapterWorker）

`domain/.../service/armory/worker/ChapterWorker` 是每章的实际执行体，顺序大致为：

1. 惰性分段规划（跨阶段批次按段规划，避免用陈旧大纲）
2. 取消 / 预算检查（命中预算熔断则**当前章写完即停**，此前章节均已落盘）
3. **分层记忆前缀装配**：末态红线 → 记忆块 → 风格警示 → 疲劳词红线 → 一致性索引 → 禁泄清单 → 指纹 → 情节黑名单 → 审校反馈回灌 → `PromptBudgetGuard` 统一裁剪装配
4. 章节契约判定与写作模式选择（FREE / SCAFFOLDED / RECOVERY）
5. 节拍生成（失败则回退通用骨架）
6. 正文生成（2 次尝试 + JSON 修复 + 正文主体抢救）
7. 段落密度审校
8. 审校 → 修订 → 复审闭环
9. 候选选优（低置信章触发）
10. 质量债结算、逐章摘要、检查点落盘

## 3. 存储契约

`StoryRepository` 用文件系统承载全部故事状态，**没有数据库**。目录结构：

```
docs/workspace/stories/
└─ <yyyyMMdd>-story-XXXX/            # 故事目录（序号分配用 CREATE_NEW 语义，并发下仅一方成功）
   ├─ story-bible.json               # 设定集
   ├─ story-meta.json                # 元数据（含 sticky 的完结上限）
   ├─ chapters/                      # 正文
   ├─ memory/                        # 摘要、三账本、一致性索引、滚动大纲、质量债、
   │                                 # 伏笔排期/结算、风格统计、审计样本 jsonl
   ├─ record/run-XXXX/               # 每次运行：章节计划、体检报告等
   └─ checkpoints/cp-<millis>-<ver>/ # 检查点快照（环形保留最近 8 个）
```

保证语义：

| 关注点 | 做法 |
|---|---|
| 崩溃安全 | 所有写操作先写同目录 `.<name>.tmp` 再 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`，不支持原子移动时降级普通 move，`finally` 清理残片 |
| 并发 | 以 `storyDir` 为键的 `ConcurrentHashMap<String, ReentrantLock>` 串行化同一故事的写 |
| 路径安全 | 目录名正则校验 + `toRealPath()` 确认落在 workspace 根下且为直接子目录（防符号链接越界与 `../`） |
| **读失败分两档** | 记忆类 JSON 损坏 → 抛 `AppException` **硬失败**（"记忆是续写的唯一依据，静默降级为空等于整体丢失账本与伏笔账"）；观测类文件（job-status、jsonl 样本）损坏 → 告警返回空 |
| 追加型文件 | `audit` / `candidate` / `quality-trend` 三个 jsonl 用 `APPEND`，单行 JSON 崩溃最多丢半行 |

续写时的**锁步校验**（`validateResumeLockStep`）：正文最大章号必须等于摘要最大章号，且 1..N 连续无缺口，否则硬失败——防止在损坏目录上错位续写。

## 4. 机制清单

按问题域分组。每条格式为：**机制** → 出处 → 要点。

### 4.1 一致性：不让模型"记错"

**三账本 + 证据链**
`model/valobj/LedgerEntry`、`quality/EvidenceMatch`、`memory/ChapterSummaryService`
角色/物品/势力三类账本共用条目结构，按章序滚动合并（后章覆盖前章同名条目状态）。每个条目记录**正文原文引证**与最近章节号，构成"状态出自第几章、哪段正文"的追溯链。

`EvidenceMatch` 是全项目最典型的机械校验：8 档匹配（`exact → normalized → fragmented → phrase-covered → anchored → spread → phrase-partial → no-match`），前 6 档可入账，后两档为"放宽留痕档"（调用方须把档位写回供统计）。降档逻辑是逐级的：整串包含 → 去空白/统一引号后包含 → 省略号分段（各段可定位且最小包围区间 ≤ 正文 50% 或 ≤400 字）→ 按中文标点切短语（≥6 字短语全部命中≥2 条，或单条短语 ≥8 字锚长）。设计原则写在枚举注释里：**"放宽的是表述，不是事实有无"**。

**认知边界（防"未卜先知"）**
`contract/ChapterContract.extractKnowledgeBoundary`
从最近 3 章的角色状态条目里筛出含"知道/不知/怀疑/确认/以为/身份/察觉"等认知特征词的项，后写覆盖先写，渲染成"角色——认知状态"串注入提示词。

**禁泄清单**
`quality/SecrecyViolationPolicy`
对每个谜底关键词在正文里用 `indexOf` 循环计数（不用正则，避免特殊字符问题），任一命中即产出 `dimension=foreshadow, severity=BLOCKING` 的问题项，证据是"关键词×次数"。纯字符串机械校验，不做语义判断——宁可漏报也不误报。

**一致性索引 / 关系轨迹**
`memory/ConsistencyIndexService`、`quality/RelationTrajectoryPolicy`
关系态从摘要的事实项里筛 `RELATION`，同 pair 保留最近一次关系值与章号，按最近更新降序渲染（上限 20 条，超出显示"已封存 N 条"），并要求"改写关系态必须由本章正文事件支撑"。

**时序锚与进度对齐**
`plan/StoryPacing`、`quality/PlanAdherencePolicy`
`latestAnchorYear` 从最新章向前找第一个 `timePoint` 里的 4 位年份；`budgetStartYear` 由大纲段解析取预算起始年。注释明确要求与 `BatchHealthService#addOutlinePacing` **用同一把尺子**——"两边各算各的会出现体检说滞后、规划说无需跳接的口径分裂"。

### 4.2 伏笔：从埋设到清账的完整生命周期

**排期状态机**
`memory/ForeshadowScheduleService`
`PLANNED → PLANTED → PAID`，未在 `plantChapter+2` 章内埋设则 `PLANNED → MISSED`。本章 seed 与窗口内排期项用"精确相等优先、退 LCS≥4 字"配对（一条排期项只认领一个 seed，先到先得）；兑现匹配须状态为 `PLANTED` 且意图相似度 ≥4 字。跨版本按归一化 intent 去重展平。

**动态权重**
`memory/ForeshadowPriorityService`
`score = importance×10 + 滞留章数×5`，**纯函数**——可从摘要确定性重算，零新增存储。阈值 60=软（推荐回收）/ 80=硬（计划必须逐条评估）/ 100=熔断（冻结为未填）。**主线核心（importance=5）永不熔断**：实测第 11 章被熔断的正是主线谜题，冻结会让写手失去主线悬念上下文，因此封顶在硬级。分数只决定关注强度，**回收执行权始终归规划层按剧情相关性判断——绝不强排（防注水）**。

**清账与结算**
`memory/ForeshadowSettlementService`、`quality/ForeshadowSpanPolicy`、`quality/ForeshadowSettlementPolicy`
卷末清账裁决，区分"真兑现/静默兑现/弃置"，弃置需记录理由；跨度类指标只统计已声明兑现义务的条目，并在样本被掏空时把"寿命指标"标记为不可信——**防止"指标消失"被误读成"伏笔变健康"**。

### 4.3 正文质量：机械门禁

**15 个质量策略**（`quality/*Policy`，该包共 21 个类）
章节长度、标题、内容密度、对白比例、段落结构、悬念阶梯、地点轨迹、关系轨迹、计划遵循、禁泄、风格违规、疲劳模式、已用模式、伏笔跨度、伏笔结算、出口条件。

其中两个值得单独说：

- **`SuspenseLadderPolicy`**：把计划回填的 `suspenseBeat` 解析成悬念档位下标（容忍"1. 档位原文""档位原文（解释）"等写法，**出现歧义宁可判 -1 不通过**），检测档位倒退（"产生怀疑又被自我否定圆回"）、连续 ≥3 章同档停留（主线原地）、相邻章描述逐字相同。同一停留段只报一次。
- **`PlanAdherencePolicy`**：覆盖率 = 可检出事件数/有效事件数。单条事件先剥掉括号内的执行提示（"回收第N章埋设的XX"不要求字面落入正文），再算正文与事件的**最长公共连续子串**（一维滚动 DP），≥4 字即视为覆盖。覆盖率 <0.6 时注入"计划覆盖预警"块，且措辞明确要求审校"本核对按词面匹配，可能误报，**严禁为凑数捏造问题**"。

**疲劳词表与情节黑名单**
`quality/FatiguePatternCatalog`、`quality/UsedPatternPolicy`
词表加载 classpath `assets/config/fatigue-patterns.txt`（`[category]` 分节为 adverb/eye/body/extra 四桶），文件缺失或解析为空则回退内置词表（60+ 中文套话）并告警。单章门禁与跨章统计**共用同一份词表**，避免两处硬编码漂移。

`UsedPatternPolicy` 不依赖语义理解，靠结构化标注统计：按 `placePoint`（回退 `timePoint`）聚合舞台复用次数、同（舞台×章型）组合连用、角色作为冲突发起方次数（21 个冲突动词词表）、能力展示章数。回看窗口正文 5 章、规划 10 章——注释记录了实测原因：5 章窗口看不到 8–16 章的重复史。输出是给模型看的"已用情节模式·本章必须避开"文本块。

**段落密度审校与修订闭环**
`audit/ParagraphDensityAuditService`、`revise/ChapterReviseService`
审校 → 修订（最多 2 轮，支持 patch 模式）→ 复审；修订后必须复审才算闭环。

### 4.4 候选盲评：对抗自我偏好

`candidate/ChapterCandidateService`、`candidate/CandidateSampleService`、`quality/QualityGate`
审校"低置信通过"（MINOR 残留或经过修订才闭环）的章，用**第二个模型族**整章重写为挑战者，机械排序 + 盲评二选一；挑战者复审不过则自动回退原稿——**保证闸门拒绝率不升**。

重写与评审走独立场景配置（`scene-models.chapter-rewrite` / `chapter-judge`），使得"写手"与"评审"分属不同模型族，避免自偏好。只有单一模型族时需把 `candidate.enabled` 置为 `false`。

### 4.5 记忆与提示词装配

**分层记忆前缀 + 总预算**
`prompt/PromptBudgetGuard`、`model/valobj/properties/PromptBudgetProperties`
前缀由 8+ 块拼成，每块有独立封顶，但**封顶之和会随块数增长击穿模型输入窗口**，所以有总额约束 + 淘汰次序。关键设计：

- `PrefixBlock` 枚举携带 `priority` 与 `truncatable`；**渲染顺序 = 入参顺序，淘汰次序 = priority，两者分离**——超预算只影响谁被丢，不影响谁在前。
- 可截断块可截到分段边界（剩余 <200 字则整块弃）；`SECRECY_GUARD` **标记为不可截断**（逐字扫描同源，截断会静默丢词），放不下就整块弃并在日志点名"依赖下游兜底"。
- 同一场景内重复 label 直接 **fail-fast**（重复 label 会让同一块渲染两次，击穿预算不变式）。
- 日志强制自洽：合计 = 块内容字 + 分隔字，且逐块清单之和恰等于块内容字，便于脚本对账。
- 两个独立预算：正文前缀 18000 字符、计划输入段 20000 字符。裁剪触及核心块或丢了不可截断块时升 WARN，措辞是"**回查块膨胀来源，而非直接调大上限**"。

**章节目标窗口化**
`prompt/ChapterGoalWindowPolicy`
按下一待写章号裁剪章节带目标（回看 3 章、前瞻 40 章），解析"卷（`||`）→ 带（`；`）→ 章号区间"；结构不认识或缺章号区间一律 fail-soft 返回原文并追加"已按进度窗口化"注记。

**动态参考资料（skill 式按需注入）**
`prompt/reference/DynamicReferenceService`、`VectorReferenceRetriever`、`LlmReferenceSelector`
两种模式：`vector` = 向量语义检索（命中点按文件名聚合去重，低于 `minScore` 丢弃）；`llm` = 把索引（文件名|标题|简介）交模型挑选，返回结果经索引白名单过滤。带 LRU 缓存（key 含索引哈希，索引更新即失效）。

**失败语义刻意不对称**（这是重点）：向量模式任何失败（欠费/鉴权/向量库/网络）→ 抛异常**终止作业**，注释写明"不降级 LLM 选择，静默降级会让作业在低质量注入下跑完"；而 LLM 选择模式失败 → 返回空列表不阻塞。

**跨章记忆**
`memory/StoryMemoryService`、`ChapterMemoryService`、`StyleStatService`
摘要/账本/设定分集合索引到 Qdrant，按相似度检索（`top-k`、`min-score`、字符预算贪心累计）。向量检索失败时章走"无检索唤醒"，并由体检指标 `recallDegradedShare` 统计占比。

### 4.6 作业：长跑与无人值守

**预算熔断**
`job/LlmBudgetFuse`
网关每次调用（**成功与失败路径都算**）经 `SpringAiLlmGateway.recordUsage` → `budgetFuse.record(jobId, totalTokens)` 上报；`ConcurrentHashMap.merge` 原子累加，jobId 取自 MDC 的 `trace-id`，同时回填 `GenerationJob.tokensUsed`。用量观测与预算开关**解耦**——没配阈值也照常累计。命中硬上限时置信号，`ChapterWorker` 在每章迭代开头检查并 break——**当前章完整落盘，可 resume**。默认阈值 `warn-total-tokens: 1200000` / `hard-total-tokens: 2500000`。

**错误分类**
`armory/llm/LlmErrorClassifier`
7 类错误，每类带两个**正交布尔**：`retryable`（同模型重试是否有意义）与 `modelLevel`（换模型是否有意义）。规则表按顺序匹配（顺序敏感：`content_policy_violation` 必须先于宽泛的 403/额度判断），且遍历 cause 链取全部 message 拼接后匹配（HTTP 码常埋在内层 cause），深度上限 8 防自引用；异常类型优先于 message 关键词。

**降级链**
`armory/llm/ModelFallbackChain`
"主模型 → 场景备选 → default 备选"有序去重，**硬上限 3 个**（防一条长链一次失败烧掉整串费用）。备选模型复制主模型的全部参数（maxTokens/temperature/enableThinking/baseUrl/apiKey/completionsPath），只替换模型名。网关逐链尝试，**唯一不降级的是 `CONTENT_POLICY`**。选型原则是备选尽量**跨模型族**——免费档/上游拒绝常按模型计量，同族备选可能共享同一份额度而一起失败。

**无人值守续批**
`job/RunPlanService`
`decide(chaptersDone, batchesDone, health)` 是**纯函数**，依序判停：开关未开（默认 false）→ 达标 → 批数上限 → **批末体检等级 ≥ 停机阈值**。`parseStopLevel` 对无法识别的配置值一律取最保守的 `CRITICAL`（宁可能多跑不误停）。续批子作业在**当前作业终态之后**投递，异常 fail-soft——"续批失败不会把一批成功的成果标成失败"；达标判定以磁盘实际章节文件数为准，不信内存计数。

**检查点/回滚**
`armory/CheckpointService`
快照采集"≤chapterCount 的正文 + memory 全部记忆文件（含**伏笔排期表**）+ bible"。排期表进快照是必须的：丢失会让 seed 的 `scheduledPayoffChapter` 变成悬空引用。按 `cp-<millis>-<version>` 命名，**环形保留最近 8 个**。回滚 = 锁内按 manifest 整份还原 → 裁剪超出章号的正文 → 重建向量索引（fail-soft）。回滚后正文/摘要/蓝图/账本回到同一时点，续写锁步校验天然通过。

### 4.7 质量债

`memory/QualityDebtService`
每章审校后按 `dimension` 集合比对旧债：同维度复发 → `cleanStreak=0`（债自然续期）；连续 **2 章**无同维度复发 → 核销（与回灌窗口对齐，一笔债最多注入 2 次）。**dimension 缺失的债永远不核销、也不累计 streak**——"无验证信号不伪造结论"。必须在记入本章新债**之前**调用，避免"本章问题与自身比对"。核销后同类问题复发会随新章债务再次进入回灌，系统自愈。

### 4.8 校准与评测

`audit/AuditSampleService`、`audit/AuditPromptVariant`
- **失败样本回流**：把"修订循环耗尽仍未解决的 BLOCKING"连同最终正文、章节计划、账本提示词、伏笔清单以单行 JSON 追加到 `memory/audit-runtime-samples.jsonl`，`attemptCount` 记录修订轮数。观测性文件全程自吞异常。
- **A/B 变体通道**：`AuditPromptVariant` 的 A 分支与生产行为逐字一致，B 为实验分支，通过系统属性 `audit.prompt.variant` 切换——不设属性时恒为 A，"开关默认无害、不构成运行时行为分歧"。
- **离线评测框架**：`novel_factory-infrastructure/src/test/.../calibration/` 6 个类 1428 行，由系统属性开关控制（`-Daudit.eval=true` / `-Daudit.calibration=true` / `-Deval.blind=true`），包括审校校准、跨系统盲评、运行时采样。

### 4.9 一键设定集

`draft/SettingDraftService`
10 字段设定集，`FIELDS` 字段描述表**一处声明同时驱动**四件事：请求/响应键集合、提示词里的中文字段名、"哪些字段已有值"、锁定字段回填。重试纠错区分两种失败——`parseFailed`（JSON 未解析）与 `missing`（解析成功但字段空）——第二次尝试追加的纠错指令不同（"笼统说输出不合法会让模型去改格式而漏掉真缺的内容"）。

`lockNonTargetFields` 在模型输出后机械回填非目标字段，这就是"**只重生成大纲不会顺手改掉世界观**"的机制；`targets` 含未知字段名直接报错，防"点了重生却什么都没变"。

## 5. 跨切面设计原则

把上面 20 项机制抽象一下，贯穿全项目的取舍是这几条：

**5.1 能机械校验的，绝不上 LLM。**
证据链（8 档匹配）、禁泄（逐字扫描）、计划遵循（LCS）、重复用语（词表统计）、悬念档位（下标解析）——全部可复算、可对账、无随机性。凡"模型觉得像"的地方都退回到可验证的字符串/结构化判定。**放宽的是表述，不是事实有无。**

**5.2 失败语义逐处设计，不搞统一降级。**
- 硬失败：记忆文件损坏、向量资料检索失败、续写章号不连续 → **终止**，拒绝在低质量状态下继续跑完
- 静默降级但**留痕**：向量检索失败走"无检索唤醒"，由 `recallDegradedShare` 统计；LLM 选资料失败返回空
- fail-soft：预算熔断、检查点重建索引、自动续批、观测性文件写入 → 异常不反噬主流程
- 不降级：`CONTENT_POLICY` 错误不换模型重试

**5.3 状态尽量做成纯函数或状态机。**
伏笔权重与续批决策是纯函数（零新增存储、可确定性重算）；写作模式、伏笔排期、质量债、完结上限都是显式状态机，而不是散落的布尔标志位。

**5.4 让"不祥的沉默"变成可见的指标。**
样本不足时指标会被标记为不可信而不是报 0（"指标消失"与"变健康"长得一模一样）；降级必须留痕（`recallDegradedShare`、`auditVerifyDegradedShare`、`fallbackModeShare`）；不可截断块被丢弃要在日志点名。**任何静默的降级都被视为缺陷。**

**5.5 长跑要可中断、可回滚、可续写。**
预算命中在章边界停（不留半截章）、逐章检查点、审批门归还线程而非阻塞、续写前锁步校验、续批失败不影响已成功批次。
