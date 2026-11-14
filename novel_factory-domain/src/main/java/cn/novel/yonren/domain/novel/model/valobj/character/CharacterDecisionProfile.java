package cn.novel.yonren.domain.novel.model.valobj.character;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 决策设计不是历史事实；身份、来源和采纳状态由服务端绑定。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterDecisionProfile(String characterId, String displayName, Boolean locked,
                                       String want, String need, String fearAndDefense,
                                       String valuePriorities, String boundaries, String independentConcerns,
                                       String decisionPattern, Source source, Adoption adoption) {
    public enum Source { AUTHOR, MODEL_DESIGN }
    public enum Adoption { AUTHOR_CONFIRMED, AUTO_APPLIED, DRAFT }

    public CharacterDecisionProfile(String displayName, Boolean locked, String want, String need,
                                    String fearAndDefense, String valuePriorities, String boundaries,
                                    String independentConcerns, String decisionPattern) {
        this(null, displayName, locked, want, need, fearAndDefense, valuePriorities, boundaries,
                independentConcerns, decisionPattern, null, null);
    }
}
