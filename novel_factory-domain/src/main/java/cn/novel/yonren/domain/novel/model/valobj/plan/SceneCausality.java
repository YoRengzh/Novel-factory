package cn.novel.yonren.domain.novel.model.valobj.plan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可选的场景因果补充，不是每场必填的冲突、交易、对白或转折模板。
 * null/空集合表示未声明；仅保存调用方提供的内容，不补造动机、回应或条件。
 * 角色认知与世界事实分开：belief 允许误信，knowledgeRefs 引用人物事实 ID，
 * constraints 才是交给事实验收策略核验的 FactReference，不能按主体合并为角色认知。
 * profileRef 只引用作者人物档案的指定版本/字段，不证明人物已经知道、承诺或完成任何事。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SceneCausality(List<ActorInput> actors, List<CausalStep> steps,
                             Terms terms, List<FactReference> constraints) {

    public SceneCausality {
        actors = optionalList(actors);
        steps = optionalList(steps);
        constraints = optionalList(constraints);
    }

    /**
     * knowledgeRefs 是 P1 提供的确定性人物事实 ID，不是按 subject 唯一的世界状态引用。
     * belief 是角色主观所信，即使错误也不自动构成世界事实矛盾。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActorInput(String character, String want, String pressure,
                             List<String> knowledgeRefs, List<String> resources, String belief,
                             ProfileRef profileRef) {
        public ActorInput(String character, String want, String pressure,
                          List<String> knowledgeRefs, List<String> resources) {
            this(character, want, pressure, knowledgeRefs, resources, null, null);
        }

        public ActorInput(String character, String want, String pressure,
                          List<String> knowledgeRefs, List<String> resources, String belief) {
            this(character, want, pressure, knowledgeRefs, resources, belief, null);
        }

        public ActorInput {
            knowledgeRefs = optionalList(knowledgeRefs);
            resources = optionalList(resources);
        }
    }

    /** 作者档案引用；revision/contentHash 固定版本，fields 只指向该版本里明确使用的字段。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileRef(String characterId, String revision, String contentHash, List<String> fields) {
        public ProfileRef {
            fields = optionalList(fields);
        }
    }

    /** 可只有一个人的行动；回应、原因与前置条件均不构成填写配额。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CausalStep(String actor, String action, String responder, String response,
                             String because, List<String> requires, Choice choice) {
        public CausalStep(String actor, String action, String responder, String response,
                          String because, List<String> requires) {
            this(actor, action, responder, response, because, requires, null);
        }

        public CausalStep {
            requires = optionalList(requires);
        }
    }

    /** 可选的选择语境；已选行动仍在 action，理由仍在 because，不强制冲突、放弃项或代价。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(String valueConflict, String foregoneOption, String cost,
                         String expectedRelationshipImpact) {
    }

    /**
     * 仅在场景实际声明交换时使用；无偿也可明示为无偿，不凭空追加代价。
     * verificationBy 是核验主体/方式说明，deadline/onFailure 可不适用，不要求即时交易填写。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Terms(String offerer, String receiver, String consideration, String payment,
                        String verificationBy, String deadline, String onFailure) {
    }

    private static <T> List<T> optionalList(List<T> values) {
        // 保留 absence 和非法 null 元素，后者由纯 policy 按位置报错，不在读取时丢弃。
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
