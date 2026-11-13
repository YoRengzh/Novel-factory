package cn.novel.yonren.domain.novel.model.valobj.character;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 正文中的人物变化；身份与来源字段由代码绑定，意图和信念不等于世界事实。 */
public record CharacterEffect(String character, Kind kind, String key, State state, String value,
                              String evidence, String acquisition, String referenceId,
                              String characterId, String factId, String effectId,
                              Integer chapterNo, String manuscriptHash,
                              @JsonInclude(JsonInclude.Include.NON_NULL) EvidenceSupport evidenceSupport) {
    public enum Kind { ALIAS, KNOWLEDGE, BELIEF, GOAL, DECISION, INTENT, ACTION, COMMITMENT }
    public enum State { ESTABLISHED, SUSPECTED, INTENDED, REQUESTED, ACCEPTED, ATTEMPTED,
                        COMPLETED, FAILED, WITHDRAWN, FORGOTTEN }

    /** 证据的叙述层次，不是抽取置信度；BELIEF/SUSPECTED 表示人物本人的怀疑。 */
    public enum Attribution { EXPLICIT_NARRATION, DIRECT_STATEMENT, REPORTED, HYPOTHETICAL, AMBIGUOUS, INFERRED_ACTION }

    /**
     * 可选 v1 支持信息。context 是完整连续正文上下文，order 是代码回查的 UTF-16 原句偏移。
     * verification 仅记录做过哪种检查，绝不是语义证明；模型给出的标记/偏移不获授权。
     * supportRefs 只引用此前已确认 effectId，不新设账本，也不靠相似文本编造因果。
     * scope/target 是可选的原文明示限定，不改变旧 factId；不同任务仍应使用不同 key。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvidenceSupport(Integer version, Attribution attribution, String subject, String context,
                                  Integer order, List<String> supportRefs, String scope, String target,
                                  String verification) {
        public EvidenceSupport {
            if (supportRefs != null) supportRefs = Collections.unmodifiableList(new ArrayList<>(supportRefs));
        }
    }

    public CharacterEffect(String character, Kind kind, String key, State state, String value,
                           String evidence, String acquisition, String referenceId) {
        this(character, kind, key, state, value, evidence, acquisition, referenceId,
                null, null, null, null, null, null);
    }

    /** 旧十三参构造器及 null JSON 形态保持不变。 */
    public CharacterEffect(String character, Kind kind, String key, State state, String value,
                           String evidence, String acquisition, String referenceId,
                           String characterId, String factId, String effectId,
                           Integer chapterNo, String manuscriptHash) {
        this(character, kind, key, state, value, evidence, acquisition, referenceId,
                characterId, factId, effectId, chapterNo, manuscriptHash, null);
    }

    /** 新抽取候选无需填写任何来源身份字段。 */
    public CharacterEffect(String character, Kind kind, String key, State state, String value,
                           String evidence, String acquisition, String referenceId, EvidenceSupport evidenceSupport) {
        this(character, kind, key, state, value, evidence, acquisition, referenceId,
                null, null, null, null, null, evidenceSupport);
    }
}
