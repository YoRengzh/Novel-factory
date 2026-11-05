# 领域模型归位与分层解耦 — 完整迁移计划

> 依据：2026-11-05 全仓耦合审计（模型类散布 / trigger 层实体泄漏 / model 包混入配置与 Jackson 物 / types 枚举命名撞车）。
> 目标：**领域对象只住在 `model/`，trigger 层只认 DTO，model 包零杂物**。
> 硬约束：**全程不改任何 HTTP JSON 契约、不改持久化文件格式、不改前端**。
>
> **执行状态（2026-11-05）**：Phase 0~5 全部完成，改动保留在工作区待人工审核（未提交、未推送）。
> 全量 `mvn test`：1606 tests / 0 failures / 0 errors（9 skip 为既有）。
> 验收门：`grep "domain.novel.model" novel_factory-trigger/src/main` = **0**；trigger 对 `domain.novel.config` 引用 = **0**。

---

## 0. 总览

### 0.1 执行顺序（风险递增）

| 阶段 | 内容 | 性质 | 预估触碰文件 |
|---|---|---|---|
| Phase 0 | 工作区清空 + 基线 | 前置 | 0（提交现有 9 个脏文件） |
| Phase 1 | 11 个模型类归位 `model/valobj` | 纯机械 | ~80 |
| Phase 2 | 5 个 `@ConfigurationProperties` 迁出 model | 纯机械 | ~45 |
| Phase 3 | Jackson 反序列化器迁 types + 2 个枚举改名 | 纯机械 | ~40 |
| Phase 4 | trigger 解耦（应用服务下沉 domain） | **行为重构** | ~30（含测试重写） |
| Phase 5 | ArchUnit 守护 + 文档 | 防回潮 | ~5 |

每个阶段独立成 1~3 个 commit，`mvn test` 全绿才进下一阶段，可单独 revert。

### 0.2 审计修正：这些**不搬**（此前审计误伤）

以下类此前被列为"黑户"，深入核实后确认它们是**行为类/静态工具，不是模型对象**，留在 service 包是正确的：

| 类 | 定性 | 证据 |
|---|---|---|
| `service/armory/LlmRuntimeConfig` | `@Component` **有状态配置服务**（读写 JSON 覆盖文件、回写 properties） | `@PostConstruct`、`@Autowired` setter、12 个消费者横跨 3 模块 |
| `service/armory/FeatureToggles` | `@Component` 开关注册表（把值写回 4 个 properties bean） | 与 LlmRuntimeConfig 同包互引 |
| `service/armory/CreativeNotes` | 静态工具（私有构造 + 静态 `block`） | 3 个消费者全用 FQN |
| `service/armory/memory/ChapterMemoryText` | 静态格式化工具 | `final` + 私有构造 + 静态 `of` |
| `service/armory/quality/EvidenceMatch` | 静态文本比对工具 | `final` + 私有构造 |
| `service/armory/prompt/reference/ReferenceGroup` | 静态文件名规则表 | `final` + 私有构造 |

LlmRuntimeConfig（1301 行）的拆分是独立的内部重构，见 §6 排除项。

---

## 1. Phase 1 — 模型类归位 `model/valobj`

### 1.1 迁移清单（11 个类）

| # | 类 | 从 | 到 | 消费者 | 特殊处理 |
|---|---|---|---|---|---|
| 1 | `BookFormat` | `service/export/` | `model/valobj/`（平铺，紧邻 `BookExport`） | 3 | **消除 `BookExport.java:3` model→service 逆向依赖**（全仓唯一） |
| 2 | `ReferenceSelection` | `.../prompt/reference/` | `model/valobj/prompt/` | 1 | ⚠️ LLM 结构化输出目标（`BeanOutputConverter`），**字段名是 prompt 契约，不可改** |
| 3 | `ChapterContract` | `service/armory/contract/` | `model/valobj/contract/` | 4 | `ChapterWorkerTest` 有 5 处 FQN 调用需文本级更新 |
| 4 | `GenerationMode` | `service/armory/contract/` | `model/valobj/contract/` | 2 | 与 ChapterContract 同包互引，**必须一起搬**；枚举常量名是持久化契约（`ChapterSummaryEntity.generationMode` 存 `mode.name()`），**常量名不可改** |
| 5 | `StoryDate` | `service/armory/plan/` | `model/valobj/plan/` | 2（全同包） | package-private 方法 `advanceFromRelative` 被 `TimeAdvanceChecksTest:107` 反射调用——按名查找，搬家不受影响 |
| 6 | `ReferenceChunk` | `.../prompt/reference/` | `model/valobj/prompt/` | 3（全同包） | — |
| 7 | `ReferenceIndexEntry` | `.../prompt/reference/` | `model/valobj/prompt/` | 10 | 4 参兼容构造引用同包 `ReferenceGroup.TECHNIQUE`（ReferenceGroup 留下）→ 补 import |
| 8 | `GateResult`（+`PassGrade`） | `service/armory/quality/` | `model/valobj/quality/` | 6 | `QualityGate` 同包无 import 使用 8 处 `GateResult.of` → 补 import |
| 9 | `BatchHealthReport`（+`Metric/Level/Direction`） | `service/armory/quality/` | `model/valobj/quality/` | 9 | `BatchHealthService` 补 import；`StoryManageController:547` FQN 返回类型、`StoryJobServiceTest:133-139` FQN 需更新（Phase 4 会再移除 controller 处） |
| 10 | `PromptRule`（+`InjectPosition`） | `.../prompt/valobj/` | `model/valobj/prompt/` | 22 | 策略接口签名用 `InjectPosition`，多数消费者同时引用 |
| 11 | `PromptContext` | `.../prompt/valobj/` | `model/valobj/prompt/` | **37**（含跨模块 trigger 1 处） | 爆炸半径最大；`SettingDraftServiceTest:257` 有 FQN ArgumentCaptor |

搬空后删除两个空包：`service/armory/prompt/valobj/`、`service/armory/contract/`。

**包结构决策**：`model/valobj` 下按领域建子包（`prompt/`、`quality/`、`plan/`、`contract/`），既有 `properties/` 子包是先例；`BookFormat` 平铺与 `BookExport` 同级。现有 7 个平铺 VO（StoryVO、LlmCall 等）不动。

### 1.2 机械步骤（每类一批）

1. `git mv` 保留历史；
2. 改 `package` 声明，为"原同包、留下的一方"补 import（清单见上表）；
3. 用 UTF-8 安全脚本批量给消费者插 import（禁用 GBK 控制台 sed 直改含中文注释的文件，脚本跑完 `git diff` 人工抽查）；
4. FQN 内联引用逐处手工改（CreativeNotes 不搬所以无此项；本阶段 FQN 共 ~10 处）；
5. `mvn -pl novel_factory-domain -am compile` → `mvn test`。

### 1.3 本阶段验证

- 全量 `mvn test`（README 口径 1562 用例，1~2 分钟）；
- `grep -rn "service\.armory\.\(prompt\.valobj\|contract\)" --include="*.java"` 归零；
- `grep -rn "import cn\.novel\.yonren\.domain\.novel\.service" novel_factory-domain/src/main/java/cn/novel/yonren/domain/novel/model/` 归零（逆向依赖清零）。

---

## 2. Phase 2 — 配置类迁出 `model/valobj/properties`

### 2.1 迁移清单（5 个类 → `cn.novel.yonren.domain.novel.config/`）

仍留在 **domain 模块**（消费方绝大多数是 domain 服务；搬 app 会造成循环依赖）。Spring 注册不受影响：4 个带 `@Component` 靠组件扫描（包移动透明），`StoryProperties` 靠 `Application.java:26` 的 `@EnableConfigurationProperties` 显式注册 → **更新该处 FQN**。

| 类 | prefix | 消费者 | 注 |
|---|---|---|---|
| `StoryProperties` | `story` | **~49 文件**（domain main 16 + domain test 18 + infra test 4 + trigger main 6 + trigger test 4 + app 1） | `toStoryVO()` 保留（7 处生产调用）；FQN 引用需文本更新：`LlmRuntimeConfigProviderTest:346` 等 2 文件、infra calibration 3 文件、`StoryManageController:82` |
| `QualityReviewProperties` | `story.quality-review` | 4 | — |
| `PromptBudgetProperties` | `story.prompt.budget` | 4 | `ChapterWorkerTest:154` FQN |
| `ReferenceSelectorProperties` | `story.prompt.reference-selector` | 8 | `LlmConfigController:41` FQN；**顺手修 `:9` 过时注释**（写着"trigger 层的 StoryProperties"，实际在 domain） |
| `StoryMemoryProperties` | `story.prompt.story-memory` | 4 | — |

trigger 侧 6 个消费者会在 Phase 4 自然消失（服务下沉后由 domain 内部注入），本阶段先机械改 import 接受双重触碰。

### 2.2 验证

- `mvn test`；`ApplicationContextSmokeTest`（全仓唯一 `@SpringBootTest`）必须绿——它验证配置绑定没断；
- `StoryPropertiesBindingTest`（同包免 import）→ 改 import；
- yml 键零变化（`@ConfigurationProperties` prefix 不动）。

---

## 3. Phase 3 — Jackson 迁 types + 枚举改名

### 3.1 `CharacterNameListDeserializer`：`model/jackson/` → `types/utils/`

- 唯一使用点：`ChapterBeatsEntity.java:3,33`（`@JsonDeserialize` 标在 `Beat.characters`）；
- types 的 pom 已带 `spring-boot-starter-web`（传递 jackson），domain 已依赖 types → 依赖方向成立；
- 与 `JsonParseFallback`、`JsonRepair` 同居 `types/utils`，语义一致（JSON 容错工具）；
- 同步搬迁 `CharacterNameListDeserializerTest` → types test；删除空包 `model/jackson/`。

### 3.2 枚举改名（类名改，**常量名与 code 一律不动**——持久化与模型输出契约）

| 改名 | 影响文件 | FQN 易漏点 |
|---|---|---|
| `ChapterTypeVO` → `ChapterType` | 23（domain main 10 + domain test 12 + trigger 1） | `ChapterPlanChecks:155,221`、`ChapterWorker:767,965`、`QualityGateTest:716`、`ChapterWorkerTest:437,547` |
| `GenreTypeVO` → `GenreType` | 13（api 注释 1 + domain 9 + types test 1） | `ChapterWorker:540` |

- `GenreTypeVOTest` → `GenreTypeTest`；
- `api/dto/StyleViewDTO:7,13` javadoc 同步更新；
- 其余 6 个 types 枚举本就无 VO 后缀，改后风格统一。

---

## 4. Phase 4 — trigger 解耦（唯一的行为重构阶段）

### 4.1 设计原则

**沿用仓内既有先例，不发明新架构**：domain 服务返回 api DTO 已有三处先例（`SegmentEditService`、`UserAssetStore`、`LlmRuntimeConfig`），domain→api 的 pom 依赖是既成架构；domain 服务内部注入 `StoryProperties` 并调 `toStoryVO()` 也有先例（`CheckpointService:55`、`ChapterEditService:64`）。本阶段把 trigger 侧的例外收敛到这个模式上。

### 4.2 工作项

**4.2.1 新建 `domain/novel/service/manage/StoryManageService`（@Service）**
吸收 `StoryManageController`（819 行）的全部业务逻辑，controller 瘦身为 ~200 行纯 HTTP 转发 + 错误映射（保留现有 `ResponseStatusException` 语义，响应体不变）：

- 下沉方法：`listStories`、`resume`、`listChapters`、`readChapter`、`saveChapter`、`workbench`、`listCheckpoints`、`createCheckpoint`、`restoreCheckpoint`、`segmentEdit`（含 `buildSegmentPromptContext`）、`health`、`exportBook`、`pendingFacts`、`factTimeline`、`deleteStory`；
- 随之下沉的模型触碰：`new ChapterContentEntity()`（controller `:449`）、`ChapterLengthPolicy` 门禁（`:443`）、`ChapterSummaryEntity::getItemStates` 方法引用（`:292-293`）、手工 DTO 转换器（`toCheckpointDTO`/`toLedgerDTO`/`toQualityDebtDTO`/`collectPendingFacts`/`collectMatchingFacts`，`:691-777`）、`BookFormat.fromParam`（`:571`）；
- 注入：`IStoryRepository`、`JobRegistry`、`ChapterEditService`、`SegmentEditService`、`ChapterMemoryService`、`StoryMemoryService`、`BatchHealthService`、`CandidateSampleService`、`CheckpointService`、`BookExportService`、`StoryProperties`（内部 `toStoryVO()`，替代 controller `:515`）。

**DTO 补齐（api 模块）**：
- `BatchHealthDTO`：镜像 `BatchHealthReport` 的 record 组件名（含嵌套 `Metric/Level/Direction`），**字段名逐一对齐保证 JSON 形状不变**；`/health` 与 `JobStatusResponseDTO.healthReport`（现 `Object`，注释自认原样序列化）统一改用它，两处口径合一；
- `DeleteStoryResponseDTO`：从 controller 内部 record（`:654`）迁出；
- `StoryGenerateResponseDTO.chapterPlan`、`JobStatusResponseDTO.pendingChapterPlan` 维持 `Object`（由 domain 服务塞领域对象，JSON 不变，trigger 不 import 类型）——**记为已知债，不在本次扩大**。

**4.2.2 `StoryGenerateController` + `JobController` 命令装配下沉**
- `StoryCommandAssembler`（trigger 静态工具，123 行）整体迁至 `domain/novel/service/armory/`，`StoryCommandAssemblerTest` 随迁；
- `StoryGenerateService.generate(ArmoryCommandEntity)` → `generate(StoryGenerateRequestDTO)`，内部走 assembler；
- `StoryJobService` 各方法签名 DTO 化，`get` 直接返回 `JobStatusResponseDTO`（`GenerationJob` 15+ 字段拆装 `toDTO` 移入 domain）。

**4.2.3 `SettingDraftController`**：`SettingDraftService.draft(StoryVO, DraftRequest)` → `draft(SettingDraftRequestDTO)`，嵌套 record 转换移入服务，`StoryProperties` 内部注入。

**4.2.4 `LlmConfigController`**：`LlmRuntimeConfig.view/save` 收敛为无 properties 参数签名（内部注入 `StoryProperties` + `ReferenceSelectorProperties`），controller 纯转发。

**4.2.5 `ChatGptAuthController`**：3 个端口嵌套 record（`AuthStatus`/`Authorization`/`ModelOption`）→ api DTO（字段名对齐）。端口接口本身的注入保留（适配器端口不算模型泄漏）。

**4.2.6 测试策略**
- `StoryManageControllerTest`（559 行）重写：mock `StoryManageService`，断言 DTO 透传与 HTTP 状态码（~250 行）；
- **新建 `StoryManageServiceTest`（domain）**：承接原 controller 测试里有价值的仓储级断言——`ArgumentCaptor<List<ChapterContentEntity>>` 的"标题保留/空标题"用例、`pendingFacts`/`factTimeline` 两桶匹配、`resume` 字段映射、`exportBook` ×4；
- `JobControllerTest`/`SettingDraftControllerTest`/`LlmConfigControllerTest`：mock 目标从 entity/properties 改为服务 DTO 签名（这两处现在 stub `storyProperties.toStoryVO()`，改后桩消失）。

### 4.3 验收门（本阶段定义完成）

```
grep -rn "domain\.novel\.model" novel_factory-trigger/src/main --include="*.java"   # 必须 0
mvn test                                                                            # 全绿
```

外加手动冒烟：`mvn -pl novel_factory-app spring-boot:run` → 前端过一遍 工作台 / 保存章节 / 段落编辑 / 体检 / 检查点 / 导出 / LLM 配置页，比对 Network 面板 JSON 与迁移前一致。

---

## 5. Phase 5 — 守护与文档（已完成）

1. **ArchUnit**（`archunit-junit5 1.3.0`，test scope，根 pom `dependencyManagement` 统一管版本，
   trigger pom 引入）→ `novel_factory-trigger/src/test/java/cn/novel/yonren/trigger/ArchBoundaryTest.java`：
   - 规则 A：`trigger..` 不得依赖 `domain.novel.model.{entity,valobj,aggregate}..`；
   - 规则 B：`domain.novel.model..` 不得依赖 `..service..` / `..adapter..` / `..config..`；
   - 规则 C：`api..` 不得依赖 `domain..` / `trigger..` / `infrastructure..`。
   三条规则实测全部通过（3 tests）。
2. `model/package-info.java` 已补：声明纯数据层纪律 + 子包布局（entity/valobj/aggregate），
   并注明由规则 B 守护。
3. `docs/architecture.md` 已更新：删除已不存在的 `trigger.assembler` 行（assembler 已迁 domain），
   补「trigger 只认 api DTO」「model 为纯数据层」两段边界说明与 ArchUnit 守护位置。
4. 终验：`mvn test` 全量通过；`mvn -pl novel_factory-app -am clean package` + `java -jar` 冒烟
   **留待人工审核后执行**（本次未做打包验证）。

---

## 6. 明确排除项（另行立项，不混入本次）

| 事项 | 原因 |
|---|---|
| `LlmRuntimeConfig`（1301 行）拆分 | 它是配置服务不是模型；Overlay JSON 的文件 IO 应抽到 infrastructure 经端口，属独立内部重构 |
| domain→api 反向依赖根除 | 需引入 command/result 接口层，波及 3 个既有服务，收益/风险比本次低 |
| `IStoryRepository` 聚合边界重构 | `readChapter`/`readChapterSummaries` 零散读 vs 聚合根边界是仓储接口设计问题，单独提案 |
| 前端 `app.js` 适配 | 本计划冻结 JSON 契约，前端零改动 |

---

## 7. 风险与回滚

- **最大风险**：Phase 4 的测试重写丢失断言 → 对策：`StoryManageServiceTest` 逐条搬运原 controller 测试用例名，不合并不简化；
- **JSON 契约回归**：DTO 镜像字段名逐一对照 record 组件名，`Object` 字段原样保留，枚举常量名不动；
- **编码**：中文注释文件（约七成）一律走 UTF-8 脚本 + 人工 diff 抽查，不用控制台 sed；
- **回滚**：每阶段 1~3 个独立 commit，任一阶段出问题 `git revert` 该批次即可，阶段间无隐藏耦合（Phase 4 依赖 Phase 1/2 的最终位置，revert 需按逆序）。
