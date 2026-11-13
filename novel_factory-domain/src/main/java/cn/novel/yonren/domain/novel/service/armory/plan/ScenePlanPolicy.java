package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /** 完整渲染权威场景，保留 ID 和引用边界，不把入场事实或里程碑引用写成新结果。 */
    public static String render(List<ScenePlan> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【权威场景契约】场景最终结果以 outcome/function 为准。\n");
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
                    .append("\n已有事实引用(entryStates，入场状态，不是本场新结果)：");
            if (scene.entryStates().isEmpty()) {
                sb.append("[]");
            }
            for (FactReference fact : scene.entryStates()) {
                if (fact == null) {
                    sb.append("\n  （空引用）");
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
                    .append(scene.milestoneRefs()).append("\n");
            if (scene.causality() != null) {
                sb.append("可选因果补充(causality；belief 是角色主观所信，可与事实不符；")
                        .append("knowledgeRefs 是人物事实 ID；requires 同样只引用人物事实 ID；")
                        .append("profileRef 是作者档案的 characterId/revision/contentHash/fields 引用，")
                        .append("不等于人物已知或正文事实，不得放入 knowledgeRefs/requires；")
                        .append("choice 仅为选择语境，已选行动见 action、理由见 because；")
                        .append("冲突、放弃项、代价与关系影响均按实际适用，不要求补齐；")
                        .append("constraints 是待核验的真实事实引用，非本场新结果)：\n")
                        .append(JSON.toJSONString(scene.causality())).append("\n");
            }
        }
        return sb.toString();
    }

    private static String text(String value) {
        return StringUtils.defaultString(value);
    }
}
