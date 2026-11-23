package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.plan.PlanValidationResult;
import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.config.StoryProperties.CausalityProperties.Mode;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmLocalRejectionException;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.utils.FactIdentity;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;

/** 段计划的窄范围因果预检；不写历史、不构成完成人物行动的证据。 */
@Service
@RequiredArgsConstructor
public class PlanCausalityReviewService {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    // v3 的无证 PASS 不能为新准入授权；只升级 review 指纹，不重写计划/历史 JSON 或正文 hash。
    public static final String REVIEW_VERSION = "scene-evidence-v6-writing-hints";
    private static final List<String> WRITER_HINT_FIELDS = List.of("voiceAndTics", "tactic");
    private static final Set<String> NON_EVIDENCE_FIELDS = Set.of("id", "sceneId", "chapterNo",
            "manuscriptHash", "knowledgeRefs", "milestoneRefs", "factId", "effectId", "referenceId",
            "characterId", "belief", "believes", "profileRef", "characters", "character", "actor", "responder");
    private static final Set<String> CHARACTER_FAILURES = Set.of("KNOWLEDGE_REGRESSION", "SECRET_LEAK",
            "SECRECY_CONFLICT", "COMMITMENT_CONFLICT", "COMMITMENT_REGRESSION");
    private static final int FACT_REFERENCE_LIMIT = 12;
    private final LlmGateway gateway;
    private final StoryProperties properties;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IStoryRepository storyRepository;
    private static final int LEGACY_SUMMARY_LIMIT = 3;

    public PlanValidationResult check(StoryVO config, ChapterPlanAggregate plan, StageBlueprintEntity stage,
                                      List<ChapterSummaryEntity> history, int start, int end,
                                      GenerationJob job, Map<String, String> record) {
        // 与规划器传入的集合同源：确认人物事实 + 旧三账本世界前提，未解决冲突不进入。
        if (getMode() == Mode.OFF) return new PlanValidationResult(List.of(), List.of());
        return check(config, plan, stage, history, start, end, job, record,
                CharacterFactReferences.from(history, start));
    }

    /** 新正式入口：人物投影与规划 prompt 的正文核验引用合并，不以候选 entryStates 自证。 */
    public PlanValidationResult check(StoryVO config, ChapterPlanAggregate plan, StageBlueprintEntity stage,
                                      List<ChapterSummaryEntity> history, int start, int end,
                                      GenerationJob job, Map<String, String> record, Path storyDir) {
        if (getMode() == Mode.OFF) return new PlanValidationResult(List.of(), List.of());
        checkPlanningActive(job);
        Map<String, FactReference> selected = new LinkedHashMap<>();
        CharacterFactReferences.from(history, start).forEach(fact -> selected.put(CharacterFactReferences.identity(fact), fact));
        FactReferenceSource.select(history, start, storyRepository, storyDir, FACT_REFERENCE_LIMIT)
                .forEach(fact -> selected.put(CharacterFactReferences.identity(fact), fact));
        return check(config, plan, stage, history, start, end, job, record, List.copyOf(selected.values()));
    }

    public PlanValidationResult check(StoryVO config, ChapterPlanAggregate plan, StageBlueprintEntity stage,
                                      List<ChapterSummaryEntity> history, int start, int end,
                                      GenerationJob job, Map<String, String> record, List<FactReference> facts) {
        Mode mode = getMode();
        if (mode == Mode.OFF) return new PlanValidationResult(List.of(), List.of());
        checkPlanningActive(job);
        if (config == null || config.getModule() == null || start < 1 || end < start) {
            return unavailable(mode, "CAUSALITY_PLAN_INPUT");
        }
        String planText = JSON.toJSONString(plan);
        List<FactReference> factList = facts == null ? List.of() : facts.stream().filter(Objects::nonNull)
                .filter(fact -> fact.chapterNo() != null && fact.chapterNo() > 0 && fact.chapterNo() < start)
                .toList();
        Map<String, FactReference> factByIdentity = new LinkedHashMap<>();
        for (FactReference fact : factList) {
            factByIdentity.put(FactIdentity.of(fact.ledger(), fact.chapterNo(), fact.manuscriptHash(),
                    fact.subject(), fact.status(), fact.evidence()), fact);
        }
        // 阶段入口事实与持续护栏同样是已确认来源，可按同一身份核对；阶段目标/里程碑不是事实。
        // 阶段蓝图在当前架构里没有入场事实与约束字段：事实来源只有已提交人物与三账本引用，
        // 不从阶段目标或里程碑推造事实。
        // 模型可能回填身份哈希，也可能回填计划里已使用的 subject（character-fact-...）；
        // 两者都允许，但章号/hash/引文仍须逐项匹配，不能只凭字符串相等。
        Map<String, FactReference> factByReference = new LinkedHashMap<>(factByIdentity);
        for (Map.Entry<String, FactReference> entry : factByIdentity.entrySet()) {
            String subject = entry.getValue().subject();
            if (StringUtils.isNotBlank(subject)) factByReference.putIfAbsent(subject, entry.getValue());
        }
        // 模型必须看到 factId 才能原样回填；只给 subject 会让它自己编一个身份，核验随即失败。
        String factText = renderFacts(factByIdentity);
        String stageText = JSON.toJSONString(stage);
        // 历史文本变化（哪怕只是旧摘要、尚无结构化事实）也必须触发重核，不能复用旧结论。
        String historyText = JSON.toJSONString(history);
        String legacyText = renderLegacyNotes(history, start);
        String baseline = factText + legacyText + "\n历史摘要：" + ChapterManuscript.digest(historyText)
                + "\n当前阶段（规划上下文，不表示目标已完成）：" + stageText;
        JsonNode candidateForReview;
        Map<String, List<EvidenceField>> scenes;
        try {
            candidateForReview = withoutWriterHints(MAPPER.readTree(planText));
            scenes = sceneEvidence(candidateForReview);
            if (scenes.isEmpty()) return unavailable(mode, "CAUSALITY_PLAN_INPUT");
        } catch (Exception invalid) {
            return unavailable(mode, "CAUSALITY_PLAN_INPUT");
        }
        String key = "causality-plan:" + start + "-" + end + ":" + ChapterManuscript.digest(
                planText + "\n" + baseline + "\n" + factText);
        // 无运行身份或观测 map 时不缓存，避免匿名同步请求或恢复的 input 跨运行复用。
        boolean cacheable = record != null && job != null && job.getJobId() != null && !job.getJobId().isBlank();
        String fingerprint = ChapterManuscript.digest(REVIEW_VERSION + "\n" + key + "\n"
                + (job == null ? "" : job.getJobId()) + "\n" + JSON.toJSONString(config.getModule())
                + "\n" + String.join(",", factByIdentity.keySet()));
        String resultKey = key + ":" + fingerprint + ":result";
        checkPlanningActive(job);
        if (record != null) record.put(key + ":mode", mode.name());
        if (cacheable && record.containsKey(resultKey)) {
            String cached = record.get(resultKey);
            String digest = record.get(resultKey + ":hash");
            if (cached == null || digest == null || !digest.equals(ChapterManuscript.digest(cached))) {
                return unavailable(mode, "CAUSALITY_PLAN_CACHE_MISMATCH");
            }
            return recordResult(consume(cached, scenes, factByReference, mode), record, key);
        }
        String prompt = "你是计划因果核验员，不是创作者。只检查场景结果所依赖的必要前提，不给对白、冲突、代价或转折配额。"
                + "\nreviewVersion=" + REVIEW_VERSION + "；旧版无证PASS不授权，本调用每场必须定位原文。"
                + "\n历史事实优先于计划；人物信念可以错误，要求不等于答应，打算不等于完成。"
                + "\n候选计划的 entryStates/constraints 只是待核验引用，不是已提交事实。"
                + "缺少完整来源的引用（包括裸字符串兼容读取形成的描述）不得作为既成事实或已有能力的依据；六字段齐全也不等于来源核验通过。"
                + "\n只有当结果所必需的前提缺乏已提交事实或合理的场景内行动支撑时才用UNKNOWN；"
                + "不要仅因存在未核验引用就否定场景或增加证明配额。同场不同字段的内部矛盾仍可按现有规则判ISSUE。"
                + "\nbelief/believes 与人物 BELIEF 记录不是世界事实，信念与真实情况不同本身不构成 ISSUE。"
                + "角色的腔调与应对提示不参与核验；人物当场的借口只证明他这么说，不能证明其内容为真。"
                + "知识回退、保密与既有承诺核验不得用BELIEF冒充确知、泄密事实或真实承诺；缺少已确认依据用UNKNOWN。"
                + "\n保密策略来源：SECRECY_CONFLICT/SECRET_LEAK可核对同主体的DECISION/ESTABLISHED与INTENT/INTENDED；"
                + "它们只证明人物已作出保密决定或打算，并不证明秘密为真、人物确知或对他人作出承诺，不得强制改成KNOWLEDGE。"
                + "WITHDRAWN已撤回记录不作为有效保密约束；承诺语义仍只由COMMITMENT支撑，不用DECISION/INTENT/BELIEF代替。"
                + "保密决定不是永恒硬约束：先核对原文的对象、范围、条件例外和当前场景的变更依据；"
                + "明确的新处境、条件触发或重新决策可以合理改变策略，不因策略改变自动判ISSUE，也不强制增加变更对白或事件。"
                + "本场已说明合理变更时可PASS，并定位本场变更过程或允许概述证据；"
                + "只有仍有效的同主体策略与候选原文明确矛盾且无合理变更支撑时才用ISSUE。"
                + "真正缺少结果所依赖的策略变更前提时用UNKNOWN，不推定旧决定永久有效，也不凭推测制造泄密事实。"
                + "\n历史中的 pending 字段尚未证实；阶段的目标、任务与里程碑是规划，不是已完成事实。"
                + "同批次较后场的outcome也是候选未来结果，不得把它当成较早场已知知识或历史事实；"
                + "但同批次两场对同一主体/物件的明确必要条件与计划结果自相矛盾可双字段核对，不凭推测发ISSUE。"
                + "\n核对人物已有知识是否被抹掉、设备能力是否无依据扩大、交易双方/支付/验证责任是否支持结果。"
                + "\n核验对象：verificationObligations中的verifier/subject/item/criterion/resultUse/allowedAlternative；"
                + "谁核验谁或什么、通过什么标准、核验结论被拿来授权什么结果，不能把另一个人或物件的核验误当本对象通过。"
                + "只审查已声明且被结果依赖的必要核验；允许概述、替代核验与合理场内过程，不强制逐项加戏。"
                + "\n预设胜利审查：筹划中的预计对手反应、保密无风险或成功保证不等于已发生的反应/完成核验。"
                + "结果若依赖未确认的前提用UNKNOWN；只有两条明确原文确实矛盾才用ISSUE，不从作者未写出的条件推造冲突。"
                + "\n静场、独处、等待与闭合余韵均可PASS。缺证据用UNKNOWN，不编冲突。"
                + "PASS必须用planField/planEvidence定位本场行动过程或允许概述，不能只写无冲突。"
                + "步骤的action/response/because或本场intention可以是过程证据；"
                + "概述落在outcome/允许替代说明时必须evidenceRole=ALLOWED_SUMMARY并说明为何无需展开，不能用裸成功保证代替过程。"
                + "\n每个sceneId恰好输出一条，统一返回 {\"checks\":[判定条目]}。"
                + "PASS用PROCESS时，planField直接引用intention或steps的过程字段，不能把过程填在basisPlanField里。"
                + "PASS示例：{\"sceneId\":\"实际场景id\",\"verdict\":\"PASS\",\"planField\":\"scene.intention\","
                + "\"planEvidence\":\"intention中的连续行动原文\",\"evidenceRole\":\"PROCESS\",\"reason\":\"通过理由\"}。"
                + "\nISSUE示例：{\"sceneId\":\"实际场景id\",\"verdict\":\"ISSUE\","
                + "\"planField\":\"scene.outcome\",\"planEvidence\":\"该字段的连续原文\","
                + "\"evidenceRole\":\"PROCESS|ALLOWED_SUMMARY\",\"failureType\":\"具体冲突类型\",\"subject\":\"被核验主体原名\","
                + "\"factId\":\"下方事实factId（历史依据时）\",\"sourceChapter\":章号,\"manuscriptHash\":\"事实hash\","
                + "\"basisSceneId\":\"候选依据场id（计划依据时）\",\"basisPlanField\":\"scene.causality.steps[0].action\","
                + "\"basisEvidence\":\"依据单字段的连续原文\",\"reason\":\"具体断裂环节\","
                + "\"repair\":\"定向修正目标\"}。"
                + "\nplanField必须明确绑定sceneId内的左场字段；字段路径必须从scene开始，数组带准确下标。"
                + "ISSUE必须二选一绑定依据：历史factId/sourceChapter/manuscriptHash/连续原文同时匹配；"
                + "或basisSceneId/basisPlanField绑定合法候选场/字段，左右各为单字段连续原文且不能相同。"
                + "同场计划依据必须是不同字段；跨场必须显式声明两场，不能自动搜索另一场认领证据。"
                + "若声明了错误或混合依据，不得回退另一种来源替它授权。"
                + "不得引用JSON键名、身份/档案标签、未核验entryStates/constraints或belief当世界事实，不得用模型推断替代事实。"
                + "知识/保密/承诺核验必须回填subject并绑定到该主体，不能把甲知道/答应误配到乙。"
                + "\nISSUE的repair只写需要补齐或修正的实际缺口，不替作者设计人物突然醒悟、临时解锁或新对白。"
                + "\n【已提交人物与阶段事实】\n" + baseline + "\n【可定位计划字段】\n" + JSON.toJSONString(scenes)
                + "\n【候选计划】\n" + candidateForReview;
        if (record != null) {
            record.put(key + ":prompt", prompt);
            record.put(key + ":planHash", ChapterManuscript.digest(planText));
            record.put(key + ":stateHash", ChapterManuscript.digest(baseline));
        }
        checkPlanningActive(job);
        String raw;
        try {
            raw = gateway.complete(config.getModule(), LlmCall.builder().scene(ModelScene.CHAPTER_AUDIT)
                    .systemPrompt("只核验既有证据与候选场景，不补写历史，不要求人工裁决。")
                    .userPrompt(prompt).label("plan-causality-" + start + "-" + end).build());
        } catch (Exception failure) {
            checkPlanningActive(job);
            LlmLocalRejectionException.rethrowIfPresent(failure);
            return unavailable(mode, "CAUSALITY_PLAN_UNAVAILABLE");
        }
        // 取消/预算信号必须传播，不能被 OBSERVE 或模型失败降级吞掉。
        checkPlanningActive(job);
        if (cacheable) {
            String value = raw == null ? "" : raw;
            record.put(resultKey, value);
            record.put(resultKey + ":hash", ChapterManuscript.digest(value));
        }
        return recordResult(consume(raw, scenes, factByReference, mode), record, key);
    }

    private static PlanValidationResult recordResult(PlanValidationResult result, Map<String, String> record, String key) {
        if (record != null) {
            record.put(key + ":reviewVersion", REVIEW_VERSION);
            record.put(key + ":diagnostics", JSON.toJSONString(result));
        }
        return result;
    }

    private PlanValidationResult consume(String raw, Map<String, List<EvidenceField>> expected,
                                         Map<String, FactReference> factByReference, Mode mode) {
        try {
            // 只剥完整 Markdown 围栏；不能用 JSON repair 补齐截断的核验授权或吞掉重复键。
            JsonNode root = MAPPER.readTree(unwrap(raw));
            JsonNode checks = root == null ? null : root.get("checks");
            if (checks == null || !checks.isArray() || checks.size() != expected.size()) {
                return unavailable(mode, "CAUSALITY_PLAN_COVERAGE");
            }
            Set<String> seen = new HashSet<>();
            List<String> issues = new ArrayList<>();
            for (JsonNode check : checks) {
                String scene = text(check, "sceneId"), verdict = text(check, "verdict");
                String reason = text(check, "reason");
                if (!expected.containsKey(scene) || !seen.add(scene) || reason.isBlank()) {
                    issues.add("CAUSALITY_PLAN_UNKNOWN: 场景身份重复、误配或缺少具体理由");
                    continue;
                }
                String left = text(check, "planEvidence");
                EvidenceField leftField = boundField(expected.get(scene), text(check, "planField"), left);
                if ("PASS".equals(verdict)) {
                    if (leftField == null || !passEvidence(leftField, text(check, "evidenceRole"))) {
                        issues.add("CAUSALITY_PLAN_UNKNOWN: scene=" + scene + " PASS缺少可定位本场过程或允许概述证据");
                    }
                } else if ("ISSUE".equals(verdict)) {
                    String right = basis(check), repair = text(check, "repair");
                    String factId = text(check, "factId"), basisScene = text(check, "basisSceneId");
                    String basisField = text(check, "basisPlanField");
                    // 显式声明任何计划侧绑定即走计划来源，不能误配后自动回退历史授权。
                    boolean planBasis = declared(check, "basisSceneId") || declared(check, "basisPlanField");
                    boolean historicalBasis = declared(check, "factId") || declared(check, "sourceChapter")
                            || declared(check, "manuscriptHash");
                    FactReference fact = planBasis ? null : factByReference.get(factId);
                    EvidenceField rightField = planBasis
                            ? boundField(expected.getOrDefault(basisScene, List.of()), basisField, right) : null;
                    boolean bound = leftField != null && !left.equals(right) && !repair.isBlank()
                            && !text(check, "failureType").isBlank();
                    if (planBasis) {
                        bound = bound && !historicalBasis && rightField != null
                                && (!scene.equals(basisScene) || !leftField.path().equals(rightField.path()))
                                && subjectsBound(check, leftField, rightField, null);
                    } else {
                        bound = bound && fact != null && Objects.equals(fact.chapterNo(), check.path("sourceChapter").asInt(0))
                                && Objects.equals(fact.manuscriptHash(), text(check, "manuscriptHash"))
                                && StringUtils.isNotBlank(right) && StringUtils.isNotBlank(fact.evidence())
                                && fact.evidence().contains(right)
                                && usableFact(fact, text(check, "failureType"))
                                && subjectsBound(check, leftField, null, fact);
                    }
                    if (!bound) {
                        issues.add("CAUSALITY_PLAN_UNKNOWN: scene=" + scene
                                + " 证据未绑定到合法场景/字段与同一核验主体，或事实来源/修正目标缺失");
                    } else {
                        String source = planBasis ? (scene.equals(basisScene) ? "同场" : "跨场 scene=" + basisScene)
                                + " field=" + basisField : "已提交事实 fact=" + factId;
                        issues.add("causality scene=" + scene + " field=" + leftField.path()
                                + "：「" + left + "」与" + source + "「" + right + "」不一致；"
                                + reason + "；需修正缺口：" + repair);
                    }
                } else {
                    issues.add("CAUSALITY_PLAN_UNKNOWN: scene=" + scene + "：" + reason);
                }
            }
            if (seen.size() != expected.size()) issues.add("CAUSALITY_PLAN_UNKNOWN: 场景核验覆盖不完整");
            return mode == Mode.ENFORCE ? new PlanValidationResult(issues, List.of())
                    : new PlanValidationResult(List.of(), issues.stream().map(text -> "OBSERVE " + text).toList());
        } catch (Exception invalid) {
            return unavailable(mode, "CAUSALITY_PLAN_PARSE");
        }
    }

    /** null/空选填不声明来源；非空错误类型仍是显式声明，禁止失败后切换来源授权。 */
    private static boolean declared(JsonNode check, String field) {
        JsonNode value = check == null ? null : check.get(field);
        return value != null && !value.isNull() && (!value.isTextual() || !value.textValue().isBlank());
    }

    private static EvidenceField boundField(List<EvidenceField> fields, String path, String quote) {
        if (path.isBlank() || quote.isBlank()) return null;
        return fields.stream().filter(field -> field.path().equals(path) && field.value().contains(quote))
                .findFirst().orElse(null);
    }

    /** PASS必须有现场过程；概述显式标注，不能把身份/地点/世界前提或预期胜利认作过程。 */
    private static boolean passEvidence(EvidenceField field, String role) {
        boolean process = field.path().equals("scene.intention")
                || field.path().matches("scene\\.causality\\.steps\\[\\d+](\\.action|\\.response|\\.because)");
        if ("PROCESS".equals(role)) return process;
        return "ALLOWED_SUMMARY".equals(role) && (process || field.path().equals("scene.outcome")
                || field.path().matches("scene\\.causality\\.verificationObligations\\[\\d+]\\.allowedAlternative"));
    }

    private static boolean usableFact(FactReference fact, String failureType) {
        String status = StringUtils.defaultString(fact.status());
        // BELIEF只证明曾经相信，不足以授权知识、保密、承诺或世界事实冲突。
        if (status.startsWith("BELIEF/")) return false;
        if (CHARACTER_FAILURES.contains(failureType)) {
            if (!"CHARACTER".equalsIgnoreCase(fact.ledger())) return false;
            String kindState = status.split("\\|", 2)[0];
            if (kindState.endsWith("/WITHDRAWN")) return false;
            if (failureType.startsWith("COMMITMENT")) return status.startsWith("COMMITMENT/");
            if ("SECRECY_CONFLICT".equals(failureType) || "SECRET_LEAK".equals(failureType)) {
                // 决策/意图只授权同主体保密策略核对，不转换成确知或对外承诺。
                if ("DECISION/ESTABLISHED".equals(kindState) || "INTENT/INTENDED".equals(kindState)) return true;
            }
            return status.startsWith("KNOWLEDGE/") || status.startsWith("COMMITMENT/");
        }
        return CharacterFactReferences.usableAsBasis(fact, failureType);
    }

    /** 结构化actor/step或连续引文绑定人物；不能只因另一人也在characters列表就挪用认知。 */
    private static boolean subjectsBound(JsonNode check, EvidenceField left, EvidenceField right, FactReference fact) {
        String subject = text(check, "subject");
        boolean required = CHARACTER_FAILURES.contains(text(check, "failureType"));
        String[] parts = fact == null ? new String[0] : StringUtils.defaultString(fact.status()).split("\\|", -1);
        String basisSubject = right != null ? right.subject() : (parts.length > 1 ? parts[1] : "");
        // 结构化两侧已经声明了主体时，不允许用通用failureType绕开主体误配核验。
        if (left != null && !left.subject().isBlank() && !basisSubject.isBlank()
                && !left.subject().equals(basisSubject)) return false;
        if (subject.isBlank()) return !required;
        if (!fieldSubject(left, subject, text(check, "planEvidence"))) return false;
        if (right != null) return fieldSubject(right, subject, basis(check));
        return subject.equals(basisSubject);
    }

    private static boolean fieldSubject(EvidenceField field, String subject, String quote) {
        if (field == null) return false;
        return field.subject().isBlank() ? quote.contains(subject) : field.subject().equals(subject);
    }

    /** 事实清单必须带 factId 与回填字段；缺字段的引用不交给模型，避免伪造身份。 */
    private static String renderFacts(Map<String, FactReference> facts) {
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (Map.Entry<String, FactReference> entry : facts.entrySet()) {
            FactReference fact = entry.getValue();
            if (StringUtils.isBlank(fact.evidence()) || StringUtils.isBlank(fact.manuscriptHash())
                    || fact.chapterNo() == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("factId", entry.getKey());
            item.put("ledger", fact.ledger());
            item.put("chapterNo", fact.chapterNo());
            item.put("manuscriptHash", fact.manuscriptHash());
            item.put("subject", fact.subject());
            item.put("status", fact.status());
            item.put("evidence", fact.evidence());
            rendered.add(item);
        }
        return JSON.toJSONString(rendered);
    }

    /** 旧摘要只作参考，沿用近章窗口；可核验结论必须绑定上方已入选事实。 */
    private static String renderLegacyNotes(List<ChapterSummaryEntity> history, int beforeChapter) {
        if (history == null || history.isEmpty()) return "";
        List<Map<String, Object>> notes = new ArrayList<>();
        history.stream().filter(Objects::nonNull).filter(summary -> summary.getChapterNo() != null
                        && summary.getChapterNo() > 0 && summary.getChapterNo() < beforeChapter)
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo).reversed())
                .limit(LEGACY_SUMMARY_LIMIT)
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo))
                .forEach(summary -> {
                    if (StringUtils.isBlank(summary.getSummary())) return;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chapterNo", summary.getChapterNo());
                    item.put("summary", summary.getSummary());
                    notes.add(item);
                });
        return notes.isEmpty() ? "" : "\n旧摘要（无 factId，仅供理解；不得据此提交 ISSUE）："
                + JSON.toJSONString(notes);
    }

    /** 兼容旧测试/旧调用：模型仍可能输出 historyEvidence 字段名。 */
    private static String basis(JsonNode check) {
        String value = text(check, "basisEvidence");
        return value.isBlank() ? text(check, "historyEvidence") : value;
    }

    /** 只清理送审的 JSON 副本。采纳后的计划与写手仍使用原始提示。 */
    private static JsonNode withoutWriterHints(JsonNode plan) {
        for (JsonNode chapter : plan.path("chapters")) {
            for (JsonNode scene : chapter.path("scenes")) {
                for (JsonNode actor : scene.path("causality").path("actors")) {
                    if (actor instanceof ObjectNode actorObject) {
                        actorObject.remove(WRITER_HINT_FIELDS);
                    }
                }
            }
        }
        return plan;
    }

    private static Map<String, List<EvidenceField>> sceneEvidence(JsonNode plan) {
        Map<String, List<EvidenceField>> result = new LinkedHashMap<>();
        JsonNode chapters = plan == null ? null : plan.get("chapters");
        if (chapters == null || !chapters.isArray() || chapters.isEmpty()) return Map.of();
        Set<String> ids = new HashSet<>();
        for (JsonNode chapter : chapters) {
            JsonNode scenes = chapter.get("scenes");
            if (scenes == null || !scenes.isArray() || scenes.isEmpty()) return Map.of();
            for (JsonNode scene : scenes) {
                String id = text(scene, "id");
                if (id.isBlank() || !ids.add(id.trim())) return Map.of();
                List<EvidenceField> fields = new ArrayList<>();
                collectEvidence(scene, "scene", fields);
                result.put(id, fields);
            }
        }
        return result;
    }

    private static void collectEvidence(JsonNode node, String path, List<EvidenceField> fields) {
        collectEvidence(node, path, fields, "");
    }

    private static void collectEvidence(JsonNode node, String path, List<EvidenceField> fields, String subject) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            if (!node.textValue().isBlank()) fields.add(new EvidenceField(path, node.textValue(), subject));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collectEvidence(node.get(i), path + "[" + i + "]", fields, subject);
        } else if (node.isObject() && !"BELIEF".equals(text(node, "kind"))) {
            String actor = text(node, "character");
            if (path.matches("scene\\.causality\\.steps\\[\\d+]")) actor = text(node, "actor");
            if (path.matches("scene\\.causality\\.verificationObligations\\[\\d+]")) actor = text(node, "subject");
            String owner = actor.isBlank() ? subject : actor;
            node.fields().forEachRemaining(field -> {
                if (!NON_EVIDENCE_FIELDS.contains(field.getKey()) && !field.getKey().startsWith("pending")
                        // 候选引用是待核验输入，不能拿其描述/status/evidence充当已成立计划依据。
                        && !field.getKey().equals("entryStates") && !field.getKey().equals("constraints")) {
                    String fieldOwner = field.getKey().equals("response") && StringUtils.isNotBlank(text(node, "responder"))
                            ? text(node, "responder") : owner;
                    collectEvidence(field.getValue(), path + "." + field.getKey(), fields, fieldOwner);
                }
            });
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static String unwrap(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("```") && value.endsWith("```")) {
            int line = value.indexOf('\n');
            if (line >= 0) {
                String header = value.substring(0, line).trim();
                if (header.equals("```") || header.equalsIgnoreCase("```json")) {
                    return value.substring(line + 1, value.length() - 3).trim();
                }
            }
        }
        return value;
    }

    private record EvidenceField(String path, String value, String subject) {
    }

    private PlanValidationResult unavailable(Mode mode, String reason) {
        return mode == Mode.ENFORCE ? new PlanValidationResult(List.of(reason), List.of())
                : new PlanValidationResult(List.of(), List.of("OBSERVE " + reason));
    }

    public Mode getMode() {
        var cfg = properties == null ? null : properties.getCausality();
        return cfg == null || cfg.getPlanMode() == null ? Mode.OFF : cfg.getPlanMode();
    }

    /**
     * 规划期取消/预算信号必须传播：核验是一次模型调用，可能持续数十秒，
     * 期间用户点取消或预算熔断都不该被 OBSERVE 或失败降级吞掉。
     */
    private static void checkPlanningActive(GenerationJob job) {
        if (job == null) {
            return;
        }
        if (job.isCancelRequested()) {
            throw new cn.novel.yonren.types.exception.AppException(
                    cn.novel.yonren.types.enums.ResponseCode.UN_ERROR.getCode(), "计划因果核验已被取消");
        }
        if (job.isBudgetExceeded()) {
            throw new cn.novel.yonren.types.exception.AppException(
                    cn.novel.yonren.types.enums.ResponseCode.UN_ERROR.getCode(), "计划因果核验因预算熔断终止");
        }
    }
}
