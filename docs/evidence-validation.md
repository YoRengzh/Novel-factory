# 计划准入、正文交付与人物观察证据

## 目标与非目标

本批复用现有因果审查与交付验收链路，解决核验对象被替换、保密策略与实际发言不符，以及人物观察被升级为确定后果的风险。

不新增写作事件配额，不开启章间Rebase或章内滚动，不把全部心理/推断改写成显式旁白，不重写现有故事。不以Mock测试通过证明真实模型能识别全部语义问题。

## 可选核验义务

场景确有考试、鉴定、交接或交易验收时，可在 `scenes[].causality.verificationObligations` 记录：

```json
{
  "verifier": "考官",
  "subject": "学员",
  "item": "基础施法",
  "criterion": "学员独立维持灯焰十息",
  "resultUse": "决定报名资格",
  "allowedAlternative": null
}
```

- verifier/subject绑定场景内人物；item/criterion/resultUse明确核验项目、标准和结果用途。
- allowedAlternative只记录明确授权及条件，缺席不允许正文临时发明“拆穿考官即可免试”。
- 自验、失败、待验均可以是合法安排，不要求每场新增检验。
- 字段可缺席；旧四参SceneCausality构造器和null JSON/hash保持兼容。

交付验收新增固定target：`scene:<sceneId>/verification:<序号>`。有实际条款的terms也独立作为`scene:<sceneId>/terms`，不要求存在steps。身份名不是完成证明，引用必须绑定该target的交付字段与当前正文。

## 计划审查

复用 `PlanCausalityReviewService`：

- 首段与后续规划显式传入历史摘要和故事目录，正式事实引用可以回查同源正文。
- PASS须提供本场过程或明确允许概述的字段、连续原文和evidenceRole。仅说“无冲突”、用未来outcome冒充历史完成或用身份标签作证据都不授权。
- ISSUE可引用同场/跨场两个不同明确字段，双方sceneId/field/连续引文必须绑定；历史依据仍需factId/章号/hash匹配。
- null/空选填字段不算另一来源，非空错误或混合来源不得自动回退为合法证据。
- 人物策略变更不是永久禁止：保密DECISION/ESTABLISHED、INTENT/INTENDED可按同主体核对，已撤回、错主体和BELIEF不能冒充确定知识或承诺。
- reviewVersion升级，旧无证PASS缓存不复用。

## 正文审查

复用 `ChapterAuditService` 和 `DeliveryReviewPolicy`：

- 对照实际核验者、被验对象、项目、标准、结果用途；检查其他人的演示或诊断是否偷换主角自身义务。
- 只承认明确授权、正文中满足条件的替代。合法概述不要求额外演示，失败不强改成功。
- 实际发言不能被事后旁白撤销；同时允许失言、撒谎、自欺、合理变更策略和作者安排的揭示，不能一律按保密词面拦截。
- 计划冲突与正文漏交付分开处理；UNKNOWN不当成PASS。
- 计划/稿件版本及target覆盖沿用既有严格绑定，修订或候选换稿后复验。

## 人物观察与权威状态

`characterEffects`仍为权威人物状态增量；`characterBeats`改为字段级观察视图：

- goal/decision/consequence/relationshipChange/nextIntent都可为空，不为每人补齐故事动机。
- 每个字段可有独立support，包括attribution、quote/context或confirmed effect引用。
- 连续引文、偏移和来源由程序绑定，模型的verification标记不授予事实权威。
- 条件解释明确带“可能/未证实”，直接发言不证明兑现或世界真伪；可观察后果不能截掉引文中的否定或可能限定。
- 新无支持字段进pending，重复绑定不自动清空或重新接纳。
- 旧共享证据beat可读，但标“旧观察·未逐项复核，非确认事实”；不自动重写旧JSON/hash。
- Memory与人物深度反馈共用 `CharacterBeatEvidencePolicy`，避免一处隔离的推测在另一处变成确定已送达消息。

生产顺序：Summary新候选清洗 → CharacterStateProjector.bind → CharacterBeatEvidencePolicy.bind → 深度观察/落盘。

## 模式与灰度

现有开关仍保持原默认值，不自动启用ENFORCE：

```yaml
story:
  causality:
    plan-mode: OFF
    audit-mode: OFF
```

- OFF：不追加该因果/交付审查调用。
- OBSERVE：保存诊断，不据此强制阻断。
- ENFORCE：计划/交付未完成或UNKNOWN不得当作已通过；正文ENFORCE要求章节审校开启。

人物新候选证据清洗属于提取协议，不新增LLM调用。不要将观察标签转换成永久性人物禁止事项。

## 验证口径

已增加旧schema/hash、核验主体和授权替代、双字段来源、历史传递、取消/预算传播、玉简动作不等于送达、实际收到、待核与重复绑定等测试。

真实校准只读用户失败三章，最多9次GLM-5.2请求：三章计划、三章审校、第三章摘要及两个合法对照。内部服务重试由校准工具重放同一原始响应，不增加请求；这只检查协议消费，不代表独立重试成功率。请求关闭思考但中转可能仍返回reasoning，报告必须披露。

是否开启ENFORCE必须看真实漏检、误拦和协议可用性，不能仅因编译或单测通过。校准报告保留在独立测试工作目录，不向仓库写入API密钥或正式故事稿件。
