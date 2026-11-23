package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 场景是 v2 唯一权威；本策略不把旧事件转换成场景，也不证明里程碑完成。 */
public final class ScenePlanPolicy {
    private ScenePlanPolicy() {
    }

    /** 仅刷新 v2 兼容视图，不重建场景，保留可选 causality、ID、事实和结果。 */
    public static void normalize(ChapterPlanItemEntity item) {
        if (item == null || !Integer.valueOf(2).equals(item.getSchemaVersion())) {
            return;
        }
        Set<String> characters = new LinkedHashSet<>();
        List<String> events = new ArrayList<>();
        if (item.getScenes() != null) {
            for (ScenePlan scene : item.getScenes()) {
                if (scene == null) {
                    continue;
                }
                for (String character : scene.characters()) {
                    if (StringUtils.isNotBlank(character)) {
                        characters.add(character);
                    }
                }
                StringBuilder event = new StringBuilder();
                event.append(String.join("、", scene.characters().stream()
                        .filter(StringUtils::isNotBlank).toList()));
                event.append("意图：").append(text(scene.intention()));
                if (StringUtils.isNotBlank(scene.resistance())) {
                    event.append("；阻力：").append(scene.resistance());
                }
                if (StringUtils.isNotBlank(scene.outcome())) {
                    event.append("；结果：").append(scene.outcome());
                }
                if (StringUtils.isNotBlank(scene.function())) {
                    event.append("；场景功能：").append(scene.function());
                }
                events.add(event.append("。").toString());
            }
        }
        item.setCharacters(new ArrayList<>(characters));
        item.setKeyEvents(events);
        // ScenePlan 没有对白事实字段；不可凭空推断对白或设置配额。
        item.setDialogueScenes(List.of());
    }

    /** 验证当前计划内 ID 唯一；跨章重复 ID 由批次审批/执行入口结合其他计划校验。 */
    public static List<String> violations(ChapterPlanItemEntity item) {
        List<String> violations = new ArrayList<>();
        if (item == null || !Integer.valueOf(2).equals(item.getSchemaVersion())) {
            violations.add("场景契约版本(schemaVersion=2)缺失或不支持；legacy 仅可读取");
        }
        if (item == null || item.getScenes() == null || item.getScenes().isEmpty()) {
            violations.add("权威场景(scenes)必须非空");
            return violations;
        }
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (ScenePlan scene : item.getScenes()) {
            String at = "scenes[" + index++ + "]";
            if (scene == null) {
                violations.add(at + "场景不能为空");
                continue;
            }
            if (StringUtils.isBlank(scene.id())) {
                violations.add(at + ".id 必须非空");
            } else if (!ids.add(scene.id().trim())) {
                violations.add(at + ".id 重复：" + scene.id());
            }
            if (scene.characters().isEmpty() || scene.characters().stream().anyMatch(StringUtils::isBlank)) {
                violations.add(at + ".characters 必须包含有效人物");
            }
            if (StringUtils.isBlank(scene.intention())) {
                violations.add(at + ".intention 人物意图必须非空");
            }
            if (StringUtils.isBlank(scene.outcome()) && StringUtils.isBlank(scene.function())) {
                violations.add(at + ".outcome 或 function 必须非空");
            }
            if (StringUtils.isBlank(scene.resistance()) && !quietFunction(scene.function())) {
                violations.add(at + ".resistance 推进场景需要阻力或 uncertainty（过渡/消化/余韵可无外部冲突）");
            }
            if (scene.entryStates().stream().anyMatch(fact -> fact == null)) {
                violations.add(at + ".entryStates 不能含空引用");
            }
            if (scene.milestoneRefs().stream().anyMatch(StringUtils::isBlank)) {
                violations.add(at + ".milestoneRefs 不能含空引用");
            }
            for (String violation : SceneCausalityPolicy.violations(scene)) {
                violations.add(at + "." + violation);
            }
        }
        return violations;
    }

    private static boolean quietFunction(String function) {
        String value = text(function).trim().toLowerCase(Locale.ROOT);
        // 功能词可带自由说明，不根据整章章型或任意意图中的“未知”豁免推进场景。
        // 这是结构检查，不声称机械证明意图、阻力或结果在语义上已经充分。
        if (List.of("过渡", "消化", "余韵", "静场").stream().anyMatch(value::startsWith)) {
            return true;
        }
        return List.of("transition", "digestion", "afterglow", "quiet").stream()
                .anyMatch(type -> value.equals(type) || value.startsWith(type + ":")
                        || value.startsWith(type + "：") || value.startsWith(type + " "));
    }

    /** 无核验上下文时仍提供场景内容，略过不能证实的事实引用。 */
    public static String renderForWriting(ChapterPlanItemEntity item) {
        return renderForWriting(item, Set.of());
    }

    /** 写作只需场景经过、落点与核实后的前情。旧版无结构化场景时由调用方使用关键事件。 */
    public static String renderForWriting(ChapterPlanItemEntity item, Set<FactReference> verifiedReferences) {
        if (item == null || !Integer.valueOf(2).equals(item.getSchemaVersion()) || item.getScenes() == null
                || item.getScenes().stream().noneMatch(Objects::nonNull)) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【场景安排】用这些信息安排人物的反应和本场经过。\n");
        int index = 1;
        for (ScenePlan scene : item.getScenes()) {
            if (scene == null) {
                continue;
            }
            sb.append("\n场景 ").append(index++);
            String whereWhen = formatWhereWhen(scene.time(), scene.location());
            if (!whereWhen.isEmpty()) {
                sb.append("（").append(whereWhen).append("，仅作定位，不必在首句报时）");
            }
            sb.append("\n- 在场人物：").append(String.join("、", scene.characters()));
            appendWritingLine(sb, "当下想做的事", scene.intention());
            appendWritingLine(sb, "眼前的阻碍", scene.resistance());
            appendWritingLine(sb, "本场落点", StringUtils.defaultIfBlank(scene.outcome(), scene.function()));
            if (StringUtils.isNotBlank(scene.outcome()) && quietFunction(scene.function())
                    && !scene.outcome().trim().equals(scene.function().trim())) {
                appendWritingLine(sb, "余味与节奏", scene.function());
            }
            appendWritingFacts(sb, "已证实的前情", scene.entryStates(), verifiedReferences);
            if (scene.causality() != null) {
                appendWritingCausality(sb, scene.causality());
                appendWritingFacts(sb, "已证实的限制", scene.causality().constraints(), verifiedReferences);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void appendWritingCausality(StringBuilder sb, SceneCausality causality) {
        if (causality.actors() != null) {
            for (var actor : causality.actors()) {
                if (actor != null) {
                    appendWritingLine(sb, StringUtils.defaultIfBlank(actor.character(), "人物") + "此刻相信", actor.belief());
                }
            }
        }
        if (causality.steps() != null) {
            for (var step : causality.steps()) {
                if (step == null) continue;
                appendWritingLine(sb, StringUtils.defaultIfBlank(step.actor(), "人物") + "的行动", step.action());
                appendWritingLine(sb, StringUtils.defaultIfBlank(step.responder(), "对方") + "的回应", step.response());
                if (step.choice() != null) {
                    appendWritingLine(sb, "放弃的做法", step.choice().foregoneOption());
                    appendWritingLine(sb, "这个选择的代价", step.choice().cost());
                }
            }
        }
        var terms = causality.terms();
        if (SceneCausalityPolicy.hasTerms(terms)) {
            appendWritingLine(sb, StringUtils.trimToEmpty(terms.offerer()) + "向"
                    + StringUtils.trimToEmpty(terms.receiver()) + "提供", terms.consideration());
            appendWritingLine(sb, "回报", terms.payment());
            appendWritingLine(sb, "验收方式", terms.verificationBy());
            appendWritingLine(sb, "期限", terms.deadline());
            appendWritingLine(sb, "未达约定时", terms.onFailure());
        }
        if (causality.verificationObligations() != null) {
            for (var obligation : causality.verificationObligations()) {
                if (obligation == null) continue;
                sb.append("\n- 本场核验：").append(text(obligation.verifier())).append("核验")
                        .append(text(obligation.subject())).append("的").append(text(obligation.item()));
                appendWritingLine(sb, "判定标准", obligation.criterion());
                appendWritingLine(sb, "结果用于", obligation.resultUse());
                if (StringUtils.isNotBlank(obligation.allowedAlternative())) {
                    appendWritingLine(sb, "已授权替代及条件", obligation.allowedAlternative());
                } else {
                    sb.append("\n- 替代边界：未授权替代，不得换被验主体、项目或标准来宣称已完成。");
                }
            }
        }
    }

    private static void appendWritingFacts(StringBuilder sb, String label, List<FactReference> references,
                                           Set<FactReference> verifiedReferences) {
        if (references == null || verifiedReferences == null || verifiedReferences.isEmpty()) return;
        List<String> facts = references.stream().filter(Objects::nonNull)
                .filter(verifiedReferences::contains)
                .map(fact -> fact.subject().trim() + "：" + fact.status().trim())
                .distinct()
                .toList();
        appendWritingLine(sb, label, String.join("；", facts));
    }

    private static void appendWritingLine(StringBuilder sb, String label, String value) {
        if (StringUtils.isNotBlank(value)) {
            sb.append("\n- ").append(label).append("：").append(value.trim());
        }
    }

    private static String formatWhereWhen(String time, String location) {
        boolean hasTime = StringUtils.isNotBlank(time);
        boolean hasLoc = StringUtils.isNotBlank(location);
        if (hasTime && hasLoc) {
            return time.trim() + " · " + location.trim();
        }
        return hasTime ? time.trim() : (hasLoc ? location.trim() : "");
    }

    /** 完整渲染权威场景，保留 ID 和引用边界，不把入场事实或里程碑引用写成新结果。 */
    public static String render(List<ScenePlan> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【权威场景契约】场景最终结果以 outcome/function 为准。location/time 是连续性元数据，用于核对场景状态，不要求在首句或第一段显式写出。\n");
        int index = 1;
        for (ScenePlan scene : scenes) {
            if (scene == null) {
                sb.append("场景 ").append(index++).append("：（无效空场景）\n");
                continue;
            }
            sb.append("场景 ").append(index++).append("｜id：").append(text(scene.id()))
                    .append("\n人物：").append(scene.characters())
                    .append("\n意图(intention)：").append(text(scene.intention()))
                    .append("\n阻力(resistance)：").append(text(scene.resistance()))
                    .append("\n最终结果(outcome)：").append(text(scene.outcome()))
                    .append("\n场景功能(function)：").append(text(scene.function()))
                    .append("\n地点(location)：").append(text(scene.location()))
                    .append("\n时间(time)：").append(text(scene.time()))
                    .append("\n入场前提引用(entryStates，待核验，不是本场新结果)：");
            if (scene.entryStates().isEmpty()) {
                sb.append("[]");
            }
            for (int i = 0; i < scene.entryStates().size(); i++) {
                FactReference fact = scene.entryStates().get(i);
                sb.append("\n  entryStates[").append(i).append("]：").append(sourceStatus(fact));
                if (fact == null) {
                    sb.append("（空引用）");
                    continue;
                }
                sb.append("\n  {ledger=").append(text(fact.ledger()))
                        .append(", chapterNo=").append(fact.chapterNo())
                        .append(", manuscriptHash=").append(text(fact.manuscriptHash()))
                        .append(", subject=").append(text(fact.subject()))
                        .append(", status=").append(text(fact.status()))
                        .append(", evidence=").append(text(fact.evidence())).append("}");
            }
            sb.append("\n里程碑引用(milestoneRefs，仅映射待推进目标，不代表已完成)：")
                    .append(scene.milestoneRefs());
            sb.append("\n");
            if (scene.causality() != null) {
                sb.append("可选因果补充(causality；belief 是角色主观所信，可与事实不符；")
                        .append("knowledgeRefs 是人物事实 ID；requires 同样只引用人物事实 ID；")
                        .append("profileRef 是作者档案的 characterId/revision/contentHash/fields 引用，")
                        .append("不等于人物已知或正文事实，不得放入 knowledgeRefs/requires；")
                        .append("choice 仅为选择语境，已选行动见 action、理由见 because；")
                        .append("冲突、放弃项、代价与关系影响均按实际适用，不要求补齐；")
                        .append("verificationObligations 按 verifier/subject/item/criterion/resultUse 绑定核验任务，")
                        .append("allowedAlternative 仅为已授权替代，缺省不得新增替代规则；核验结果不等于自动成功；")
                        .append("constraints 是待核验约束引用，非本场新结果)：\n")
                        .append(JSON.toJSONString(scene.causality())).append("\n");
                List<FactReference> constraints = scene.causality().constraints();
                if (constraints != null) {
                    for (int i = 0; i < constraints.size(); i++) {
                        sb.append("  causality.constraints[").append(i).append("]：")
                                .append(sourceStatus(constraints.get(i))).append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 只检查来源形状；字段齐全不证明已与正文或账本核对。 */
    private static String sourceStatus(FactReference fact) {
        if (fact != null && StringUtils.isNotBlank(fact.ledger())
                && fact.chapterNo() != null && fact.chapterNo() > 0
                && fact.manuscriptHash() != null && fact.manuscriptHash().matches("[0-9a-fA-F]{64}")
                && StringUtils.isNotBlank(fact.subject()) && StringUtils.isNotBlank(fact.status())
                && StringUtils.isNotBlank(fact.evidence())) {
            return "来源字段齐全，仍须与可信来源核对；字段齐全不等于已核验。";
        }
        return "来源缺失或不完整：未核验描述，不得仅凭此条认定既成事实或已具备能力。";
    }

    private static String text(String value) {
        return StringUtils.defaultString(value);
    }
}
