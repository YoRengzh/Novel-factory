package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.config.StoryProperties;
import cn.novel.yonren.domain.novel.config.StoryProperties.CausalityProperties.Mode;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.CausalVerdict;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import cn.novel.yonren.domain.novel.model.valobj.quality.DeliveryReview;
import cn.novel.yonren.domain.novel.model.valobj.work.ChapterManuscript;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 只验证交付协议、证据定位与版本身份；过程是否成立由 LLM 判断，不用词面覆盖率代替。 */
public final class DeliveryReviewPolicy {
    public static final String DELIVERY_DIMENSION = "plan-delivery";

    private DeliveryReviewPolicy() {
    }

    public record Target(String id, String sceneId, Map<String, String> fields) {
        public Target {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    /** 从权威计划冻结的检查对象；targets 不由模型增删，逐步骤索引只在该计划 hash 内有效。 */
    public record Contract(String planHash, DeliveryReview.Scope scope, Map<String, Target> targets,
                           List<String> diagnostics) {
        public Contract {
            targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record Request(Contract contract, ChapterManuscript manuscript, boolean enforced,
                          Runnable cancellationCheck) {
        public Request(Contract contract, ChapterManuscript manuscript, boolean enforced) {
            this(contract, manuscript, enforced, null);
        }

        public void checkCancellation() {
            if (cancellationCheck != null) cancellationCheck.run();
        }
    }

    public static Mode mode(StoryProperties properties) {
        if (properties == null || properties.getCausality() == null
                || properties.getCausality().getAuditMode() == null) return Mode.OFF;
        return properties.getCausality().getAuditMode();
    }

    public static void assertConfiguration(StoryProperties properties) {
        if (mode(properties) == Mode.ENFORCE
                && (properties.getAudit() == null || !properties.getAudit().isEnabled())) {
            throw new IllegalArgumentException("story.causality.audit-mode=ENFORCE 要求 story.audit.enabled=true，"
                    + "不能以关闭审校替代交付确认");
        }
    }

    public static Contract contract(ChapterPlanItemEntity item) {
        Map<String, Target> targets = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        boolean scenes = item != null && Integer.valueOf(2).equals(item.getSchemaVersion());
        if (scenes) {
            if (item.getScenes() != null) {
                for (ScenePlan scene : item.getScenes()) addScene(targets, diagnostics, scene);
            }
        } else if (item != null && item.getKeyEvents() != null) {
            for (int i = 0; i < item.getKeyEvents().size(); i++) {
                String event = item.getKeyEvents().get(i);
                if (StringUtils.isBlank(event)) continue;
                String id = "event:" + (i + 1);
                addTarget(targets, diagnostics, new Target(id, null, Map.of("keyEvents[" + i + "]", event)));
            }
        }
        if (targets.isEmpty()) diagnostics.add("DELIVERY_PLAN_EMPTY: 没有可核验的权威场景或关键事件");
        return new Contract(ChapterManuscript.digest(JSON.toJSONString(item)),
                scenes ? DeliveryReview.Scope.SCENES : DeliveryReview.Scope.EVENTS, targets, diagnostics);
    }

    private static void addScene(Map<String, Target> targets, List<String> diagnostics, ScenePlan scene) {
        if (scene == null || StringUtils.isBlank(scene.id())) {
            diagnostics.add("DELIVERY_PLAN_INVALID: 场景为空或缺少 sceneId");
            return;
        }
        String sceneId = scene.id().trim();
        String id = "scene:" + sceneId;
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "intention", scene.intention());
        put(fields, "resistance", scene.resistance());
        put(fields, "outcome", scene.outcome());
        put(fields, "function", scene.function());
        if (StringUtils.isBlank(scene.outcome()) && StringUtils.isBlank(scene.function())) {
            diagnostics.add("DELIVERY_PLAN_INVALID: " + id + " 未声明 outcome/function");
        }
        addTarget(targets, diagnostics, new Target(id, sceneId, fields));
        if (scene.causality() == null || scene.causality().steps() == null) return;
        List<SceneCausality.CausalStep> steps = scene.causality().steps();
        for (int i = 0; i < steps.size(); i++) {
            SceneCausality.CausalStep step = steps.get(i);
            if (step == null || StringUtils.isBlank(step.action())) {
                diagnostics.add("DELIVERY_PLAN_INVALID: " + id + " 的第 " + (i + 1) + " 步缺少 action");
                continue;
            }
            String prefix = "causality.steps[" + i + "].";
            Map<String, String> stepFields = new LinkedHashMap<>();
            put(stepFields, prefix + "actor", step.actor());
            put(stepFields, prefix + "action", step.action());
            put(stepFields, prefix + "responder", step.responder());
            put(stepFields, prefix + "response", step.response());
            put(stepFields, prefix + "because", step.because());
            addTarget(targets, diagnostics, new Target(id + "/step:" + (i + 1), sceneId, stepFields));
        }
    }

    private static void put(Map<String, String> fields, String key, String value) {
        if (StringUtils.isNotBlank(value)) fields.put(key, value);
    }

    private static void addTarget(Map<String, Target> targets, List<String> diagnostics, Target target) {
        if (targets.putIfAbsent(target.id(), target) != null) {
            diagnostics.add("DELIVERY_PLAN_DUPLICATE: 验收对象身份重复 " + target.id());
        }
    }

    public static String prompt(Request request) {
        StringBuilder sb = new StringBuilder("\n\n【计划交付逐项验收】这是一份固定清单，不是文风评分。")
                .append("每个 targetId 恰好返回一条 deliveryChecks；不能因机械词面命中、issues=[] 或 fixed=true 省略核验。")
                .append("\n核验范围：").append(request.contract().scope())
                .append("。场景级对象核对最终 outcome/function；step 对象核对已声明的决定性行动及必要回应、依据。")
                .append("\n意图不等于成功，阻力不等于必须克服；预期失败、静场、等待、心理、讲述与余韵都可交付。")
                .append("不新增对白、冲突、代价、转折、字数或动作展示配额，不要求逐句照抄计划。")
                .append("\nPASS：正文已有可理解的必要过程与结果，或计划明确允许结果级概述且已给出具体落点。")
                .append("表达简短不算缺失；概述许可只适用于对应任务，不因过渡章标签豁免其它已声明行动。")
                .append("\nISSUE/RESULT_WITHOUT_CAUSE：正文只宣布结果，计划承诺的必要行动或因果环节未交付；引用宣布句或跳接位置。")
                .append("\nISSUE/MISSING_DELIVERY：核对全文后，计划明确要求的事件/场景完全缺席；planEvidence 必须引用该义务，")
                .append("reason 写明缺失内容和全文核对结论，evidence 可为空，不能捏造不存在的正文。")
                .append("\nUNKNOWN：计划含糊、证据不足、无法判断。不能将拿不准改成 PASS 或程度性 MINOR。")
                .append("\n每条填写 targetId、sceneId（旧事件为 null）、verdict、planField、planEvidence、evidence、reason、")
                .append("repair、failureType；planField 只选该对象下的一个交付字段：场景选 outcome/function，")
                .append("步骤选 action/response/because，旧事件选 keyEvents[i]；planEvidence 是该字段的连续原文。")
                .append("PASS 的 evidence 必须摘自当前正文，reason 说明这些文字如何支撑必要过程/允许概述的落点。")
                .append("ISSUE 必须有具体缺口和定向 repair，不另编剧情；非 ISSUE 的 failureType 必须为 null。")
                .append("引文存在本身不证明因果成立，不能拿名词/结果短语的重合代替语义判断。")
                .append("factId/sourceChapter/manuscriptHash/factType/basisEvidence 留空，稿件身份由程序绑定。")
                .append("\n【固定验收对象与可引用计划字段】\n");
        request.contract().targets().values().forEach(target -> sb.append(JSON.toJSONString(target)).append('\n'));
        if (!request.contract().diagnostics().isEmpty()) {
            sb.append("计划结构异常，不能宣称通过：").append(String.join("；", request.contract().diagnostics()));
        }
        return sb.toString();
    }

    public static DeliveryReview consume(Request request, List<CausalVerdict> checks) {
        List<String> diagnostics = new ArrayList<>(request.contract().diagnostics());
        Map<String, CausalVerdict> accepted = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        String normalized = EvidenceMatch.normalize(request.manuscript().content());
        if (checks == null) {
            diagnostics.add("DELIVERY_COVERAGE: 缺少 deliveryChecks");
        } else {
            for (CausalVerdict check : checks) {
                Target target = check == null ? null : request.contract().targets().get(check.targetId());
                if (target == null || !seen.add(check.targetId())) {
                    diagnostics.add("DELIVERY_COVERAGE: 空条目、未知或重复 targetId="
                            + (check == null ? "null" : check.targetId()));
                    continue;
                }
                String invalid = invalidCheck(check, target, normalized);
                if (invalid != null) {
                    diagnostics.add(invalid + " [" + target.id() + "]");
                    accepted.put(target.id(), unknownCheck(target, check.reason()));
                } else {
                    accepted.put(target.id(), boundCheck(check, request.manuscript()));
                    if (check.verdict() == CausalVerdict.Status.UNKNOWN) {
                        diagnostics.add("DELIVERY_UNKNOWN: " + target.id() + "：" + check.reason());
                    }
                }
            }
        }
        for (Target target : request.contract().targets().values()) {
            if (!accepted.containsKey(target.id())) {
                diagnostics.add("DELIVERY_COVERAGE: 未核验 " + target.id());
                accepted.put(target.id(), unknownCheck(target, "模型未返回该对象的有效交付结论"));
            }
        }
        List<CausalVerdict> ordered = request.contract().targets().keySet().stream().map(accepted::get).toList();
        DeliveryReview.Status status = !diagnostics.isEmpty() ? DeliveryReview.Status.UNKNOWN
                : ordered.stream().anyMatch(check -> check.verdict() == CausalVerdict.Status.ISSUE)
                ? DeliveryReview.Status.ISSUE : DeliveryReview.Status.PASS;
        return new DeliveryReview(status, request.enforced(), request.contract().scope(),
                request.manuscript().hash(), request.contract().planHash(), ordered, diagnostics);
    }

    private static String invalidCheck(CausalVerdict check, Target target, String normalizedContent) {
        if (!Objects.equals(StringUtils.trimToNull(check.sceneId()), target.sceneId())
                || check.verdict() == null || StringUtils.isBlank(check.reason())) {
            return "DELIVERY_INVALID: 场景身份、状态或判断理由缺失";
        }
        if (check.verdict() == CausalVerdict.Status.UNKNOWN) return null;
        String field = target.fields().get(check.planField());
        boolean obligationField = check.planField() != null && (check.planField().startsWith("keyEvents[")
                || check.planField().equals("outcome") || check.planField().equals("function")
                || check.planField().endsWith(".action") || check.planField().endsWith(".response")
                || check.planField().endsWith(".because"));
        // 计划证据必须锚定该对象的交付义务，人物名或“有此意图”不是完成证明。
        if (!obligationField || field == null || StringUtils.isBlank(check.planEvidence())
                || !field.contains(check.planEvidence())
                || !EvidenceMatch.contained(check.planEvidence(), EvidenceMatch.normalize(field))) {
            return "DELIVERY_PLAN_EVIDENCE: 计划引文未绑定到该对象字段";
        }
        if (check.verdict() == CausalVerdict.Status.PASS) {
            if (check.failureType() != null) return "DELIVERY_INVALID: PASS 不能同时声称存在缺口";
            if (!EvidenceMatch.contained(check.evidence(), normalizedContent)) {
                return "DELIVERY_CONTENT_EVIDENCE: PASS 缺少可定位的当前正文依据";
            }
        } else {
            if (StringUtils.isBlank(check.repair()) || (check.failureType() != CausalVerdict.FailureType.RESULT_WITHOUT_CAUSE
                    && check.failureType() != CausalVerdict.FailureType.MISSING_DELIVERY)) {
                return "DELIVERY_INVALID: ISSUE 缺少交付缺口类型或定向修复目标";
            }
            if ((check.failureType() != CausalVerdict.FailureType.MISSING_DELIVERY
                    || StringUtils.isNotBlank(check.evidence()))
                    && !EvidenceMatch.contained(check.evidence(), normalizedContent)) {
                return "DELIVERY_CONTENT_EVIDENCE: 宣布句/跳接位置无法在当前稿定位";
            }
        }
        return null;
    }

    private static CausalVerdict boundCheck(CausalVerdict check, ChapterManuscript manuscript) {
        // 丢弃模型自报的历史身份；该报告只证明当前稿对当前计划的交付，不授予事实入账权限。
        return new CausalVerdict(StringUtils.trimToNull(check.sceneId()), check.verdict(), check.evidence(), null,
                check.reason(), check.repair(), check.failureType(), null, manuscript.chapterNo(), manuscript.hash(),
                null, check.targetId(), check.planField(), check.planEvidence());
    }

    private static CausalVerdict unknownCheck(Target target, String reason) {
        return new CausalVerdict(target.sceneId(), CausalVerdict.Status.UNKNOWN, null, null,
                StringUtils.defaultIfBlank(reason, "交付结论不可用"), null, null,
                null, null, null, null, target.id(), null, null);
    }

    public static DeliveryReview unavailable(Request request, String reason) {
        List<CausalVerdict> checks = request.contract().targets().values().stream()
                .map(target -> unknownCheck(target, reason)).toList();
        return new DeliveryReview(DeliveryReview.Status.UNKNOWN, request.enforced(), request.contract().scope(),
                request.manuscript().hash(), request.contract().planHash(), checks,
                List.of(StringUtils.defaultIfBlank(reason, "DELIVERY_UNAVAILABLE")));
    }

    /** 消费边界再次核对身份；旧稿/另一份计划的 PASS 不能被候选或提交路径复用。 */
    public static DeliveryReview ensureBound(DeliveryReview review, ChapterPlanItemEntity item,
                                            ChapterManuscript manuscript, boolean enforced) {
        Contract contract = contract(item);
        Request request = new Request(contract, manuscript, enforced);
        if (review == null || review.status() == DeliveryReview.Status.NOT_CHECKED) {
            return unavailable(request, "DELIVERY_NOT_CHECKED: 当前稿缺少交付核验");
        }
        if (!Objects.equals(review.manuscriptHash(), manuscript.hash())
                || !Objects.equals(review.planHash(), contract.planHash()) || review.scope() != contract.scope()) {
            return unavailable(request, "DELIVERY_VERSION_MISMATCH: 交付结论与当前稿或计划不一致");
        }
        if (review.unknown()) {
            return new DeliveryReview(review.status(), enforced, review.scope(), review.manuscriptHash(),
                    review.planHash(), review.checks(), review.diagnostics());
        }
        return consume(request, review.checks());
    }

    /** 新交付问题独立于旧 plan-integrity 候选，不走“证据为空就降 MINOR”的旧过滤。 */
    public static List<ChapterIssueEntity> blockingIssues(DeliveryReview review) {
        if (review == null || !review.enforced() || review.unknown()) return List.of();
        return review.checks().stream().filter(check -> check.verdict() == CausalVerdict.Status.ISSUE)
                .map(check -> ChapterIssueEntity.builder().dimension(DELIVERY_DIMENSION).severity("BLOCKING")
                        .evidence(check.evidence())
                        .description("计划交付未完成 [" + check.targetId() + "] " + check.failureType()
                                + "；计划 " + check.planField() + "：「" + check.planEvidence() + "」；" + check.reason())
                        .suggestion(check.repair()).build()).toList();
    }

    public static boolean isDeliveryIssue(ChapterIssueEntity issue) {
        return issue != null && DELIVERY_DIMENSION.equals(issue.getDimension());
    }
}
