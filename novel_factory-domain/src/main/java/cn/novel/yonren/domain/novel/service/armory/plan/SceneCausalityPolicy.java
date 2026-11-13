package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.ActorInput;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.CausalStep;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.ProfileRef;
import cn.novel.yonren.domain.novel.model.valobj.plan.SceneCausality.Terms;
import cn.novel.yonren.domain.novel.model.valobj.plan.ScenePlan;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 只检查已声明因果数据的身份和结构，不调用模型，不证明语义因果或世界事实为真。
 * 不要求每场提供 actors/steps/terms，也不为静场、独处场景补对白、回应、阻力或代价。
 */
public final class SceneCausalityPolicy {
    private SceneCausalityPolicy() {
    }

    /** 返回以 causality 开头的相对字段路径；章内场景下标由 ScenePlanPolicy 补充。 */
    public static List<String> violations(ScenePlan scene) {
        List<String> violations = new ArrayList<>();
        if (scene == null || scene.causality() == null) {
            return violations;
        }
        SceneCausality causality = scene.causality();
        Set<String> characters = new HashSet<>();
        for (String character : scene.characters()) {
            if (StringUtils.isNotBlank(character)) {
                characters.add(character.trim());
            }
        }

        Set<String> declaredActors = new HashSet<>();
        List<ActorInput> actors = emptyIfNull(causality.actors());
        for (int i = 0; i < actors.size(); i++) {
            ActorInput actor = actors.get(i);
            String at = "causality.actors[" + i + "]";
            if (actor == null) {
                violations.add(at + " 不能含空人物输入");
                continue;
            }
            checkCharacter(actor.character(), at + ".character", characters, violations);
            if (StringUtils.isNotBlank(actor.character()) && !declaredActors.add(actor.character().trim())) {
                violations.add(at + ".character 重复：" + actor.character());
            }
            checkFactIdEntries(actor.knowledgeRefs(), at + ".knowledgeRefs", violations);
            checkTextEntries(actor.resources(), at + ".resources", violations);
            checkProfileRef(actor.profileRef(), at + ".profileRef", violations);
            // 不按 subject/status 解析 belief，也不在此解析或杜撰 knowledgeRefs 的事实 ID。
        }

        List<CausalStep> steps = emptyIfNull(causality.steps());
        for (int i = 0; i < steps.size(); i++) {
            CausalStep step = steps.get(i);
            String at = "causality.steps[" + i + "]";
            if (step == null) {
                violations.add(at + " 不能含空因果步骤");
                continue;
            }
            checkCharacter(step.actor(), at + ".actor", characters, violations);
            requireText(step.action(), at + ".action", "已提供步骤的行动未明", violations);
            if (StringUtils.isNotBlank(step.responder())) {
                checkCharacter(step.responder(), at + ".responder", characters, violations);
            } else if (StringUtils.isNotBlank(step.response())) {
                violations.add(at + ".responder 已提供 response，但回应人物未明");
            }
            checkFactIdEntries(step.requires(), at + ".requires", violations);
            // choice 只补充已声明选择的语境；任何单项均可不适用，不机械要求代价或关系变化。
        }

        checkTerms(causality.terms(), characters, violations);
        List<?> constraints = emptyIfNull(causality.constraints());
        for (int i = 0; i < constraints.size(); i++) {
            if (constraints.get(i) == null) {
                violations.add("causality.constraints[" + i + "] 不能含空事实引用");
            }
        }
        // 非空 constraints 原样保留给 PlanAcceptance 核验来源；此处不合并主体、不验证真假。
        return violations;
    }

    private static void checkProfileRef(ProfileRef reference, String at, List<String> violations) {
        if (reference == null) {
            return;
        }
        requireText(reference.characterId(), at + ".characterId", "档案人物 ID 未明", violations);
        requireText(reference.revision(), at + ".revision", "档案版本未明", violations);
        if (reference.contentHash() == null || !reference.contentHash().matches("[0-9a-fA-F]{64}")) {
            violations.add(at + ".contentHash 必须为档案版本的 SHA-256 内容哈希");
        }
        if (reference.fields() == null || reference.fields().isEmpty()) {
            violations.add(at + ".fields 必须明确引用的档案字段");
        } else {
            checkTextEntries(reference.fields(), at + ".fields", violations);
            Set<String> fields = new HashSet<>();
            for (int i = 0; i < reference.fields().size(); i++) {
                String field = reference.fields().get(i);
                if (StringUtils.isNotBlank(field) && !fields.add(field.trim())) {
                    violations.add(at + ".fields[" + i + "] 档案字段重复：" + field);
                }
            }
        }
        // 此处只核对引用形状；不把作者档案当成正文事实，也不查档案内容或评定人物深度。
    }

    private static void checkFactIdEntries(List<String> entries, String at, List<String> violations) {
        checkTextEntries(entries, at, violations);
        List<String> values = emptyIfNull(entries);
        for (int i = 0; i < values.size(); i++) {
            String value = StringUtils.trimToEmpty(values.get(i));
            boolean profileReference = StringUtils.startsWithIgnoreCase(value, "profile:")
                    || StringUtils.startsWithIgnoreCase(value, "profileRef:")
                    || StringUtils.startsWithIgnoreCase(value, "profile-ref:")
                    || value.startsWith("{") && (value.contains("\"profileRef\"")
                    || value.contains("\"characterId\"") && value.contains("\"revision\""));
            if (profileReference) {
                violations.add(at + "[" + i + "] 仅可引用人物事实 ID，档案引用须放在 profileRef，不能混用");
            }
        }
    }

    private static void checkTerms(Terms terms, Set<String> characters, List<String> violations) {
        if (terms == null || !hasTerms(terms)) {
            return;
        }
        String at = "causality.terms";
        checkCharacter(terms.offerer(), at + ".offerer", characters, violations);
        checkCharacter(terms.receiver(), at + ".receiver", characters, violations);
        if (StringUtils.isNotBlank(terms.offerer()) && StringUtils.isNotBlank(terms.receiver())
                && terms.offerer().trim().equals(terms.receiver().trim())) {
            violations.add(at + ".receiver 交换双方不能是同一人物：" + terms.receiver());
        }
        requireText(terms.consideration(), at + ".consideration", "已声明交换的提供内容未明", violations);
        requireText(terms.payment(), at + ".payment", "已声明交换的支付或回报未明；无偿可明示为无偿", violations);
        // 核验方式、截止时间和失败处置可不适用；不从自由文本推断或自动补全附加条款。
    }

    private static boolean hasTerms(Terms terms) {
        return StringUtils.isNotBlank(terms.offerer()) || StringUtils.isNotBlank(terms.receiver())
                || StringUtils.isNotBlank(terms.consideration()) || StringUtils.isNotBlank(terms.payment())
                || StringUtils.isNotBlank(terms.verificationBy()) || StringUtils.isNotBlank(terms.deadline())
                || StringUtils.isNotBlank(terms.onFailure());
    }

    private static void checkCharacter(String character, String at, Set<String> characters,
                                       List<String> violations) {
        if (StringUtils.isBlank(character)) {
            violations.add(at + " 已提供条目的人物身份未明");
        } else if (!characters.contains(character.trim())) {
            violations.add(at + " 人物不在 scene.characters：" + character);
        }
    }

    private static void requireText(String value, String at, String description, List<String> violations) {
        if (StringUtils.isBlank(value)) {
            violations.add(at + " " + description);
        }
    }

    private static void checkTextEntries(List<String> entries, String at, List<String> violations) {
        List<String> values = emptyIfNull(entries);
        for (int i = 0; i < values.size(); i++) {
            if (StringUtils.isBlank(values.get(i))) {
                violations.add(at + "[" + i + "] 不能含空条目");
            }
        }
    }

    private static <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? List.of() : values;
    }
}
