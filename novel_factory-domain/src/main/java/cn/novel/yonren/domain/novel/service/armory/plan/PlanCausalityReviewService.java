package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
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
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;

/** 段计划的窄范围因果预检；不写历史、不构成完成人物行动的证据。 */
@Service
@RequiredArgsConstructor
public class PlanCausalityReviewService {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String REVIEW_VERSION = "scene-evidence-v2";
    private static final Set<String> NON_EVIDENCE_FIELDS = Set.of("id", "sceneId", "chapterNo",
            "manuscriptHash", "knowledgeRefs", "milestoneRefs", "factId", "effectId", "referenceId",
            "characterId", "belief", "believes");
    private final LlmGateway gateway;
    private final StoryProperties properties;
    private static final int LEGACY_SUMMARY_LIMIT = 3;

    public PlanValidationResult check(StoryVO config, ChapterPlanAggregate plan, StageBlueprintEntity stage,
                                      List<ChapterSummaryEntity> history, int start, int end,
                                      GenerationJob job, Map<String, String> record) {
        // 与规划器传入的集合同源：确认人物事实 + 旧三账本世界前提，未解决冲突不进入。
        return check(config, plan, stage, history, start, end, job, record,
                CharacterFactReferences.from(history, start));
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
        List<FactReference> factList = facts == null ? List.of() : facts.stream().filter(Objects::nonNull).toList();
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
        Map<String, List<EvidenceField>> scenes;
        List<EvidenceField> factEvidence = new ArrayList<>();
        try {
            scenes = sceneEvidence(MAPPER.readTree(planText));
            if (scenes.isEmpty()) return unavailable(mode, "CAUSALITY_PLAN_INPUT");
            collectEvidence(MAPPER.readTree(factText), "facts", factEvidence);
            JsonNode stageNode = MAPPER.readTree(stageText);
            if (stageNode != null && stageNode.isObject()) {
                collectEvidence(stageNode.get("entryStates"), "stage.entryStates", factEvidence);
                collectEvidence(stageNode.get("constraints"), "stage.constraints", factEvidence);
            }
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
            return consume(cached, scenes, factEvidence, factByReference, mode);
        }
        String prompt = "你是计划因果核验员，不是创作者。只检查场景结果所依赖的必要前提，不给对白、冲突、代价或转折配额。"
                + "\n历史事实优先于计划；人物信念可以错误，要求不等于答应，打算不等于完成。"
                + "\nbelief/believes 与人物 BELIEF 记录不是世界事实，信念与真实情况不同本身不构成 ISSUE。"
                + "\n历史中的 pending 字段尚未证实；阶段的目标、任务与里程碑是规划，不是已完成事实。"
                + "\n核对人物已有知识是否被抹掉、设备能力是否无依据扩大、交易双方/支付/验证责任是否支持结果。"
                + "\n静场、独处、等待与闭合余韵均可PASS。缺证据用UNKNOWN，不编冲突。"
                + "\n每个sceneId恰好输出一条：{\"checks\":[{\"sceneId\":\"...\",\"verdict\":\"PASS|ISSUE|UNKNOWN\","
                + "\"planEvidence\":\"该场景单个计划字段的连续原文\",\"factId\":\"下方事实的factId\","
                + "\"sourceChapter\":章号,\"manuscriptHash\":\"该事实的manuscriptHash\","
                + "\"basisEvidence\":\"该事实的连续原文\",\"reason\":\"具体断裂环节或通过理由\","
                + "\"repair\":\"ISSUE时的定向修正目标\"}]}。"
                + "\nISSUE必须绑定下方事实：factId/sourceChapter/manuscriptHash/证据须同时匹配，且不能与planEvidence相同；"
                + "不得跨场、不得引用JSON键名、不得拿 belief 当世界事实，也不得用模型自己的推断替代事实。"
                + "\nISSUE的repair只写需要补齐或修正的实际缺口，不替作者设计人物突然醒悟、临时解锁或新对白。"
                + "\n【已提交人物与阶段事实】\n" + baseline + "\n【候选计划】\n" + planText;
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
        return consume(raw, scenes, factEvidence, factByReference, mode);
    }

    private PlanValidationResult consume(String raw, Map<String, List<EvidenceField>> expected,
                                         List<EvidenceField> factEvidence, Map<String, FactReference> factByReference,
                                         Mode mode) {
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
                if ("ISSUE".equals(verdict)) {
                    String left = text(check, "planEvidence"), right = basis(check);
                    String factId = text(check, "factId");
                    int sourceChapter = check.path("sourceChapter").asInt(0);
                    String manuscriptHash = text(check, "manuscriptHash");
                    String repair = text(check, "repair");
                    List<EvidenceField> fields = expected.get(scene);
                    List<EvidenceField> leftMatches = matches(fields, left);
                    FactReference fact = factByReference.get(factId);
                    boolean factBound = fact != null
                            && Objects.equals(fact.chapterNo(), sourceChapter)
                            && Objects.equals(fact.manuscriptHash(), manuscriptHash)
                            && StringUtils.isNotBlank(right) && fact.evidence().contains(right)
                            && !left.equals(right)
                            && !matches(factEvidence, right).isEmpty()
                            && CharacterFactReferences.usableAsBasis(fact, text(check, "failureType"));
                    // 同场两个不同字段自相矛盾：两侧原文都在计划里，无需历史事实，也不是模型推断。
                    boolean internal = StringUtils.isNotBlank(left) && StringUtils.isNotBlank(right)
                            && !left.equals(right) && !leftMatches.isEmpty()
                            && !matches(fields, right).isEmpty() && !sameField(fields, left, right);
                    if (leftMatches.isEmpty() || (!factBound && !internal) || StringUtils.isBlank(repair)) {
                        issues.add("CAUSALITY_PLAN_UNKNOWN: scene=" + scene + " 证据未绑定到该场与可核对事实，或修正目标缺失");
                    } else if (factBound) {
                        issues.add("causality scene=" + scene + " fact=" + factId
                                + "：「" + left + "」与已提交事实「" + right + "」不一致；"
                                + reason + "；需修正缺口：" + repair);
                    } else {
                        issues.add("causality scene=" + scene
                                + "：「" + left + "」与同场「" + right + "」不一致；"
                                + reason + "；需修正缺口：" + repair);
                    }
                } else if (!"PASS".equals(verdict)) {
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

    private static void addStageFacts(Map<String, FactReference> target, List<FactReference> facts) {
        if (facts == null) return;
        for (FactReference fact : facts) {
            if (fact == null || StringUtils.isBlank(fact.evidence()) || StringUtils.isBlank(fact.manuscriptHash())
                    || StringUtils.isBlank(fact.subject()) || fact.chapterNo() == null) continue;
            target.putIfAbsent(FactIdentity.of(fact.ledger(), fact.chapterNo(), fact.manuscriptHash(),
                    fact.subject(), fact.status(), fact.evidence()), fact);
        }
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
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            if (!node.textValue().isBlank()) fields.add(new EvidenceField(path, node.textValue()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collectEvidence(node.get(i), path + "[" + i + "]", fields);
        } else if (node.isObject() && !"BELIEF".equals(text(node, "kind"))) {
            node.fields().forEachRemaining(field -> {
                if (!NON_EVIDENCE_FIELDS.contains(field.getKey()) && !field.getKey().startsWith("pending")) {
                    collectEvidence(field.getValue(), path + "." + field.getKey(), fields);
                }
            });
        }
    }

    private static List<EvidenceField> matches(List<EvidenceField> fields, String quote) {
        return quote.isBlank() ? List.of() : fields.stream().filter(field -> field.value().contains(quote)).toList();
    }

    /** 同一字段内的两段文字不能构成“两个事实冲突”，只可能是模型自述矛盾。 */
    private static boolean sameField(List<EvidenceField> fields, String left, String right) {
        return fields.stream().anyMatch(field -> field.value().contains(left) && field.value().contains(right));
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

    private record EvidenceField(String path, String value) {
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
