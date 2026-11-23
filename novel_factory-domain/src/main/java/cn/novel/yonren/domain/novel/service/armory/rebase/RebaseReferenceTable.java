package cn.novel.yonren.domain.novel.service.armory.rebase;

import cn.novel.yonren.domain.novel.model.valobj.character.CharacterEffect;
import cn.novel.yonren.domain.novel.model.valobj.plan.FactReference;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重基候选的引用选择表。
 *
 * <p>模型只允许用 P1/S1 这类短 ID 指向输入中的来源，不允许自造 factId、章节 hash 或长原文。
 * 程序负责把 ID 解析回真实状态/事实，解析不到就是无效引用——不用相似度或猜测补授权。
 */
public final class RebaseReferenceTable {

    /** 一个人物状态/事实引用：ID、主体、来源、简短语义与可选原文。 */
    public record Reference(String id, String kind, String character, String value, String evidence) {
    }

    private final Map<String, Reference> byId = new LinkedHashMap<>();

    public RebaseReferenceTable(List<CharacterEffect> effects, List<ScenePlan> scenes) {
        int fact = 1;
        if (effects != null) {
            for (CharacterEffect effect : effects) {
                if (effect == null || StringUtils.isBlank(effect.character()) || StringUtils.isBlank(effect.value())) {
                    continue;
                }
                String id = "P" + fact++;
                byId.put(id, new Reference(id, effect.kind() + "/" + effect.state(), effect.character(),
                        effect.value(), effect.evidence()));
            }
        }
        int scene = 1;
        if (scenes != null) {
            for (ScenePlan plan : scenes) {
                if (plan == null) continue;
                String id = "S" + scene++;
                byId.put(id, new Reference(id, "SCENE", null,
                        StringUtils.defaultString(plan.intention()), null));
            }
        }
    }

    public Reference get(String id) {
        return id == null ? null : byId.get(id.trim());
    }

    public boolean has(String id) {
        return get(id) != null;
    }

    public List<Reference> all() {
        return List.copyOf(byId.values());
    }

    /** 供 prompt 渲染：只给短 ID 与语义，不给内部 hash。 */
    public String render() {
        StringBuilder text = new StringBuilder();
        for (Reference reference : byId.values()) {
            text.append("- ").append(reference.id()).append("｜").append(reference.kind());
            if (StringUtils.isNotBlank(reference.character())) {
                text.append("｜").append(reference.character());
            }
            text.append("：").append(StringUtils.abbreviate(reference.value(), 160)).append('\n');
        }
        return text.toString();
    }

    /** 把引用 ID 解析为校验过的 FactReference；不存在或语义不符返回 null。 */
    public static FactReference asFact(Reference reference, List<FactReference> facts) {
        if (reference == null || facts == null) return null;
        for (FactReference fact : facts) {
            if (fact == null || !StringUtils.equals(fact.subject(), reference.character())) continue;
            if (StringUtils.isNotBlank(reference.evidence()) && !fact.evidence().contains(reference.evidence())) {
                continue;
            }
            return fact;
        }
        return null;
    }

    /** 场景引用列表，供补丁校验使用。 */
    public List<String> sceneIds() {
        List<String> ids = new ArrayList<>();
        for (Reference reference : byId.values()) {
            if ("SCENE".equals(reference.kind())) ids.add(reference.id());
        }
        return ids;
    }
}
