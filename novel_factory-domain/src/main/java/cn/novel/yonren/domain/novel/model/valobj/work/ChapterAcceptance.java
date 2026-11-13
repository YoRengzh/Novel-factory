package cn.novel.yonren.domain.novel.model.valobj.work;

import cn.novel.yonren.domain.novel.model.valobj.quality.GateResult;

/**
 * 正文接受记录：只对所记录的稿件及其输入版本有效。
 *
 * <p>稿件哈希、计划哈希与依据指纹三者共同锁定"这一份稿"的准入结论。
 * {@code basisChanged} 为真表示该章之前存在更晚的人工编辑，记录本身仍然有效，
 * 但依据已经漂移，下游不能把它当作当前状态直接沿用。
 */
public record ChapterAcceptance(Method method, String manuscriptHash, String basis, String planHash,
                                GateResult checks, String actor, String reason, long acceptedAtMs,
                                boolean basisChanged) {

    public ChapterAcceptance(Method method, String manuscriptHash, String basis, String planHash,
                             GateResult checks, String actor, String reason, long acceptedAtMs) {
        this(method, manuscriptHash, basis, planHash, checks, actor, reason, acceptedAtMs, false);
    }

    public ChapterAcceptance withBasisChanged() {
        return new ChapterAcceptance(method, manuscriptHash, basis, planHash, checks, actor, reason, acceptedAtMs, true);
    }

    /** 准入方式：自动通过、带警告继续、人工接受、人工编辑、旧数据基线。 */
    public enum Method { AUTOMATIC, AUTOMATIC_WITH_WARNING, HUMAN, HUMAN_EDIT, LEGACY_BASELINE }
}
